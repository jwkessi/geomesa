/***********************************************************************
* Copyright (c) 2013-2016 Commonwealth Computer Research, Inc.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Apache License, Version 2.0
* which accompanies this distribution and is available at
* http://www.opensource.org/licenses/apache2.0.php.
*************************************************************************/

package org.locationtech.geomesa.accumulo.data.stats

import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}
import java.util.concurrent.{ScheduledFuture, ScheduledThreadPoolExecutor, TimeUnit}

import com.google.common.collect.ImmutableSortedSet
import com.google.common.util.concurrent.MoreExecutors
import org.apache.accumulo.core.client.{Connector, IteratorSetting}
import org.apache.accumulo.core.iterators.IteratorUtil.IteratorScope
import org.geotools.data.{Query, Transaction}
import org.joda.time._
import org.locationtech.geomesa.accumulo.AccumuloVersion
import org.locationtech.geomesa.accumulo.data.{MultiRowAccumuloMetadata, _}
import org.locationtech.geomesa.accumulo.index.AccumuloFeatureIndex
import org.locationtech.geomesa.accumulo.index.z2.Z2IndexV1
import org.locationtech.geomesa.accumulo.index.z3.Z3IndexV2
import org.locationtech.geomesa.accumulo.iterators.KryoLazyStatsIterator
import org.locationtech.geomesa.filter._
import org.locationtech.geomesa.index.conf.QueryHints
import org.locationtech.geomesa.index.stats.MetadataBackedStats.KeyAndStat
import org.locationtech.geomesa.index.stats._
import org.locationtech.geomesa.utils.collection.SelfClosingIterator
import org.locationtech.geomesa.utils.geotools.RichSimpleFeatureType.RichSimpleFeatureType
import org.locationtech.geomesa.utils.geotools.SimpleFeatureTypes
import org.locationtech.geomesa.utils.index.IndexMode
import org.locationtech.geomesa.utils.stats._
import org.opengis.feature.simple.SimpleFeatureType
import org.opengis.filter._

import scala.collection.JavaConversions._

/**
 * Tracks stats via entries stored in metadata.
 */
class AccumuloGeoMesaStats(val ds: AccumuloDataStore, statsTable: String, val generateStats: Boolean)
    extends MetadataBackedStats[AccumuloDataStore] {

  import AccumuloGeoMesaStats._

  override protected val metadata = new MultiRowAccumuloMetadata(ds.connector, statsTable, new StatsMetadataSerializer(ds))

  private val compactionScheduled = new AtomicBoolean(false)
  private val lastCompaction = new AtomicLong(0L)

  private val running = new AtomicBoolean(true)
  private var scheduledCompaction: ScheduledFuture[_] = null

  private val compactor = new Runnable() {
    override def run(): Unit = {
      import org.locationtech.geomesa.accumulo.AccumuloProperties.StatsProperties.STAT_COMPACTION_MILLIS
      val compactInterval = STAT_COMPACTION_MILLIS.get.toLong
      if (lastCompaction.get < DateTimeUtils.currentTimeMillis() - compactInterval &&
          compactionScheduled.compareAndSet(true, false) ) {
        compact()
      }
      if (running.get) {
        synchronized(scheduledCompaction = executor.schedule(this, compactInterval, TimeUnit.MILLISECONDS))
      }
    }
  }

  compactor.run() // schedule initial compaction

  override def getCount(sft: SimpleFeatureType, filter: Filter, exact: Boolean): Option[Long] = {
    lazy val hasDupes = sft.nonPoints && {
      val indices = AccumuloFeatureIndex.indices(sft, IndexMode.Read)
      // TODO check for multivalued attribute indices
      Seq(Z2IndexV1, Z3IndexV2).exists(indices.contains)
    }
    if (exact && hasDupes) {
      // stat query doesn't entirely handle duplicates - only on a per-iterator basis
      // is a full scan worth it? the stat will be pretty close...

      // restrict fields coming back so that we push as little data as possible
      val props = Array(Option(sft.getGeomField).getOrElse(sft.getDescriptor(0).getLocalName))
      val query = new Query(sft.getTypeName, filter, props)
      // length of an iterator is an int... this is Big Data
      var count = 0L
      SelfClosingIterator(ds.getFeatureReader(query, Transaction.AUTO_COMMIT)).foreach(_ => count += 1)
      Some(count)
    } else {
      super.getCount(sft, filter, exact)
    }
  }

  override def runStats[T <: Stat](sft: SimpleFeatureType, stats: String, filter: Filter): Seq[T] = {
    val query = new Query(sft.getTypeName, filter)
    query.getHints.put(QueryHints.STATS_KEY, stats)
    query.getHints.put(QueryHints.RETURN_ENCODED_KEY, java.lang.Boolean.TRUE)

    try {
      val reader = ds.getFeatureReader(query, Transaction.AUTO_COMMIT)
      val result = try {
        // stats should always return exactly one result, even if there are no features in the table
        KryoLazyStatsIterator.decodeStat(reader.next.getAttribute(0).asInstanceOf[String], sft)
      } finally {
        reader.close()
      }
      result match {
        case s: SeqStat => s.stats.asInstanceOf[Seq[T]]
        case s => Seq(s).asInstanceOf[Seq[T]]
      }
    } catch {
      case e: Exception =>
        logger.error(s"Error running stats query with stats '$stats' and filter '${filterToString(filter)}'", e)
        Seq.empty
    }
  }

  override def statUpdater(sft: SimpleFeatureType): StatUpdater =
    if (generateStats) new MetadataStatUpdater(this, sft, Stat(sft, buildStatsFor(sft))) else NoopStatUpdater

  override def close(): Unit = {
    super.close()
    running.set(false)
    synchronized(scheduledCompaction.cancel(false))
  }

  override protected def writeAuthoritative(typeName: String, toWrite: Seq[KeyAndStat]): Unit = {
    // due to accumulo issues with combiners, deletes and compactions, we have to:
    // 1) delete the existing data; 2) compact the table; 3) insert the new value
    // see: https://issues.apache.org/jira/browse/ACCUMULO-2232
    toWrite.foreach(ks => metadata.remove(typeName, ks.key))
    compact()
    toWrite.foreach(ks => metadata.insert(typeName, ks.key, ks.stat))
  }

  /**
    * Schedules a compaction for the stat table
    */
  private [stats] def scheduleCompaction(): Unit = compactionScheduled.set(true)

  /**
    * Performs a synchronous compaction of the stats table
    */
  private def compact(): Unit = {
    compactionScheduled.set(false)
    ds.connector.tableOperations().compact(statsTable, null, null, true, true)
    lastCompaction.set(DateTimeUtils.currentTimeMillis())
  }
}

/**
  * Stores stats as metadata entries
  *
  * @param stats persistence
  * @param sft simple feature type
  * @param statFunction creates stats for tracking new features - this will be re-created on flush,
  *                     so that our bounds are more accurate
  */
class AccumuloStatUpdater(stats: AccumuloGeoMesaStats, sft: SimpleFeatureType, statFunction: => Stat)
    extends MetadataStatUpdater(stats, sft, statFunction) {

  override def close(): Unit = {
    super.close()
    // schedule a compaction so our metadata doesn't stack up too much
    stats.scheduleCompaction()
  }
}

object AccumuloGeoMesaStats {

  val CombinerName = "stats-combiner"

  private [stats] val executor = MoreExecutors.getExitingScheduledExecutorService(new ScheduledThreadPoolExecutor(3))
  sys.addShutdownHook(executor.shutdownNow())

  /**
    * Configures the stat combiner to sum stats dynamically.
    *
    * Note: should be called with a distributed lock on the stats table
    *
    * @param connector accumulo connector
    * @param table stats table
    * @param sft simple feature type
    */
  def configureStatCombiner(connector: Connector, table: String, sft: SimpleFeatureType): Unit = {
    import MetadataBackedStats._

    AccumuloVersion.ensureTableExists(connector, table)
    val tableOps = connector.tableOperations()

    def attach(options: Map[String, String]): Unit = {
      // priority needs to be less than the versioning iterator at 20
      val is = new IteratorSetting(10, CombinerName, classOf[StatsCombiner])
      options.foreach { case (k, v) => is.addOption(k, v) }
      tableOps.attachIterator(table, is)

      val keys = Seq(CountKey, BoundsKeyPrefix, TopKKeyPrefix, FrequencyKeyPrefix, HistogramKeyPrefix)
      val splits = keys.map(k => MultiRowAccumuloMetadata.getRowKey(sft.getTypeName, k))
      // noinspection RedundantCollectionConversion
      tableOps.addSplits(table, ImmutableSortedSet.copyOf(splits.toIterable))
    }

    val sftKey = s"${StatsCombiner.SftOption}${sft.getTypeName}"
    val sftOpt = SimpleFeatureTypes.encodeType(sft)

    val existing = tableOps.getIteratorSetting(table, CombinerName, IteratorScope.scan)
    if (existing == null) {
      attach(Map(sftKey -> sftOpt, "all" -> "true"))
    } else {
      val existingSfts = existing.getOptions.filter(_._1.startsWith(StatsCombiner.SftOption))
      if (!existingSfts.get(sftKey).exists(_ == sftOpt)) {
        tableOps.removeIterator(table, CombinerName, java.util.EnumSet.allOf(classOf[IteratorScope]))
        attach(existingSfts.toMap ++ Map(sftKey -> sftOpt, "all" -> "true"))
      }
    }
  }
}
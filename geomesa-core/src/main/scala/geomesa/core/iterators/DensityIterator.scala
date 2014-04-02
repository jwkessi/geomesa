package geomesa.core.iterators

import collection.JavaConversions._
import com.google.common.collect._
import com.vividsolutions.jts.geom.{Polygon, Point}
import geomesa.utils.text.WKTUtils
import java.awt.Rectangle
import java.awt.geom.AffineTransform
import java.io.{ByteArrayInputStream, DataInputStream, DataOutputStream, ByteArrayOutputStream}
import java.{util => ju}
import org.apache.accumulo.core.client.IteratorSetting
import org.apache.accumulo.core.data.{Range => ARange, PartialKey, ByteSequence, Value, Key}
import org.apache.accumulo.core.iterators.{IteratorEnvironment, SortedKeyValueIterator}
import org.apache.commons.codec.binary.Base64
import org.geotools.data.DataUtilities
import org.geotools.feature.simple.SimpleFeatureBuilder
import org.geotools.geometry.jts.{JTS, ReferencedEnvelope}
import org.geotools.renderer.lite.RendererUtilities
import org.opengis.feature.simple.SimpleFeatureType
import scala.util.Random

class DensityIterator extends SimpleFeatureFilteringIterator {

  import geomesa.utils.geotools.Conversions._

  var bbox: ReferencedEnvelope = null
  var w: Int = 0
  var h: Int = 0
  var transform: AffineTransform = null
  var curRange: ARange = null
  var result = HashBasedTable.create[Int,Int,Int]()
  var srcIter: SortedKeyValueIterator[Key, Value] = null
  var projectedSFT: SimpleFeatureType = null
  var featureBuilder: SimpleFeatureBuilder = null

  override def init(source: SortedKeyValueIterator[Key, Value],
                    options: ju.Map[String, String],
                    env: IteratorEnvironment): Unit = {
    super.init(source, options, env)
    bbox = JTS.toEnvelope(WKTUtils.read(options.get(DensityIterator.BBOX_KEY)))
    w = 600
    h = 600
    transform = RendererUtilities.worldToScreenTransform(bbox, new Rectangle(w, h))
    projectedSFT = DataUtilities.createType(simpleFeatureType.getTypeName, "encodedraster:String,geom:Point:srid=4326")
    featureBuilder = new SimpleFeatureBuilder(projectedSFT)
  }

  override def next() = {
    var outOfRange = false
    do {
      super.next()
      if (super.hasTop && !outOfRange) {
        if (!curRange.afterEndKey(topKey.followingKey(PartialKey.ROW))) {
          val geom = curFeature.getDefaultGeometry.asInstanceOf[Point]
          val coord = geom.getCoordinate
          val pt = transform.transform(coord.toPoint2D, null)
          val x = pt.getX.toInt
          val y = pt.getY.toInt
          val cur = Option(result.get(x, y)).getOrElse(0)
          result.put(x, y, cur + 1)
        } else {
          outOfRange = true
        }
      }
    } while(nextKey != null && !outOfRange)
  }

  override def seek(range: ARange,
                    columnFamilies: ju.Collection[ByteSequence],
                    inclusive: Boolean): Unit = {
    curRange = range
    super.seek(range, columnFamilies, inclusive)
  }

  override def getTopValue = {
    featureBuilder.reset()
    featureBuilder.add(DensityIterator.encodeSparseMatrix(result))
    featureBuilder.add(curFeature.getDefaultGeometry)
    val feature = featureBuilder.buildFeature(Random.nextString(6))
    result.clear()

    new Value(DataUtilities.encodeFeature(feature).getBytes)
  }
}

object DensityIterator {
  val BBOX_KEY = "geomesa.density.bbox"

  def setBbox(iterSettings: IteratorSetting, poly: Polygon): Unit = {
    iterSettings.addOption(BBOX_KEY, WKTUtils.write(poly))
  }

  def encodeSparseMatrix(sparseMatrix: HashBasedTable[Int, Int, Int]): String = {
    val baos = new ByteArrayOutputStream()
    val os = new DataOutputStream(baos)
    sparseMatrix.rowMap().foreach { case (rowIdx, cols) =>
      os.writeInt(rowIdx)
      os.writeInt(cols.size())
      cols.foreach { case (colIdx, v) =>
        os.writeInt(colIdx)
        os.writeInt(v)
      }
    }
    os.flush()
    Base64.encodeBase64URLSafeString(baos.toByteArray)
  }

  def decodeSparseMatrix(encoded: String): HashBasedTable[Int, Int, Int] = {
    val bytes = Base64.decodeBase64(encoded)
    val is = new DataInputStream(new ByteArrayInputStream(bytes))
    val table = HashBasedTable.create[Int, Int, Int]()
    while(is.available() > 0) {
      val rowIdx = is.readInt()
      val colCount = is.readInt()
      (0 until colCount).foreach { _ =>
        val colIdx = is.readInt()
        val v = is.readInt()
        table.put(rowIdx, colIdx, v)
      }
    }
    table
  }

}
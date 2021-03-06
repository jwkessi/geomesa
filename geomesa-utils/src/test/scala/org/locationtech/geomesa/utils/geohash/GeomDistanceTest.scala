/*
 * Copyright 2013 Commonwealth Computer Research, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.locationtech.geomesa.utils.geohash

import com.vividsolutions.jts.geom.Point
import org.junit.runner.RunWith
import org.locationtech.geomesa.utils.text.WKTUtils
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class GeomDistanceTest extends Specification with GeomDistance {

  import Distance._

  "1 kilometer" should {
    "equate to 1000 meters" in {
      val a : Double = 1 kilometer
      val b : Double = 1000 meter

      a must beCloseTo(b, 1e-6)
    }
  }


  "Reference test Flinders Peak, AUS" should {
    "be 54972.271 meters from Buninyong, AUS" in {
      val ptFlindersPeak = WKTUtils.read("POINT(144.4248678889 -37.9510334167)").asInstanceOf[Point]
      val ptBuninyong = WKTUtils.read("POINT(143.9264955278 -37.6528211389)").asInstanceOf[Point]
      VincentyModel.getDistanceBetweenTwoPoints(ptFlindersPeak, ptBuninyong).getDistanceInMeters must beCloseTo(54972.271, 0.01)
    }
  }

  "CCRi local" should {
    "be 433.5 meters from Albemarle High School" in {
      val ptCCRI = WKTUtils.read("POINT(-78.4953560 38.0752150)").asInstanceOf[Point]
      val ptAHS = WKTUtils.read("POINT(-78.5002901 38.0754152)").asInstanceOf[Point]
      VincentyModel.getDistanceBetweenTwoPoints(ptCCRI, ptAHS).getDistanceInMeters must beCloseTo(433.5, 0.01)
    }
  }

  "CCRi remote" should {
    "be 11422838.3 meters from Mount Potts, NZ" in {
      val ptCCRI = WKTUtils.read("POINT(-78.4953560 38.0752150)").asInstanceOf[Point]
      val ptEdoras = WKTUtils.read("POINT(170.919998 -43.498299)").asInstanceOf[Point]
      VincentyModel.getDistanceBetweenTwoPoints(ptCCRI, ptEdoras).getDistanceInMeters must beCloseTo(14301344.142, 0.01)
    }
  }

  "New Mexico" should {
    "be 2300000 meters from CCRI" in {
      val ptCCRI = WKTUtils.read("POINT(-78.4953560 38.0752150)").asInstanceOf[Point]
      val ptNM = VincentyModel.moveWithBearingAndDistance(ptCCRI, -90.0, 2300000)

      ptNM.getX must beCloseTo(-104.060, 0.01)
      ptNM.getY must beCloseTo(35.236, 0.01)

      val dist = VincentyModel.getDistanceBetweenTwoPoints(ptCCRI, ptNM).getDistanceInMeters

      dist must beCloseTo(2300000, 0.1)
    }
  }
}

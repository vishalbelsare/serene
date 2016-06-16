/**
 * Copyright (C) 2015-2016 Data61, Commonwealth Scientific and Industrial Research Organisation (CSIRO).
 * See the LICENCE.txt file distributed with this work for additional
 * information regarding copyright ownership.
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
package au.csiro.data61.matcher.types

import java.nio.file.{Paths, Path}

import au.csiro.data61.matcher.types.ModelTypes.StatusSerializer
import org.json4s._

/**
 * An object that has a property id which is of type key.
 * This is used as the base type for the id-able case classes
 * such as DataSet and Model
 *
 * @tparam Key The id type, nominally an Int or Long
 */
trait Identifiable[Key] {
  def id: Key
}

/**
 * Serializer for the Java.io.Path object
 */
case object PathSerializer extends CustomSerializer[Path](format => ( {
  case jv: JValue =>
    implicit val formats = DefaultFormats
    val str = jv.extract[String]
    Paths.get(str)
}, {
  case path: Path =>
    JString(path.toString)
}))

/**
 * Holds the implicit matcher objects for the Json4s Serializers.
 *
 * This should be mixed in to the object in order to use.
 */
trait MatcherJsonFormats {

  implicit def json4sFormats: Formats =
    org.json4s.DefaultFormats ++
      org.json4s.ext.JodaTimeSerializers.all +
      LogicalTypeSerializer +
      PathSerializer +
      SamplingStrategySerializer +
      ModelTypeSerializer +
      FeatureSerializer +
      StatusSerializer
}
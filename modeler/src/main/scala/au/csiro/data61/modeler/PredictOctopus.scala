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
package au.csiro.data61.modeler

import au.csiro.data61.modeler.karma.{KarmaBuildAlignmentGraph, KarmaParams, KarmaSuggestModel}
import au.csiro.data61.types.ColumnTypes._
import au.csiro.data61.types.Exceptions.ModelerException
import au.csiro.data61.types.SsdTypes._
import au.csiro.data61.types.{ColumnPrediction, DataSetPrediction, Ssd, SsdPrediction}
import com.typesafe.scalalogging.LazyLogging

/**
  * As input we give a ssd, a list of predicted semantic types for this ssd and directory with alignment graph to be used.
  * As output we get a list of ssd with associated scores.
  */
object PredictOctopus extends LazyLogging {

  /**
    * Generate a ranked list of semantic models based on the provided alignmentGraph and predictions of
    * semantic types.
    * @param octopus
    * @param alignmentDir
    * @param ontologies
    * @param ssd
    * @param dsPredictions
    * @param attrToColMap
    * @param numSemanticTypes
    * @return
    */
  def predict(octopus: Octopus
              , alignmentDir: String
              , ontologies: List[String]
              , ssd: Ssd
              , dsPredictions: Option[DataSetPrediction]
              , attrToColMap: Map[AttrID,ColumnID]
              , numSemanticTypes: Int): Option[SsdPrediction] = {
    logger.info("Semantic Modeler initializes prediction...")

    val karmaWrapper = KarmaParams(alignmentDir = alignmentDir,
      ontologies = ontologies,
      ModelerConfig.makeModelingProps(octopus.modelingProps)
    )

    val problematicDsPreds = dsPredictions match {
      case Some(obj: DataSetPrediction) =>
        obj.predictions.values
          .filter(_.confidence == 0)
      case None => List()
    }
    logger.warn(s"Semantic Modeler got problematic ds predictions: ${problematicDsPreds.size}")

    // TODO: filter unknown class labels!
    val convertedDsPreds: Option[DataSetPrediction] = dsPredictions match {

      case Some(obj: DataSetPrediction) =>
        logger.debug(s"Semantic Modeler got ${obj.predictions.size} dataset predictions.")
        val filteredPreds: Map[String, ColumnPrediction] =
          obj.predictions
            .filter(_._2.confidence > 0)
        logger.info(s"Semantic Modeler will use ${filteredPreds.size} ds predictions.")
        Some(DataSetPrediction(obj.modelID, obj.dataSetID, filteredPreds))

      case None => None
    }

    val suggestions = KarmaSuggestModel(karmaWrapper).suggestModels(ssd
      , ontologies
      , convertedDsPreds
      , octopus.semanticTypeMap
      , attrToColMap: Map[AttrID,ColumnID]
      , numSemanticTypes)

    karmaWrapper.deleteKarma() // deleting karma home directory

    suggestions
  }
}
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
package au.csiro.data61.core

import java.io.FileInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import au.csiro.data61.core.api.OctopusRequest
import au.csiro.data61.core.drivers.{MatcherInterface, OctopusInterface}
import au.csiro.data61.core.storage._
import au.csiro.data61.modeler.ModelerConfig
import au.csiro.data61.modeler.karma.{KarmaBuildAlignmentGraph, KarmaParams, KarmaSuggestModel}
import au.csiro.data61.types.ModelType.RANDOM_FOREST
import au.csiro.data61.types.ModelTypes.Model
import au.csiro.data61.types.SSDTypes.Owl
import au.csiro.data61.types.SamplingStrategy.NO_RESAMPLING
import au.csiro.data61.types._
import com.typesafe.scalalogging.LazyLogging
import org.apache.commons.io.FileUtils
import org.joda.time.DateTime
import org.json4s.Extraction
import org.json4s.jackson.JsonMethods._
import org.scalatest.{BeforeAndAfterEach, FunSuite}

import scala.concurrent._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._


import scala.language.postfixOps
import scala.util.{Failure, Success, Try}



/**
  * Tests for the OctopusInterface methods
  */

class OctopusSpec extends FunSuite with JsonFormats with BeforeAndAfterEach with LazyLogging{

  override def afterEach(): Unit = {
    SSDStorage.removeAll()
//    OctopusStorage.removeAll()
    DatasetStorage.removeAll()
    OwlStorage.removeAll()
//    ModelStorage.removeAll()
  }

  override def beforeEach(): Unit = {
    copySampleDatasets() // copy csv files for getCities and businessInfo
    SSDStorage.add(0, businessSSD) // add businessInfo ssd
    OwlStorage.add(1, exampleOwl)  // add sample ontology
  }

  val ssdDir = getClass.getResource("/ssd").getPath

  def readSSD(ssdPath: String): SemanticSourceDesc = {
    Try {
      val stream = new FileInputStream(Paths.get(ssdPath).toFile)
      parse(stream).extract[SemanticSourceDesc]
    } match {
      case Success(ssd) =>
        ssd
      case Failure(err) =>
        fail(err.getMessage)
    }
  }

  val exampleOntolPath = Paths.get(ssdDir,"dataintegration_report_ontology.owl")
  val exampleOwl = Owl(id =1, path = exampleOntolPath,
    description = "sample", dateCreated = DateTime.now, dateModified = DateTime.now)

  val partialSSD: SemanticSourceDesc = readSSD(Paths.get(ssdDir,"partial_model.ssd").toString)
  val veryPartialSSD: SemanticSourceDesc = readSSD(Paths.get(ssdDir,"partial_model2.ssd").toString)
  val emptyCitiesSSD: SemanticSourceDesc = readSSD(Paths.get(ssdDir,"empty_getCities.ssd").toString)
  val emptySSD: SemanticSourceDesc = readSSD(Paths.get(ssdDir,"empty_model.ssd").toString)
  val businessSSD: SemanticSourceDesc = readSSD(Paths.get(ssdDir,"businessInfo.ssd").toString)

  val defaultFeatures = FeaturesConfig(
    activeFeatures = Set("num-unique-vals", "prop-unique-vals", "prop-missing-vals",
    "ratio-alpha-chars", "prop-numerical-chars",
    "prop-whitespace-chars", "prop-entries-with-at-sign",
    "prop-entries-with-hyphen", "prop-entries-with-paren",
    "prop-entries-with-currency-symbol", "mean-commas-per-entry",
    "mean-forward-slashes-per-entry",
    "prop-range-format", "is-discrete", "entropy-for-discrete-values"),
    activeGroupFeatures = Set.empty[String],
    featureExtractorParams = Map()
  )

  val defaultOctopusRequest = OctopusRequest(
    description = Some("default octopus"),
    modelType = None,
    features = Some(defaultFeatures),
    resamplingStrategy = Some(NO_RESAMPLING),
    numBags = None,
    bagSize = None,
    ontologies = None,
    ssds = Some(List(0)),
    modelingProps = None)

  val blankOctopusRequest = OctopusRequest(None, None, None, None, None, None, None, None, None)

  val helperDir = getClass.getResource("/helper").getPath
  val sampleDsDir = getClass.getResource("/sample.datasets").getPath
  val datasetMap = Map("businessInfo" -> 767956483, "getCities" -> 696167703)

  def copySampleDatasets(): Unit = {
    // copy sample dataset to Config.DatasetStorageDir
    if (!Paths.get(Serene.config.datasetStorageDir).toFile.exists) { // create dataset storage dir
      Paths.get(Serene.config.datasetStorageDir).toFile.mkdirs}
    val dsDir = Paths.get(sampleDsDir).toFile // directory to copy from
    FileUtils.copyDirectory(dsDir,                    // copy sample dataset
      Paths.get(Serene.config.datasetStorageDir).toFile)
  }


  test("Creating octopus for businessInfo") {
    copySampleDatasets() // copy csv files for getCities and businessInfo
    SSDStorage.add(0, businessSSD) // add businessInfo ssd

    // create default octopus
    val octopus = OctopusInterface.createOctopus(defaultOctopusRequest)
    // lobster should automatically be created
    val lobster: Model = ModelStorage.get(octopus.lobsterID).get

    assert(octopus.ssds === List(0))
    assert(octopus.ontologies === List(1))
    assert(octopus.state.status === ModelTypes.Status.UNTRAINED)

    assert(lobster.modelType === RANDOM_FOREST)
    assert(lobster.resamplingStrategy === NO_RESAMPLING)
    assert(lobster.classes.size === 4)
    assert(lobster.labelData.size === 4)
    assert(lobster.labelData === Map(643243447 -> "Organization---name",
      1534291035 -> "Person---name",
      843054462 -> "City---name",
      1138681944 -> "State---name")
    )
  }

  test("Constructing initial alignment graph for businessInfo") {
    copySampleDatasets() // copy csv files for getCities and businessInfo
    SSDStorage.add(0, businessSSD) // add businessInfo ssd

    // create default octopus
    val octopus = OctopusInterface.createOctopus(defaultOctopusRequest)

    val storedOctopus = OctopusStorage.get(octopus.id)
    println(storedOctopus)

    val state = OctopusInterface.trainOctopus(octopus.id)

    // octopus becomes busy
    assert(state.get.status === ModelTypes.Status.BUSY)
    Thread.sleep(1000)
    // lobster becomes busy
    assert(ModelStorage.get(octopus.lobsterID).get.state.status === ModelTypes.Status.BUSY)

    Thread.sleep(12000)

    assert(state.get.status === ModelTypes.Status.COMPLETE)
  }

  // tests for createOctopus
  // tests for trainOctopus
  // tests for predictOctopus


//  test("Recommendation for empty businessInfo.csv succeeds"){
//    SSDStorage.add(businessSSD.id, businessSSD)
//    // first, we build the Alignment Graph = training step
//    val karmaTrain = KarmaBuildAlignmentGraph(karmaWrapper)
//    // our alignment
//    var alignment = karmaTrain.alignment
//    assert(alignment.getGraph.vertexSet.size === 0)
//    assert(alignment.getGraph.edgeSet.size === 0)
//
//    alignment = karmaTrain.constructInitialAlignment(knownSSDs)
//    assert(alignment.getGraph.vertexSet.size === 8)
//    assert(alignment.getGraph.edgeSet.size === 7)
//
//    val newSSD = Try {
//      val stream = new FileInputStream(Paths.get(emptySSD).toFile)
//      parse(stream).extract[SemanticSourceDesc]
//    } match {
//      case Success(ssd) =>
//        ssd
//      case Failure(err) =>
//        fail(err.getMessage)
//    }
//    // check uploaded ontologies....
//    assert(karmaWrapper.ontologies.size === 1)
//    assert(karmaWrapper.karmaWorkspace.getOntologyManager.getClasses.size === 7)
//    assert(karmaWrapper.karmaWorkspace.getOntologyManager.getDataProperties.size === 9)
//    assert(karmaWrapper.karmaWorkspace.getOntologyManager.getObjectProperties.size === 12)
//
//    // now, we run prediction for the new SSD
//    karmaWrapper = KarmaParams(alignmentDir, List(exampleOntol), None)
//    val karmaPredict = KarmaSuggestModel(karmaWrapper)
//    val recommends = karmaPredict
//      .suggestModels(newSSD, List(exampleOntol), getBusinessDataSetPredictions, businessSemanticTypeMap, businessAttrToColMap2)
//
//    recommends match {
//      case Some(ssdPred: SSDPrediction) =>
//        assert(ssdPred.suggestions.size === 1)
//        val recSemanticModel = ssdPred.suggestions(0)._1
//        val scores = ssdPred.suggestions(0)._2
//
//        assert(recSemanticModel.isConsistent) // Karma should return a consistent and complete semantic model
//        assert(recSemanticModel.isComplete)
//        assert(scores.nodeCoherence === 1)
//        assert(scores.nodeConfidence === 1) // confidence is one since Karma standardizes scores so that sum=1
//        assert(scores.linkCost === 7)
//        assert(scores.nodeCoverage === 1)
//        assert(recSemanticModel.mappings.isDefined)
//        assert(recSemanticModel.mappings.forall(_.mappings.size==4))
//      case _ =>
//        fail("Wrong! There should be some prediction!")
//    }
//  }
//
//  test("Recommendation for partial businessInfo.csv with no matcher predictions succeeds"){
//    SSDStorage.add(businessSSD.id, businessSSD)
//    logger.info("================================================================")
//    val karmaTrain = KarmaBuildAlignmentGraph(karmaWrapper)
//    // our alignment
//    logger.info("================================================================")
//    var alignment = karmaTrain.alignment
//    assert(alignment.getGraph.vertexSet.size === 0)
//    assert(alignment.getGraph.edgeSet.size === 0)
//
//    logger.info("================================================================")
//    alignment = karmaTrain.constructInitialAlignment(knownSSDs)
//    assert(alignment.getGraph.vertexSet.size === 8)
//    assert(alignment.getGraph.edgeSet.size === 7)
//
//    logger.info("================================================================")
//    val newSSD = Try {
//      val stream = new FileInputStream(Paths.get(partialSSD).toFile)
//      parse(stream).extract[SemanticSourceDesc]
//    } match {
//      case Success(ssd) =>
//        ssd
//      case Failure(err) =>
//        fail(err.getMessage)
//    }
//    // check uploaded ontologies....
//    assert(karmaWrapper.ontologies.size === 1)
//    assert(karmaWrapper.karmaWorkspace.getOntologyManager.getClasses.size === 7)
//    assert(karmaWrapper.karmaWorkspace.getOntologyManager.getDataProperties.size === 9)
//    assert(karmaWrapper.karmaWorkspace.getOntologyManager.getObjectProperties.size === 12)
//
//    logger.info("================================================================")
//    karmaWrapper = KarmaParams(alignmentDir, List(exampleOntol), None)
//    val karmaPredict = KarmaSuggestModel(karmaWrapper)
//    logger.info("================================================================")
//    val recommends = karmaPredict
//      .suggestModels(newSSD, List(exampleOntol), None, businessSemanticTypeMap, businessAttrToColMap2)
//    recommends match {
//      case Some(ssdPred: SSDPrediction) =>
//        assert(ssdPred.suggestions.size === 1)
//        val recSemanticModel = ssdPred.suggestions(0)._1
//        val scores = ssdPred.suggestions(0)._2
//
//        val str = compact(Extraction.decompose(recSemanticModel))
//        val outputPath = Paths.get(ModelerConfig.KarmaDir, s"recommended_ssd.json")
//        // write the object to the file system
//        logger.debug("HERE")
//        Files.write(
//          outputPath,
//          str.getBytes(StandardCharsets.UTF_8)
//        )
//
//        assert(recSemanticModel.isConsistent)
//        assert(recSemanticModel.isComplete)
//        assert(scores.nodeCoherence === 1) // all column node mappings are user provided
//        assert(scores.nodeConfidence === 1)
//        assert(scores.linkCost === 7)
//        assert(scores.nodeCoverage === 1)
//        assert(recSemanticModel.mappings.isDefined)
//        assert(recSemanticModel.mappings.forall(_.mappings.size==4))
//      //        assert(recSemanticModel.mappings === Some(SSDMapping(Map(4 -> 7, 5 -> 5, 6 -> 4, 7 -> 6)))) // unfortunately, mappings are not fixed
//      case _ =>
//        fail("Wrong!")
//    }
//  }
//
//  test("Recommendation for partially specified businessInfo.csv with matcher predictions succeeds"){
//    SSDStorage.add(businessSSD.id, businessSSD)
//    logger.info("================================================================")
//    val karmaTrain = KarmaBuildAlignmentGraph(karmaWrapper)
//    // our alignment
//    logger.info("================================================================")
//    var alignment = karmaTrain.alignment
//    assert(alignment.getGraph.vertexSet.size === 0)
//    assert(alignment.getGraph.edgeSet.size === 0)
//
//    logger.info("================================================================")
//    alignment = karmaTrain.constructInitialAlignment(knownSSDs)
//    assert(alignment.getGraph.vertexSet.size === 8)
//    assert(alignment.getGraph.edgeSet.size === 7)
//
//    logger.info("================================================================")
//    val newSSD = Try {
//      val stream = new FileInputStream(Paths.get(veryPartialSSD).toFile)
//      parse(stream).extract[SemanticSourceDesc]
//    } match {
//      case Success(ssd) =>
//        ssd
//      case Failure(err) =>
//        fail(err.getMessage)
//    }
//    // check uploaded ontologies....
//    assert(karmaWrapper.ontologies.size === 1)
//    assert(karmaWrapper.karmaWorkspace.getOntologyManager.getClasses.size === 7)
//    assert(karmaWrapper.karmaWorkspace.getOntologyManager.getDataProperties.size === 9)
//    assert(karmaWrapper.karmaWorkspace.getOntologyManager.getObjectProperties.size === 12)
//
//    logger.info("================================================================")
//    karmaWrapper = KarmaParams(alignmentDir, List(exampleOntol), None)
//    val karmaPredict = KarmaSuggestModel(karmaWrapper)
//    logger.info("================================================================")
//    val recommends = karmaPredict
//      .suggestModels(newSSD, List(exampleOntol), getBusinessDataSetPredictions, businessSemanticTypeMap, businessAttrToColMap2)
//    recommends match {
//      case Some(ssdPred: SSDPrediction) =>
//        assert(ssdPred.suggestions.size === 1)
//        val recSemanticModel = ssdPred.suggestions(0)._1
//        val scores = ssdPred.suggestions(0)._2
//
//        val str = compact(Extraction.decompose(recSemanticModel))
//        val outputPath = Paths.get(ModelerConfig.KarmaDir, s"recommended_ssd.json")
//        // write the object to the file system
//        Files.write(
//          outputPath,
//          str.getBytes(StandardCharsets.UTF_8)
//        )
//
//        assert(recSemanticModel.isConsistent)
//        assert(recSemanticModel.isComplete)
//        assert(scores.nodeCoherence === 1) // all column node mappings are user provided
//        assert(scores.nodeConfidence === 1)
//        assert(scores.linkCost === 7)
//        assert(scores.nodeCoverage === 1)
//        assert(recSemanticModel.mappings.isDefined)
//        assert(recSemanticModel.mappings.forall(_.mappings.size==4))
//      //assert(recSemanticModel.mappings === Some(SSDMapping(Map(4 -> 7, 5 -> 5, 6 -> 4, 7 -> 6)))) // unfortunately, mappings are not fixed
//      case _ =>
//        fail("Wrong!")
//    }
//  }
//
//  test("Recommendation for empty getCities.csv succeeds"){
//    SSDStorage.add(businessSSD.id, businessSSD)
//    // first, we build the Alignment Graph = training step
//    val karmaTrain = KarmaBuildAlignmentGraph(karmaWrapper)
//    // our alignment
//    var alignment = karmaTrain.alignment
//    assert(alignment.getGraph.vertexSet.size === 0)
//    assert(alignment.getGraph.edgeSet.size === 0)
//
//    alignment = karmaTrain.constructInitialAlignment(knownSSDs)
//    assert(alignment.getGraph.vertexSet.size === 8)
//    assert(alignment.getGraph.edgeSet.size === 7)
//
//    val newSSD = Try {
//      val stream = new FileInputStream(Paths.get(emptyCitiesSSD).toFile)
//      parse(stream).extract[SemanticSourceDesc]
//    } match {
//      case Success(ssd) =>
//        ssd
//      case Failure(err) =>
//        fail(err.getMessage)
//    }
//    // check uploaded ontologies....
//    assert(karmaWrapper.ontologies.size === 1)
//    assert(karmaWrapper.karmaWorkspace.getOntologyManager.getClasses.size === 7)
//    assert(karmaWrapper.karmaWorkspace.getOntologyManager.getDataProperties.size === 9)
//    assert(karmaWrapper.karmaWorkspace.getOntologyManager.getObjectProperties.size === 12)
//
//    // now, we run prediction for the new SSD
//    karmaWrapper = KarmaParams(alignmentDir, List(exampleOntol), None)
//    val karmaPredict = KarmaSuggestModel(karmaWrapper)
//    val recommends = karmaPredict
//      .suggestModels(newSSD, List(exampleOntol), getCitiesDataSetPredictions, Map(), citiesAttrToColMap)
//
//    recommends match {
//      case Some(ssdPred: SSDPrediction) =>
//        assert(ssdPred.suggestions.size === 4)
//        assert(ssdPred.suggestions.forall(_._1.isComplete)) // Karma should return a consistent and complete semantic model
//        assert(ssdPred.suggestions.forall(_._1.isConsistent))
//        assert(ssdPred.suggestions.forall(_._1.mappings.isDefined))
//        assert(ssdPred.suggestions.forall(_._1.mappings.forall(_.mappings.size==2)))
//        assert(ssdPred.suggestions.forall(_._2.nodeConfidence == 0.5))
//        assert(ssdPred.suggestions.forall(_._2.nodeCoherence == 1))
//        assert(ssdPred.suggestions.forall(_._2.nodeCoverage == 1))
//
//        ssdPred.suggestions.forall(_._1.mappings.isDefined)
//
//        val recSemanticModel = ssdPred.suggestions(0)._1
//        val scores = ssdPred.suggestions(0)._2
//        assert(scores.linkCost === 4)
//
//      case _ =>
//        fail("Wrong! There should be some prediction!")
//    }
//  }

}

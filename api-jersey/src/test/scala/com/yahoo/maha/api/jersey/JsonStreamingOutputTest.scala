package com.yahoo.maha.api.jersey

import java.io.OutputStream
import java.util.Date

import com.yahoo.maha.api.jersey.example.ExampleMahaService
import com.yahoo.maha.api.jersey.example.ExampleSchema.StudentSchema
import com.yahoo.maha.core.bucketing.{BucketParams, UserInfo}
import com.yahoo.maha.core.query._
import com.yahoo.maha.core.request.ReportingRequest
import com.yahoo.maha.core.{Engine, OracleEngine, RequestModelResult}
import com.yahoo.maha.service.curators.{DrilldownCurator, NoConfig, CuratorResult, DefaultCurator}
import com.yahoo.maha.service.utils.MahaRequestLogHelper
import com.yahoo.maha.service.{MahaRequestContext, RequestResult}
import org.scalatest.FunSuite

import scala.util.Try

/**
 * Created by pranavbhole on 06/04/18.
 */
class JsonStreamingOutputTest extends FunSuite {

  val jsonRequest = s"""{
                          "cube": "student_performance",
                          "selectFields": [
                            {"field": "Student ID"},
                            {"field": "Class ID"},
                            {"field": "Section ID"},
                            {"field": "Total Marks"}
                          ],
                          "filterExpressions": [
                            {"field": "Day", "operator": "between", "from": "${ExampleMahaService.yesterday}", "to": "${ExampleMahaService.today}"},
                            {"field": "Student ID", "operator": "=", "value": "213"}
                          ],
                          "includeRowCount" : true
                        }"""

  val reportingRequest = ReportingRequest.deserializeSync(jsonRequest.getBytes, StudentSchema).toOption.get

  val (query, queryChain)  = {
    val mahaService = ExampleMahaService.getMahaService("test")
    val mahaServiceConfig = mahaService.getMahaServiceConfig
    val registry = mahaServiceConfig.registry.get(ExampleMahaService.REGISTRY_NAME).get

    val bucketParams = BucketParams(UserInfo("uid", isInternal = true))

    val mahaRequestContext = MahaRequestContext(ExampleMahaService.REGISTRY_NAME,
      bucketParams,
      reportingRequest,
      jsonRequest.getBytes,
      Map.empty, "rid", "uid")

    val requestModel = mahaService.generateRequestModel(ExampleMahaService.REGISTRY_NAME, reportingRequest, BucketParams(UserInfo("test", false)), MahaRequestLogHelper(mahaRequestContext, mahaService.mahaRequestLogWriter)).toOption.get
    val factory = registry.queryPipelineFactory.from(requestModel.model, QueryAttributes.empty)
    val queryChain = factory.get.queryChain
    (queryChain.drivingQuery, queryChain)
  }

  val timeStampString = new Date().toString

  class StringStream extends OutputStream {
    val stringBuilder = new StringBuilder()
    override def write(b: Int): Unit = {
      stringBuilder.append(b.toChar)
    }
    override def toString() : String = stringBuilder.toString()
  }

  case class TestOracleIngestionTimeUpdater(engine: Engine, source: String) extends IngestionTimeUpdater {
    override def getIngestionTime(dataSource: String): Option[String] = {
      Some(timeStampString)
    }
  }

  class TestCurator extends DrilldownCurator {
    override val name = "TestCurator"
    override val isSingleton = false
  }

  test("Test JsonStreamingOutput with DefaultCurator, totalRow Option, empty curator result") {

    val rowList = CompleteRowList(query)

    val row = rowList.newRow
    row.addValue("Student ID", 123)
    row.addValue("Class ID", 234)
    row.addValue("Section ID", 345)
    row.addValue("Total Marks", 99)
    rowList.addRow(row)

    val queryPipelineResult = QueryPipelineResult(queryChain, rowList, QueryAttributes.empty)
    val requestResult = Try(RequestResult(queryPipelineResult, Some(1)))
    val requestModelResult = RequestModelResult(query.queryContext.requestModel, None)
    val defaultCurator = DefaultCurator()
    val curatorResult = CuratorResult(defaultCurator, NoConfig, requestResult, requestModelResult)

    val curatorResults= IndexedSeq(curatorResult)

    val jsonStreamingOutput = JsonStreamingOutput(curatorResults, Map(OracleEngine-> TestOracleIngestionTimeUpdater(OracleEngine, "testSource")))

    val stringStream =  new StringStream()

    jsonStreamingOutput.write(stringStream)
    val result = stringStream.toString()
    println(result)
    stringStream.close()
    assert(result.equals(s"""{"header":{"lastIngestTime":"$timeStampString","source":"student_grade_sheet","cube":"student_performance","fields":[{"fieldName":"Student ID","fieldType":"DIM"},{"fieldName":"Class ID","fieldType":"DIM"},{"fieldName":"Section ID","fieldType":"DIM"},{"fieldName":"Total Marks","fieldType":"FACT"},{"fieldName":"ROW_COUNT","fieldType":"CONSTANT"}],"maxRows":200},"rows":[[123,234,345,99,1]],"curators":{}}"""))
  }

  test("Test JsonStreamingOutput with DefaultCurator and valid other curator result") {

    val rowList = CompleteRowList(query)

    val row = rowList.newRow
    row.addValue("Student ID", 123)
    row.addValue("Class ID", 234)
    row.addValue("Section ID", 345)
    row.addValue("Total Marks", 99)
    rowList.addRow(row)

    val queryPipelineResult = QueryPipelineResult(queryChain, rowList, QueryAttributes.empty)
    val requestResult = Try(RequestResult(queryPipelineResult, None))
    val requestModelResult = RequestModelResult(query.queryContext.requestModel, None)
    val defaultCurator = DefaultCurator()
    val curatorResult1 = CuratorResult(defaultCurator, NoConfig, requestResult, requestModelResult)

    val testCurator = new TestCurator()
    val curatorResult2 = CuratorResult(testCurator, NoConfig, requestResult, requestModelResult)


    val curatorResults= IndexedSeq(curatorResult1, curatorResult2)

    val jsonStreamingOutput = JsonStreamingOutput(curatorResults)

    val stringStream =  new StringStream()

    jsonStreamingOutput.write(stringStream)
    val result = stringStream.toString()
    println(result)
    stringStream.close()
    assert(result.equals(s"""{"header":{"cube":"student_performance","fields":[{"fieldName":"Student ID","fieldType":"DIM"},{"fieldName":"Class ID","fieldType":"DIM"},{"fieldName":"Section ID","fieldType":"DIM"},{"fieldName":"Total Marks","fieldType":"FACT"},{"fieldName":"ROW_COUNT","fieldType":"CONSTANT"}],"maxRows":200},"rows":[[123,234,345,99]],"curators":{"TestCurator":{"result":{"header":{"cube":"student_performance","fields":[{"fieldName":"Student ID","fieldType":"DIM"},{"fieldName":"Class ID","fieldType":"DIM"},{"fieldName":"Section ID","fieldType":"DIM"},{"fieldName":"Total Marks","fieldType":"FACT"},{"fieldName":"ROW_COUNT","fieldType":"CONSTANT"}],"maxRows":200},"rows":[[123,234,345,99]]}}}}""".stripMargin))
  }

}
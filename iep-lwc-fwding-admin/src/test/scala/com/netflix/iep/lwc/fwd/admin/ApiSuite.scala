/*
 * Copyright 2014-2019 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.iep.lwc.fwd.admin

import akka.http.scaladsl.model._
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.Sink
import com.netflix.atlas.akka.StreamOps.SourceQueue
import com.netflix.atlas.akka.RequestHandler
import com.netflix.atlas.akka.StreamOps
import com.netflix.atlas.eval.stream.Evaluator
import com.netflix.atlas.json.Json
import com.netflix.iep.lwc.fwd.cw.ExpressionId
import com.netflix.iep.lwc.fwd.cw.ForwardingExpression
import com.netflix.iep.lwc.fwd.cw.Report
import com.netflix.spectator.api.NoopRegistry
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.StrictLogging
import org.scalatest.FunSuite
import ExpressionDetails._
import akka.Done

import scala.concurrent.Future

class ApiSuite
    extends FunSuite
    with ScalatestRouteTest
    with CwForwardingTestConfig
    with StrictLogging {

  val config = ConfigFactory.load()

  val validations = new CwExprValidations(
    new ExprInterpreter(config),
    new Evaluator(config, new NoopRegistry(), system)
  )

  val markerService = new MarkerServiceTest()

  val purger = new Purger {
    override def purge(expressions: List[ExpressionId]): Future[Done] = Future(Done)
  }

  val routes = RequestHandler.standardOptions(
    new Api(
      new NoopRegistry,
      new SchemaValidation,
      validations,
      markerService,
      purger,
      new ExpressionDetailsDaoTestImpl,
      system
    ).routes
  )

  test("Valid config") {

    val config = makeConfigString()()

    val postRequest = HttpRequest(
      HttpMethods.POST,
      uri = "/api/v1/cw/check/foo_cluster_config",
      entity = HttpEntity(MediaTypes.`application/json`, config)
    )

    postRequest ~> routes ~> check {
      assert(response.status === StatusCodes.OK)
    }
  }

  test("Fail for an invalid config") {

    val config = makeConfigString()(
      atlasUri = """
                   | http://localhost/api/v1/graph?q=
                   |  nf.app,nq_android_api,:eq,
                   |  name,cgroup,:re,:and,
                   |  (,name,nf.asg,nf.account,),:by
                 """.stripMargin
    )

    val postRequest = HttpRequest(
      HttpMethods.POST,
      uri = "/api/v1/cw/check/foo_cluster_config",
      entity = HttpEntity(MediaTypes.`application/json`, config)
    )

    postRequest ~> routes ~> check {
      assert(response.status === StatusCodes.BadRequest)
      assert(
        entityAs[String]
          .contains(
            s"IllegalArgumentException: By default allowing only grouping by " +
            s"${validations.defaultGroupingKeys}"
          )
      )
    }
  }

  test("Queue report") {

    val reports = List(
      Report(
        1551820461000L,
        ExpressionId("c1", ForwardingExpression("", "", None, "")),
        None,
        None
      )
    )

    val postRequest = HttpRequest(
      HttpMethods.POST,
      uri = "/api/v1/cw/report",
      entity = HttpEntity(MediaTypes.`application/json`, Json.encode(reports))
    )

    postRequest ~> routes ~> check {
      assert(response.status === StatusCodes.OK)
      assert(markerService.result.result() === reports)
    }
  }

  test("Query purge eligible expressions") {
    val getRequest = HttpRequest(
      HttpMethods.GET,
      uri = s"/api/v1/cw/expr/purgeEligible?events=$NoDataFoundEvent,$NoScalingPolicyFoundEvent"
    )

    getRequest ~> routes ~> check {
      assert(response.status === StatusCodes.OK)
      assert(responseAs[String] === "[]")
    }
  }

  test("Purge given expressions") {
    val expressions = List(ExpressionId("", ForwardingExpression("", "", None, "")))

    val delRequest = HttpRequest(
      HttpMethods.DELETE,
      uri = "/api/v1/cw/expr/purge",
      entity = HttpEntity(MediaTypes.`application/json`, Json.encode(expressions))
    )

    delRequest ~> routes ~> check {
      assert(response.status === StatusCodes.OK)
    }
  }

}

class MarkerServiceTest(
  implicit val mat: ActorMaterializer
) extends MarkerService {

  var result = List.newBuilder[Report]

  override var queue: SourceQueue[Report] = StreamOps
    .blockingQueue[Report](new NoopRegistry(), "fwdingAdminCwReports", 1)
    .map(result += _)
    .toMat(Sink.ignore)(Keep.left)
    .run()

}

package gatling.simulations

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._

class DemoSimulation extends Simulation {
  val targetHost = System.getProperty("targetHost", "localhost")
  val targetPort = Integer.getInteger("targetPort", 7012)
  val baseURL = "http://" + targetHost + ":" + targetPort

  val httpConf = http
    .baseURL(baseURL)
    .warmUp(baseURL + "/playground")

  val scn = scenario("foo load test")
    .exec(http("Mark").get("/playground/server/admin/v1/routing/rules"))
  setUp(
    scn.inject(
      atOnceUsers(5)
    )
  ).protocols(httpConf)
}
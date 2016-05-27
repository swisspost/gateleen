package gatling.simulations

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._
import gatling.simulations._

class GateleenPerformanceTestSimulation extends Simulation {

  val targetHost = System.getProperty("targetHost", "localhost")
  val targetPort = Integer.getInteger("targetPort", 7012)
  val baseURL = "http://" + targetHost + ":" + targetPort
  val httpConf = http.baseURL(baseURL).warmUp(baseURL + "/playground")

  before {
    println("About to start performance tests on host " +targetHost+ " and port " + targetPort)
  }

  setUp(
    Scenarios.storageOperations.inject(rampUsers(500) over(30 seconds))
  ).protocols(httpConf)
}
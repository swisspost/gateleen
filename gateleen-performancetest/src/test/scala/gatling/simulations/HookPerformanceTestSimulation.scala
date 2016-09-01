package gatling.simulations

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._
import gatling.simulations._

class HookPerformanceTestSimulation extends Simulation {

  val targetHost = System.getProperty("targetHost", "localhost")
  val targetPort = Integer.getInteger("targetPort", 7012)
  val baseURL = "http://" + targetHost + ":" + targetPort
  val httpConf = http.baseURL(baseURL).warmUp(baseURL + "/playground")

  before {
    println("About to start hook performance tests on host " + baseURL)
  }

  setUp(
//    Scenarios.connectWebSockets.inject(rampUsers(5000) over(60 seconds))

    Scenarios.pushScenario.inject(rampUsers(1500) over(60 seconds)),
    Scenarios.putHookedResourceScenario.inject(nothingFor(70 seconds), atOnceUsers(1)),
    Scenarios.unregisterHooks.inject(nothingFor(75 seconds), rampUsers(1500) over(60 seconds))
  )
    .protocols(httpConf)
    .assertions(global.successfulRequests.percent.is(100))
}
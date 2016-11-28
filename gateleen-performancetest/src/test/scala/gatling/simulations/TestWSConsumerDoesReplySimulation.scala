package gatling.simulations

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._
import gatling.simulations._

class TestWSConsumerDoesReplySimulation extends Simulation {

  setUp(
    TestScenarios.registerHookConnectAndReply.inject(rampUsers(Constants.numberOfUsers) over(Constants.rampUpTime seconds)),
    TestScenarios.putHookedResourceScenario.inject(nothingFor(Constants.rampUpTime + 20 seconds), atOnceUsers(1))
  )
    .protocols(Constants.httpConf)
    .assertions(
      global.successfulRequests.percent.is(100)
    )
}
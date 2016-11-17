package gatling.simulations

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._
import gatling.simulations._

class HookWSConsumerDoesReplySimulation extends Simulation {

  before {
    println("About to start hook webSocket consumer does reply performance tests on host "
      + Constants.baseURL + " with " + Constants.numberOfUsers + " users over " + Constants.rampUpTime + " seconds")
  }

  setUp(
    Scenarios.registerHookConnectAndReply.inject(rampUsers(Constants.numberOfUsers) over(Constants.rampUpTime seconds)),
    Scenarios.putHookedResourceScenario.inject(nothingFor(Constants.rampUpTime + 10 seconds), atOnceUsers(1)),
    Scenarios.verifyResponsiveness.inject(nothingFor(Constants.rampUpTime + 20 seconds), rampUsers(20) over(20 seconds)),
    Scenarios.checkPushNotificationQueuesEmpty.inject(nothingFor(Constants.rampUpTime + 90 seconds), atOnceUsers(1)),
    Scenarios.unregisterHooks.inject(nothingFor(Constants.rampUpTime + 95 seconds), rampUsers(Constants.numberOfUsers) over(Constants.rampUpTime seconds))
  )
    .protocols(Constants.httpConf)
    .assertions(
      global.successfulRequests.percent.is(100)
    )
}
package gatling.simulations

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._
import gatling.simulations._

class HookNoWSConsumerTestSimulation extends Simulation {

  before {
    println("About to start hook no webSocket consumer performance tests on host "
      + Constants.baseURL + " with " + Constants.numberOfUsers + " users over " + Constants.rampUpTime + " seconds")
  }

  setUp(
    Scenarios.registerHookConnectAndDisconnectWS.inject(rampUsers(Constants.numberOfUsers) over(Constants.rampUpTime seconds)),
    Scenarios.putHookedResourceScenario.inject(nothingFor(Constants.rampUpTime + 10 seconds), atOnceUsers(1)),
    Scenarios.checkPushNotificationQueues.inject(nothingFor(Constants.rampUpTime + 15 seconds), atOnceUsers(1)),
    Scenarios.verifyResponsiveness.inject(nothingFor(Constants.rampUpTime + 17 seconds), rampUsers(20) over(20 seconds))
  )
    .protocols(Constants.httpConf)
    .assertions(
      details("verify_responsiveness").responseTime.percentile1.lessThan(Constants.responseTimeMs),
      global.successfulRequests.percent.is(100)
    )
}
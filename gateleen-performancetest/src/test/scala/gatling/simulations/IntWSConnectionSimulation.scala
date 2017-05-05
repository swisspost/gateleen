package gatling.simulations

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._
import gatling.simulations._

class IntWSConnectionSimulation extends Simulation {

  setUp(
    IntegrationScenarios.proxy_login.inject(atOnceUsers(1)),
    IntegrationScenarios.registerHookConnectAndRegisterWS.inject(nothingFor(3 seconds), constantUsersPerSec(Constants.numberOfUsers) during(Constants.rampUpTime seconds)),
    IntegrationScenarios.waitAndThenGetAResource.inject(nothingFor(Constants.rampUpTime + 3600 seconds), atOnceUsers(1))
  )
    .protocols(
      http
        .baseURL("https://" + Constants.targetHost)
        .disableWarmUp
        .disableFollowRedirect
    )
    .assertions(
      global.successfulRequests.percent.is(100)
    )
}
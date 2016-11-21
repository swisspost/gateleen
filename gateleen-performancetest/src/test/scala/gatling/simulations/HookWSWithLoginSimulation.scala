package gatling.simulations

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._
import gatling.simulations._

class HookWSWithLoginSimulation extends Simulation {

  setUp(
    IntegrationScenarios.proxy_login.inject(atOnceUsers(1)),

//    IntegrationScenarios.registerHooks.inject(nothingFor(3 seconds), constantUsersPerSec(Constants.numberOfUsers) during(Constants.rampUpTime seconds))

//    IntegrationScenarios.registerHookConnectAndReply.inject(nothingFor(3 seconds), rampUsers(Constants.numberOfUsers) over(Constants.rampUpTime seconds)),
//    IntegrationScenarios.registerHookConnectAndDontReply.inject(nothingFor(3 seconds), rampUsers(Constants.numberOfUsers) over(Constants.rampUpTime seconds)),


//    IntegrationScenarios.putHookedResourceScenario.inject(nothingFor(Constants.rampUpTime + 10 seconds), atOnceUsers(1))
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
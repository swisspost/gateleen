package gatling.simulations

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._
import gatling.simulations._

class TestWSConnectionSimulation extends Simulation {

  setUp(
    TestScenarios.registerHookConnectAndRegisterWS.inject(rampUsers(Constants.numberOfUsers) over(Constants.rampUpTime seconds)),
    TestScenarios.waitAndThenGetAResource.inject(nothingFor(Constants.rampUpTime + 3600 seconds), atOnceUsers(1))
  )
    .protocols(Constants.httpConf)
    .assertions(
      global.successfulRequests.percent.is(100)
    )
}
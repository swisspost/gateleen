package gatling.simulations

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._
import gatling.simulations._

class HookWSWithLoginSimulation extends Simulation {

  setUp(
    Scenarios.proxy_login.inject(atOnceUsers(1))
  )
    .protocols(
      http
      .baseURL("https://" + Constants.targetHost)
      .disableFollowRedirect
    )
    .assertions(
      global.successfulRequests.percent.is(100)
    )
}
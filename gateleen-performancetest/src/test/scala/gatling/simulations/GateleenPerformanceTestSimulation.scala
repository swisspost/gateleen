package gatling.simulations

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._
import gatling.simulations._

class GateleenPerformanceTestSimulation extends Simulation {

  before {
    println("About to start performance tests on host " + Constants.baseURL)
  }

  setUp(
    Scenarios.storageOperations.inject(constantUsersPerSec(26) during(60 seconds)),
    Scenarios.prepareExpandResources.inject(nothingFor(60 seconds), atOnceUsers(1)),
    Scenarios.regularExpand.inject(nothingFor(2 minutes), constantUsersPerSec(35) during(15 minutes)),
    Scenarios.storageExpand.inject(nothingFor(17 minutes), constantUsersPerSec(26) during(20 minutes)),
    Scenarios.enqueueRequests.inject(nothingFor(37 minutes), constantUsersPerSec(7) during(2 minutes)),
    Scenarios.checkQueuesEmpty.inject(nothingFor(43 minutes), atOnceUsers(1))
  )
    .protocols(Constants.httpConf)
    .assertions(
      global.successfulRequests.percent.is(100),
      global.responseTime.percentile1.lessThan(500),    // 75%
      global.responseTime.percentile2.lessThan(1000),   // 95%
      global.responseTime.percentile3.lessThan(3000),   // 97%
      global.responseTime.percentile4.lessThan(6000)    // 99%
    )

}
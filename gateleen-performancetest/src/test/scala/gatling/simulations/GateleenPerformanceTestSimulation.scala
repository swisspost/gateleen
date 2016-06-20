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
    println("About to start performance tests on host " + baseURL)
  }

  setUp(
    Scenarios.storageOperations.inject(constantUsersPerSec(12) during(60 seconds)),
    Scenarios.prepareExpandResources.inject(nothingFor(60 seconds), atOnceUsers(1)),
    Scenarios.regularExpand.inject(nothingFor(2 minutes), constantUsersPerSec(16) during(15 minutes)),
    Scenarios.storageExpand.inject(nothingFor(17 minutes), constantUsersPerSec(12) during(20 minutes)),
    Scenarios.enqueueRequests.inject(nothingFor(37 minutes), constantUsersPerSec(2) during(2 minutes)),
    Scenarios.checkQueuesEmpty.inject(nothingFor(43 minutes), atOnceUsers(1))
  )
    .protocols(httpConf)
    .assertions(
      global.successfulRequests.percent.is(100),
      global.responseTime.percentile1.lessThan(500),    // 75%
      global.responseTime.percentile2.lessThan(1000),   // 95%
      global.responseTime.percentile3.lessThan(3000),   // 97%
      global.responseTime.percentile4.lessThan(6000)    // 99%
    )

}
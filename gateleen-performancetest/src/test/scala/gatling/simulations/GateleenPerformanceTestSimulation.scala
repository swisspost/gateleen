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
    Scenarios.storageOperations.inject(constantUsersPerSec(10) during(5 seconds))

//    Scenarios.storageOperations.inject(constantUsersPerSec(35) during(60 seconds)),
//    Scenarios.prepareExpandResources.inject(nothingFor(60 seconds), atOnceUsers(1)),
//    Scenarios.regularExpand.inject(nothingFor(2 minutes), constantUsersPerSec(47) during(15 minutes)),
//    Scenarios.storageExpand.inject(nothingFor(17 minutes), constantUsersPerSec(35) during(20 minutes)),
//    Scenarios.enqueueRequests.inject(nothingFor(37 minutes), constantUsersPerSec(10) during(2 minutes)),
//    Scenarios.checkQueuesEmpty.inject(nothingFor(43 minutes), atOnceUsers(1))
  )
    .protocols(httpConf)
    .assertions(
      global.successfulRequests.percent.is(100),
      global.responseTime.max.lessThan(2)
//      global.responseTime.max.lessThan(10)
    )

}
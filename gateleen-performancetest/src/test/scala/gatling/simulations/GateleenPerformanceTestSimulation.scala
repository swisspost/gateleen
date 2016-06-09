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
    Scenarios.prepareExpandResources.inject(atOnceUsers(1)),
    Scenarios.regularExpand.inject(nothingFor(15 seconds), constantUsersPerSec(47) over(15 minutes)),
    Scenarios.storageExpand.inject(nothingFor(17 minutes), constantUsersPerSec(35) over(20 minutes))

//    Scenarios.storageOperations.inject(rampUsers(500) over(60 seconds)),
//    Scenarios.expandRequests.inject(
//      constantUsersPerSec(10) during(10 seconds),
//      constantUsersPerSec(20) during(10 seconds),
//      constantUsersPerSec(40) during(20 seconds),
//      constantUsersPerSec(10) during(20 seconds)
//    ),
//    Scenarios.enqueueRequests.inject(constantUsersPerSec(10) during(10 seconds)),
//    Scenarios.checkQueuesEmpty.inject(nothingFor(40 seconds), atOnceUsers(1))
  )
    .protocols(httpConf)
    .assertions(global.successfulRequests.percent.is(100))

}
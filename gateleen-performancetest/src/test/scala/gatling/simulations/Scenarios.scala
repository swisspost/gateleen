package gatling.simulations

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._
import gatling.simulations._

object Scenarios {

  val prepareExpandResources = scenario("Prepare expand resources")
    .exec(Tasks.writeExpandResourcesToStorage)

  val storageExpand = scenario("StorageExpand Requests")
    .exec(Tasks.readStorageExpand)

  val regularExpand = scenario("Regular Expand Requests")
    .exec(Tasks.readRegularExpand)

  val storageOperations = scenario("Storage operations write/read/delete")
    .exec(Tasks.writeToStorage)
    .exec(Tasks.readFromStorage)
    .exec(Tasks.deleteFromStorage)
    .exec(Tasks.readNotExistingResourceFromStorage)

  val expandRequests = scenario("Expand requests")
    .exec(Tasks.writeReadExpand)
    .exec(Tasks.writeReadStorageExpand)

  val enqueueRequests = scenario("Queueing: enqueue")
    .exec(Tasks.enqueue)

  val checkQueuesEmpty = scenario("Queueing: check queues are empty")
    .exec(Tasks.readQueues)
}
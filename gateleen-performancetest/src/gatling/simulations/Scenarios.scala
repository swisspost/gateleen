package gatling.simulations

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._
import gatling.simulations._

object Scenarios {
  val storageOperations = scenario("Storage operations write/read/delete")
    .exec(Tasks.writeToStorage)
    .exec(Tasks.readFromStorage)
    .exec(Tasks.deleteFromStorage)
    .exec(Tasks.readNotExistingResourceFromStorage)

  val expandRequests = scenario("Expand requests")
    .exec(Tasks.writeReadExpand)
    .exec(Tasks.writeReadStorageExpand)
}
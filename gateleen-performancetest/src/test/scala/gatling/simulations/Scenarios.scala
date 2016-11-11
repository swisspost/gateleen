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

  val enqueueRequests = scenario("Queueing: enqueue")
    .exec(Tasks.enqueue)

  val checkQueuesEmpty = scenario("Queueing: check queues are empty")
    .exec(Tasks.readQueues)

  val pushScenario = scenario("Push scenario requests")
    .exec(Tasks.registerHook)
    .exec(Tasks.connectWebSocket)
    .exec(Tasks.waitForWebSocketCall)

  val putHookedResourceScenario = scenario("PUT request to hooked resource")
    .exec(Tasks.putToHookedResource)

  val unregisterHooks = scenario("Unregister hooks")
    .exec(Tasks.unregisterHook)

  val connectWebSockets = scenario("Connect WebSockets")
    .exec(Tasks.openWebSocket)

  val registerHookConnectAndDisconnectWS = scenario("Register a hook, connect ws, register ws and disconnect ws")
    .exec(Tasks.registerHook)
    .exec(Tasks.openWebSocket)
    .exec(Tasks.registerWebSocket)
    .exec(Tasks.closeWebSocket)

  val checkPushNotificationQueues = scenario("check queues").exec(Tasks.checkPushNotificationQueues)

  val verifyResponsiveness = scenario("verify responsiveness").group("verify_responsiveness"){
    exec(Tasks.writeToStorage).exec(Tasks.readFromStorage)
  }
}
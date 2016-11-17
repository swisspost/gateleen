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
    .exec(HookTasks.registerHook)
    .exec(HookTasks.connectWebSocket)
    .exec(HookTasks.waitForWebSocketCall)

  val putHookedResourceScenario = scenario("PUT request to hooked resource")
    .exec(HookTasks.putToHookedResource)

  val unregisterHooks = scenario("Unregister hooks")
    .exec(HookTasks.unregisterHook)

  val connectWebSockets = scenario("Connect WebSockets")
    .exec(HookTasks.openWebSocket)

  val registerHookConnectAndDisconnectWS = scenario("Register a hook, connect ws, register ws and disconnect ws")
    .exec(HookTasks.registerHook)
    .exec(HookTasks.openWebSocket)
    .exec(HookTasks.registerWebSocket)
    .exec(HookTasks.closeWebSocket)

  val checkPushNotificationQueues = scenario("check queues").exec(HookTasks.checkPushNotificationQueues)

  val checkPushNotificationQueuesEmpty = scenario("check queues are empty").exec(HookTasks.checkPushNotificationQueuesEmpty)

  val verifyResponsiveness = scenario("verify responsiveness").group("verify_responsiveness"){
    exec(Tasks.writeToStorage).exec(Tasks.readFromStorage)
  }

  val registerHookConnectAndReply = scenario("Register a hook, connect ws, register ws and reply when message arrives")
    .exec(HookTasks.registerHook)
    .exec(HookTasks.openWebSocket)
    .exec(HookTasks.registerWebSocket)
    .exec(HookTasks.awaitMessageAndThenReply)

}
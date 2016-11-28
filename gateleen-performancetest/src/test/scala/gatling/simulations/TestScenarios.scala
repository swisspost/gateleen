package gatling.simulations

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._
import gatling.simulations._

object TestScenarios {

  val registerHookConnectAndRegisterWS = scenario("Register a hook, connect ws and register ws")
    .exec(HookTestTasks.registerHook)
    .exec(HookTestTasks.openWebSocket)
    .exec(HookTestTasks.registerWebSocket)

  val registerHookConnectAndReply = scenario("Register a hook, connect ws, register ws and reply when message arrives")
    .exec(HookTestTasks.registerHook)
    .exec(HookTestTasks.openWebSocket)
    .exec(HookTestTasks.registerWebSocket)
    .exec(HookTestTasks.awaitMessageAndThenReply)

  val waitAndThenGetAResource = scenario("Wait and then get a resource")
    .exec(HookTestTasks.getResource)

  val putHookedResourceScenario = scenario("PUT request to hooked resource")
    .exec(HookTestTasks.putToHookedResource)
}
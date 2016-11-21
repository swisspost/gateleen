package gatling.simulations

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._
import gatling.simulations._

object IntegrationScenarios {

  val proxy_login = scenario("proxy login")
    .exec(ProxyTasks.get_ticket)
    .exec(ProxyTasks.get_service_ticket)
    .exec(ProxyTasks.get_cookie)

  val registerHookConnectAndReply = scenario("Register a hook, connect ws, register ws and reply when message arrives")
    .exec(HookIntegrationTasks.registerHook)
    .exec(HookIntegrationTasks.openWebSocket)
    .exec(HookIntegrationTasks.registerWebSocket)
    .exec(HookIntegrationTasks.awaitMessageAndThenReply)

  val registerHookConnectAndDontReply = scenario("Register a hook, connect ws, register ws and don't reply when message arrives")
    .exec(HookIntegrationTasks.registerHook)
    .exec(HookIntegrationTasks.openWebSocket)
    .exec(HookIntegrationTasks.registerWebSocket)
    .exec(HookIntegrationTasks.awaitMessageAndThenDontReply)

  val putHookedResourceScenario = scenario("PUT request to hooked resource")
    .exec(HookIntegrationTasks.putToHookedResource)

  val registerHooks = scenario("Register hooks")
    .exec(HookIntegrationTasks.registerHooks)
}
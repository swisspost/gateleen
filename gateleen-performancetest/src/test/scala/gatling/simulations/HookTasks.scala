package gatling.simulations

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._
import scala.util.Random
import com.fasterxml.jackson.databind.ObjectMapper
import io.gatling.jsonpath._

object HookTasks {

  val registerHookCounter = new java.util.concurrent.atomic.AtomicInteger(1)
  val unregisterHookCounter = new java.util.concurrent.atomic.AtomicInteger(1)
  val randomResource = Random.alphanumeric.take(15).mkString

  val registerHook = exec(session => session.set("registerCounter", registerHookCounter.getAndIncrement))
    .exec(session => session.set("random", randomResource))
    .exec(http("register hook")
      .put("/playground/server/tests/hooktest/${random}/_hooks/listeners/http/push/${registerCounter}")
      .body(StringBody("""{ "destination": "/playground/server/event/v1/channels/${registerCounter}", "methods": ["PUT"], "expireAfter": 1200, "fullUrl": true, "staticHeaders": { "x-sync": true} }""")).asJSON
      .check(status is 200)
    )

  val unregisterHook =  exec(session => session.set("unregisterCounter", unregisterHookCounter.getAndIncrement))
    .exec(session => session.set("random", randomResource))
    .exec(http("unregister hook")
      .delete("/playground/server/tests/hooktest/${random}/_hooks/listeners/http/push/${unregisterCounter}")
      .check(status is 200)
    )

  val putToHookedResource = exec(session => session.set("random", randomResource))
    .exec(http("put to hooked resource")
      .put("/playground/server/tests/hooktest/${random}/myres")
      .body(StringBody("""{ "someProperty": 123 }""")).asJSON
      .check(status is 200)
    )

  val connectWebSocket = exec(session => session.set("connId", Random.alphanumeric.take(8).mkString))
    .exec(session => session.set("serverId", "%03d".format(Random.nextInt(1000))))
    .exec(ws("open WebSocket").open("ws://localhost:7012/playground/server/event/v1/sock/${serverId}/${connId}/websocket"))
    .exec(ws("register WebSocket").sendText("""["{\"type\":\"register\",\"address\":\"event-${registerCounter}\"}"]"""))

  val openWebSocket = exec(session => session.set("connId", Random.alphanumeric.take(8).mkString))
    .exec(session => session.set("serverId", "%03d".format(Random.nextInt(1000))))
    .exec(ws("open WebSocket").open("ws://localhost:7012/playground/server/event/v1/sock/${serverId}/${connId}/websocket"))

  val registerWebSocket = exec(ws("register WebSocket").sendText("""["{\"type\":\"register\",\"address\":\"event-${registerCounter}\"}"]"""))

  val waitForWebSocketCall = exec(ws("wait for ws call").check(wsListen.within(120 seconds).until(1).regex(".*someProperty.*")))

  val closeWebSocket = exec(ws("close WebSocket").close)

  val checkPushNotificationQueues = exec(session => session.set("count", Constants.numberOfUsers))
    .exec(http("check queues")
      .get("/playground/server/redisques/queues?count")
      .check(status is 200, jsonPath("$[?(@.count>=${count})]").exists)
    )

  val checkPushNotificationQueuesEmpty = exec(http("check queues")
      .get("/playground/server/redisques/queues?count")
      .check(status is 200, jsonPath("$[?(@.count<=1)]").exists)
    )

  val checkServerIsStillResponsive = exec(http("PUT some resource")
    .put("/playground/server/tests/someResource")
    .body(StringBody("""{ "someProperty": 123 }""")).asJSON
    .check(status is 200)
  )

  val replyWebkSocket = exec(ws("reply WebSocket").sendText("""["{\"type\":\"send\",\"address\":\"123\",\"body\":{}}"]"""))

  val awaitMessageAndThenReply = exec(ws("wait for ws call").check(wsAwait.within(120 seconds).until(1).regex("a\\[.*").saveAs("send_response")))
    .exec {session =>
      val sendResponse = session("send_response").as[String]
      val splitted = sendResponse.split("replyAddress")
      val replyAddress = splitted(1).replaceAll("[^A-Za-z0-9]", "")

      session.set("replyAddress", replyAddress)
    }
    .exec(ws("reply WebSocket").sendText("""["{\"type\":\"send\",\"address\":\"${replyAddress}\",\"body\":{}}"]"""))
}
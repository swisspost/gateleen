package gatling.simulations

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._
import scala.util.Random

object Tasks {

  val registerHookCounter = new java.util.concurrent.atomic.AtomicInteger(1)
  val unregisterHookCounter = new java.util.concurrent.atomic.AtomicInteger(1)
  val randomResource = Random.alphanumeric.take(15).mkString

  val writeExpandResourcesToStorage = repeat(120, "index") {
    exec(http("PUT regular expand resource")
      .put("/playground/server/test/resources/expand/regular/res_${index}")
      .body(RawFileBody("expandResource.json")).asJSON
      .check(status is 200)
    )
      .exec(http("PUT storage expand resource")
        .put("/playground/server/test/resources/expand/storage/res_${index}")
        .body(RawFileBody("expandResource.json")).asJSON
        .check(status is 200)
      )
  }

  val readStorageExpand = exec(http("GET storage expand")
    .get("/playground/server/test/resources/expand/storage/")
    .queryParam("expand", "1")
    .check(status is 200, header("Etag") exists)
  )

  val readRegularExpand = exec(http("GET regular expand")
    .get("/playground/server/test/resources/expand/regular/")
    .queryParam("expand", "1")
    .check(status is 200, header("Etag") exists)
  )

  val writeToStorage = exec(session => session.set("resourceId", Random.alphanumeric.take(30).mkString))
    .exec(http("write resource to storage")
      .put("/playground/server/test/resources/crud/res_${resourceId}")
      .body(RawFileBody("dummyContent.json")).asJSON
      .check(status is 200)
    )

  val readFromStorage = exec(http("read resource from storage")
    .get("/playground/server/test/resources/crud/res_${resourceId}")
    .check(status is 200)
  )

  val deleteFromStorage = exec(http("delete resource from storage")
    .delete("/playground/server/test/resources/crud/res_${resourceId}")
    .check(status is 200)
  )

  val readNotExistingResourceFromStorage = exec(http("read not existing resource from storage")
    .get("/playground/server/test/resources/crud/res_${resourceId}")
    .check(status is 404)
  )

  val enqueue = repeat(50) {
    exec(session => session.set("queueName", Random.alphanumeric.take(10).mkString))
      .exec(http("enqueue")
        .put("/playground/server/test/queuetests/res")
        .header("x-queue", "queue_${queueName}")
        .body(RawFileBody("dummyContent.json")).asJSON
        .check(status is 202)
      )
  }

  val readQueues = exec(http("read queues")
    .get("/playground/server/queuing/queues/")
    .check(status is 200, jsonPath("$.queues[*]").count is 0)
  )

  val registerHook = exec(session => session.set("registerCounter", registerHookCounter.getAndIncrement))
    .exec(session => session.set("random", randomResource))
    .exec(http("register hook")
      .put("/playground/server/tests/hooktest/${random}/_hooks/listeners/http/push/${registerCounter}")
      .body(StringBody("""{ "destination": "/playground/server/event/v1/channels/${registerCounter}", "methods": ["PUT"], "expireAfter": 60, "fullUrl": true, "staticHeaders": { "x-sync": true} }""")).asJSON
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

  val checkServerIsStillResponsive = exec(http("PUT some resource")
    .put("/playground/server/tests/someResource")
    .body(StringBody("""{ "someProperty": 123 }""")).asJSON
    .check(status is 200)
  )
}
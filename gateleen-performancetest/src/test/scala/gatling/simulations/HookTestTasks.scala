package gatling.simulations

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._
import scala.util.Random
import com.fasterxml.jackson.databind.ObjectMapper
import io.gatling.jsonpath._
import io.gatling.core.validation._

object HookTestTasks {

  val registerHookCounter = new java.util.concurrent.atomic.AtomicInteger(1)
  val randomResource = Random.alphanumeric.take(15).mkString
  val randomPrefix = Random.alphanumeric.take(5).mkString

  val putToHookedResource = exec(session => session.set("random", randomResource))
    .exec(http("put to hooked resource")
      .put("/nemo/server/tests/hooktest/${random}/myres")
      .body(StringBody("""{ "someProperty": 123 }""")).asJSON
      .check(status is 200)
    )

  val getResource = exec(http("get a resource")
    .get("/nemo/server/admin/v1/logging")
    .check(status is 200)
  )

  val registerHook = exec(session => session.set("registerCounter", registerHookCounter.getAndIncrement))
    .exec(session => session.set("random", randomResource))
    .exec(session => session.set("rndPrfx", randomPrefix))
    .exec(http("register hook")
      .put("/nemo/server/tests/hooktest/${random}/_hooks/listeners/http/push/${rndPrfx}-${registerCounter}")
      .body(StringBody("""{ "destination": "/nemo/server/push/v1/devices/${rndPrfx}-${registerCounter}", "methods": ["PUT"], "expireAfter": 600, "fullUrl": true, "staticHeaders": { "x-sync": true} }""")).asJSON
      .check(status is 200)
    )

  val openWebSocket = exec(session => session.set("connId", Random.alphanumeric.take(8).mkString))
    .exec(session => session.set("rndPrfx", randomPrefix))
    .exec(session => session.set("serverId", "%03d".format(Random.nextInt(1000))))
    .exec(ws("open WebSocket").open("ws://"+Constants.targetHost+":"+Constants.targetPort+"/nemo/server/push/v1/sock/${serverId}/${connId}/websocket"))

  val registerWebSocket = exec(ws("register WebSocket").sendText("""["{\"type\":\"register\",\"address\":\"push-${rndPrfx}-${registerCounter}\"}"]"""))

  val awaitMessageAndThenReply = exec(ws("wait for ws call").check(wsAwait.within(120 seconds).until(1).regex("a\\[.*").saveAs("send_response")))
    .exec {session =>
      val sendResponse = session("send_response").as[String]
      val splitted = sendResponse.split("replyAddress")
      val replyAddress = splitted(1).replaceAll("[^A-Za-z0-9]", "")

      session.set("replyAddress", replyAddress)
    }
    .exec(ws("reply WebSocket").sendText("""["{\"type\":\"send\",\"address\":\"${replyAddress}\",\"body\":{}}"]"""))

  val awaitMessageAndThenDontReply = exec(ws("wait for ws call").check(wsAwait.within(120 seconds).until(1).regex("a\\[.*").saveAs("send_response")))
    .exec {session =>
      val sendResponse = session("send_response").as[String]
      val splitted = sendResponse.split("replyAddress")
      val replyAddress = splitted(1).replaceAll("[^A-Za-z0-9]", "")

      session.set("replyAddress", replyAddress)
    }

}
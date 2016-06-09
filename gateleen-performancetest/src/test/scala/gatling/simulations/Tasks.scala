package gatling.simulations

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._
import scala.util.Random

object Tasks {

  private def randomString = Random.alphanumeric.take(10).mkString
  private def randomResource =  s"""{
    "name": "$randomString",
    "description": "$randomString"
  }"""

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
    .check(status is 200)
  )

  val readRegularExpand = exec(http("GET regular expand")
    .get("/playground/server/test/resources/expand/regular/")
    .queryParam("expand", "1")
    .check(status is 200)
  )

  val writeToStorage = exec(session => session.set("resourceId", Random.alphanumeric.take(30).mkString))
      .exec(http("write resource to storage")
        .put("/playground/server/test/resources/res_${resourceId}")
        .body(RawFileBody("dummyContent.json")).asJSON
        .check(status is 200)
      )

  val readFromStorage = exec(http("read resource from storage")
      .get("/playground/server/test/resources/res_${resourceId}")
      .check(status is 200)
  )

  val deleteFromStorage = exec(http("delete resource from storage")
      .delete("/playground/server/test/resources/res_${resourceId}")
      .check(status is 200)
  )

  val readNotExistingResourceFromStorage = exec(http("read not existing resource from storage")
    .get("/playground/server/test/resources/res_${resourceId}")
    .check(status is 404)
  )

  val enqueue = repeat(100) {
    exec(session => session.set("queueName", Random.alphanumeric.take(10).mkString))
      .exec(http("enqueue")
        .put("/playground/server/test/queuetests/res")
        .header("x-queue", "queue_${queueName}")
        .body(StringBody(randomResource)).asJSON
        .check(status is 202)
      )
  }

  val readQueues = exec(http("read queues")
    .get("/playground/server/queuing/queues/")
    .check(status is 200, jsonPath("$.queues[*]").count is 0)
  )

  val writeReadExpand = buildExpandTasks("/playground/server/test/resources/expand")

  val writeReadStorageExpand = buildExpandTasks("/playground/server/test/resources/storageExpand")

  def buildExpandTasks(path: String) = {
    exec(
      session => session.set("randomFolder", Random.alphanumeric.take(30).mkString))
      .exec(http("add expand resoure 1")
        .put(path + "/${randomFolder}/res_1")
        .body(RawFileBody("dummyContent.json")).asJSON
        .check(status is 200)
      )
      .exec(http("add expand resoure 2")
        .put(path + "/${randomFolder}/res_2")
        .body(RawFileBody("dummyContent.json")).asJSON
        .check(status is 200)
      )
      .exec(http("add expand resoure 3")
        .put(path + "/${randomFolder}/res_3")
        .body(RawFileBody("dummyContent.json")).asJSON
        .check(status is 200)
      )
      .exec(http("add expand resoure 4")
        .put(path + "/${randomFolder}/res_4")
        .body(RawFileBody("dummyContent.json")).asJSON
        .check(status is 200)
      )
      .exec(http("add expand resoure 5")
        .put(path + "/${randomFolder}/res_5")
        .body(RawFileBody("dummyContent.json")).asJSON
        .check(status is 200)
      )
      .exec(http("get expand no etag")
        .get(path + "/${randomFolder}/")
        .queryParam("expand", "1")
        .check(status is 200, header("Etag").saveAs("EtagValue"))
      )
      .pause(1)
      .exec(http("get expand with etag")
        .get(path + "/${randomFolder}/")
        .queryParam("expand", "1")
        .header("If-None-Match", "${EtagValue}")
        .check(status is 304)
      )
  }
}
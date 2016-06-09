package gatling.simulations

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._
import scala.util.Random

object Tasks {

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
}
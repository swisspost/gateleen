package gatling.simulations

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._
import scala.util.Random

object Tasks {

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

}
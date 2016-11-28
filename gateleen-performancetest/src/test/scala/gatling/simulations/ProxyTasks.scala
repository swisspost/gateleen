package gatling.simulations

import io.gatling.core.Predef._
import io.gatling.http.Predef._

object ProxyTasks {
  val url: String = "/authservice-ldap-postextern/v1/tickets"
  val timestamp = System.currentTimeMillis()
  // #1
  val get_ticket = exec(
    http("proxy login: get ticket")
      .post(url)
      .header("Content-Type", "application/x-www-form-urlencoded")
      .formParam("username", Constants.loginUser)
      .formParam("password", Constants.loginPassword)
      .check(header("Location")
        .saveAs("location_url"))
      .check(status is 201)
  )
  // #2
  val get_service_ticket = exec(
    http("proxy login: get service ticket").
      post("${location_url}")
      .header("Content-Type", "application/x-www-form-urlencoded")
      .body(StringBody("service=https%3A%2F%2Fnemoint.post.ch%2Fnemo%2Fserver%2Fsecurity%2Fv1%2Fuser%3Fts%3D" + timestamp))
      .check(status is 200)
      .check(bodyString.saveAs("service_ticket"))
  )
  //# 3 -> After this we should have the cookie
  val get_cookie_url = "/nemo/server/security/v1/user"
  val get_cookie = exec(
    http("proxy login: get_cookie").get(get_cookie_url)
      .queryParam("ts", timestamp)
      .queryParam("ticket", "${service_ticket}")
      .header("Content-Type", "application/x-www-form-urlencoded")
      .check(status.in(200, 302), header("Set-Cookie").saveAs("cookie_from_server"))
  ).exec {session =>
      val cookie = session("cookie_from_server").as[String]
      val splitted = cookie.split(";")
      Constants.modAuthCas = splitted(0)
      session
    }

}
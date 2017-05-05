package gatling.simulations

import io.gatling.http.Predef._

object Constants {

  val responseTimeMs = 500

  val numberOfUsers: Int = System.getProperty("numberOfUsers").toInt
  val rampUpTime: Int = System.getProperty("rampUpTime").toInt

  val targetHost: String = System.getProperty("targetHost")
  val targetPort: Int = System.getProperty("targetPort").toInt
  val baseURL = "http://" + targetHost + ":" + targetPort

  var modAuthCas = ""

  val loginUser: String = System.getProperty("loginUser")
  val loginPassword: String = System.getProperty("loginPassword")

  val httpConf = http
    .baseURL(baseURL)
    .warmUp(baseURL + "/playground")
}
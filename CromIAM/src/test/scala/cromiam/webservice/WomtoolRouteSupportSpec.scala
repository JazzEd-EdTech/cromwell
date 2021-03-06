package cromiam.webservice

import akka.http.scaladsl.model.ContentTypes
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.testkit.ScalatestRouteTest
import common.assertion.CromwellTimeoutSpec
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers


class WomtoolRouteSupportSpec extends AnyFlatSpec with CromwellTimeoutSpec with Matchers with WomtoolRouteSupport with ScalatestRouteTest {

  override lazy val cromwellClient = new MockCromwellClient()

  behavior of "Womtool endpoint routes"

  it should "return 200 when we request to the right path" in {
    Post(s"/api/womtool/v1/describe") ~> womtoolRoutes ~> check {
      status shouldBe OK
      responseAs[String] shouldBe "Hey there, workflow describer"
      contentType should be(ContentTypes.`text/plain(UTF-8)`)
    }
  }

}

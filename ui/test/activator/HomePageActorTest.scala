package activator

import org.junit.Assert._
import org.junit._
import HomePageActor._
import play.api.libs.json._

class HomePageActorTest {
  @Test
  def testMessageSerialization(): Unit = {
    val message1 = OpenExistingApplication("location")
    val message1Json = Json.toJson(message1)
    val message1Return = message1Json.as[OpenExistingApplication]
    assertTrue("OpenExistingApplication message makes JSON round trip", message1 == message1Return)
    val message2 = CreateNewApplication("location1", "fuBar", Some("cool project"))
    val message2Json = Json.toJson(message2)
    val message2Return = message2Json.as[CreateNewApplication]
    assertTrue("CreateNewApplication message makes JSON round trip", message2 == message2Return)
  }
}

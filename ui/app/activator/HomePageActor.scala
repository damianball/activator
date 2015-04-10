/**
 * Copyright (C) 2013 Typesafe <http://typesafe.com/>
 */
package activator

import activator.typesafeproxy.{ TypesafeComProxyUIActor, UIActor, TypesafeComProxy }
import akka.actor._
import akka.util.Timeout
import play.api.libs.json._
import java.io.File
import akka.pattern.pipe
import scala.util.control.NonFatal
import scala.concurrent.{ Promise, Future }
import activator._
import play.api.libs.concurrent.Execution.Implicits.defaultContext

// THE API for the HomePage actor.
object HomePageActor {
  import play.api.libs.json._
  import play.api.libs.json.Json._
  import play.api.libs.functional.syntax._
  import JsonHelper._

  case class OpenExistingApplication(location: String)

  object OpenExistingApplication {
    val tag = "OpenExistingApplication"
    implicit val openExistingApplicationReads: Reads[OpenExistingApplication] =
      extractRequest[OpenExistingApplication](tag)((__ \ "location").read[String].map(OpenExistingApplication.apply _))
    implicit val openExistingApplicationWrites: Writes[OpenExistingApplication] =
      emitRequest(tag)(in => obj("location" -> in.location))

    def unapply(in: JsValue): Option[OpenExistingApplication] =
      Json.fromJson[OpenExistingApplication](in).asOpt
  }
  case class CreateNewApplication(location: String, templateId: String, projectName: Option[String])
  object CreateNewApplication {
    val tag = "CreateNewApplication"
    implicit val createNewApplicationReads: Reads[CreateNewApplication] =
      extractRequest(tag) {
        ((__ \ "location").read[String] and
          (__ \ "template").readNullable[String] and
          (__ \ "name").readNullable[String])((l, t, n) => CreateNewApplication(l, t.getOrElse(""), n))
      }
    implicit val createNewApplicationWrites: Writes[CreateNewApplication] =
      emitRequest(tag) { in =>
        obj("location" -> in.location,
          "template" -> in.templateId,
          "name" -> in.projectName)
      }

    def unapply(in: JsValue): Option[CreateNewApplication] =
      Json.fromJson[CreateNewApplication](in).asOpt
  }
  object RedirectToApplication {
    def apply(id: String): JsValue =
      JsObject(Seq(
        "response" -> JsString("RedirectToApplication"),
        "appId" -> JsString(id)))
  }
  object BadRequest {
    def apply(request: String, errors: Seq[String]): JsValue =
      JsObject(Seq(
        "response" -> JsString("BadRequest"),
        "errors" -> JsArray(errors map JsString.apply)))
  }
  object Status {
    def apply(info: String): JsValue =
      JsObject(Seq(
        "response" -> JsString("Status"),
        "info" -> JsString(info)))
  }
  case class Respond(json: JsValue)

  object LicenseAccepted {
    def apply(): JsValue =
      JsObject(Seq(
        "response" -> JsString("LicenseAccepted")))
    def unapply(in: JsValue): Boolean =
      try {
        if ((in \ "request").as[String] == "LicenseAccepted") true
        else false
      } catch {
        case e: JsResultException => false
      }
  }
}
class HomePageActor(typesafeComActor: ActorRef, lookupTimeout: Timeout) extends WebSocketActor[JsValue] with ActorLogging {

  AppManager.registerKeepAlive(self)

  import HomePageActor._
  override def onMessage(json: JsValue): Unit = json match {
    case UIActor.WebSocket.Inbound(req) =>
      context.actorSelection(req.actorPath).resolveOne()(lookupTimeout).onSuccess({ case a => a ! req })
    case TypesafeComProxyUIActor.Inbound(req) =>
      context.actorOf(TypesafeComProxyUIActor.props(req, typesafeComActor, self))
    case WebSocketActor.Ping(ping) => produce(WebSocketActor.Pong(ping.cookie))
    case OpenExistingApplication(msg) => openExistingApplication(msg.location)
    case CreateNewApplication(msg) => createNewApplication(msg.location, msg.templateId, msg.projectName)
    case _ =>
      log.error(s"HomeActor: received unknown msg: $json")
      produce(BadRequest(json.toString, Seq(s"Could not parse JSON for request: ${json}")))
  }

  override def subReceive: Receive = {
    case Respond(json) => produce(json)
    case UIActor.WebSocket.Outbound(msg) =>
      import UIActor.WebSocket._
      produce(Json.toJson(msg))
    case TypesafeComProxyUIActor.Outbound(msg) =>
      import TypesafeComProxyUIActor._
      produce(Json.toJson(msg))
  }

  // Goes off and tries to create/load an application.
  def createNewApplication(location: String, template: String, projectName: Option[String]): Unit = {
    import context.dispatcher
    val appLocation = new java.io.File(location)
    // a chance of knowing what the error is.
    val installed: Future[ProcessResult[File]] =
      controllers.api.Templates.doCloneTemplate(
        template,
        appLocation,
        projectName) map (result => result map (_ => appLocation))

    // Ensure feedback happens after clone-ing is done.
    for (result <- installed) {
      if (result.isSuccess)
        self ! Respond(Status("Template is cloned, compiling project definition..."))
      else
        log.warning("Failed to clone template: " + result)
    }
    loadApplicationAndSendResponse("CreateNewApplication", installed)
  }

  // Goes off and tries to open an application, responding with
  // whether or not we were successful to this actor.
  def openExistingApplication(location: String): Unit = {
    log.debug(s"Looking for existing application at: $location")
    // TODO - Ensure timeout is ok...
    val file = Validating(new File(location)).validate(
      Validation.fileExists,
      Validation.isDirectory)
    if (file.isSuccess)
      self ! Respond(Status("Compiling project definition..."))
    else
      log.warning(s"Failed to locate directory $location: " + file) // error response is generated in loadApplicationAndSendResponse
    val filePromise = Promise[ProcessResult[File]]()
    filePromise.success(file)
    loadApplicationAndSendResponse("OpenExistingApplication", filePromise.future)
  }

  // helper method that given a validated file, will try to load
  // the application id and return an appropriate response.
  private def loadApplicationAndSendResponse(request: String, file: Future[ProcessResult[File]]) = {
    import context.dispatcher
    val id = file flatMapNested { file =>
      AppManager.loadAppIdFromLocation(file,
        Some({
          json => self ! Respond(json)
        }))
    }
    val response = id map {
      case ProcessSuccess(id) =>
        log.debug(s"HomeActor: Found application id: $id")
        RedirectToApplication(id)
      // TODO - Return with form and flash errors?
      case ProcessFailure(errors) =>
        log.debug(s"HomeActor: Failed to find application: ${errors map (_.msg) mkString "\n\t"}")
        BadRequest(request, errors map (_.msg))
    } recover {
      case NonFatal(e) => BadRequest(request, Seq(s"${e.getClass.getName}: ${e.getMessage}"))
    } map Respond.apply
    pipe(response) to self
  }
}

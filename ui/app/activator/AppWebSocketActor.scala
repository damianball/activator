package activator

import activator.typesafeproxy._
import akka.actor._
import akka.event.LoggingAdapter
import akka.pattern._
import console.ClientController.HandleRequest
import play.api.Play
import play.api.libs.json.Json._
import play.api.libs.json._
import activator.JsonHelper._
import scala.reflect.ClassTag
import scala.util.control.NonFatal
import scala.util.{ Failure, Success }
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import akka.util.Timeout

class AppWebSocketActor(val config: AppConfig,
  val typesafeComActor: ActorRef,
  val lookupTimeout: Timeout) extends WebSocketActor[JsValue] with ActorLogging {
  implicit val timeout = WebSocketActor.timeout

  lazy val appDynamicsConfig = AppDynamics.fromConfig(Play.current.configuration.underlying)
  lazy val appDynamicsActor: ActorRef = context.actorOf(monitor.AppDynamicsActor.props(appDynamicsConfig, defaultContext))
  lazy val newRelicActor: ActorRef = context.actorOf(monitor.NewRelicActor.props(NewRelic.fromConfig(Play.current.configuration.underlying), defaultContext))

  override def onMessage(json: JsValue): Unit = {
    json match {
      case WebSocketActor.Ping(ping) => produce(WebSocketActor.Pong(ping.cookie))
      case UIActor.WebSocket.Inbound(req) =>
        context.actorSelection(req.actorPath).resolveOne()(lookupTimeout).onSuccess({ case a => a ! req })
      case TypesafeComProxyUIActor.Inbound(req) =>
        context.actorOf(TypesafeComProxyUIActor.props(req, typesafeComActor, self))
      case SbtRequest(req) => handleSbtPayload(req.json)
      case InspectRequest(m) => for (cActor <- consoleActor) cActor ! HandleRequest(json)
      case AppDynamicsRequest(m) => handleAppDynamicsRequest(m)
      case NewRelicRequest(m) => handleNewRelicRequest(m)
      case WriteTypesafeProperties(msg) =>
        AppWebSocketActor.bestEffortCreateTypesafeProperties(config.location, msg.subscriptionId)
      case _ => log.debug("unhandled message on web socket: {}", json)
    }
  }

  import sbt.protocol.Completion
  implicit val completionWrites = Json.writes[Completion]

  /**
   * Parses incoming sbt payload into an sbt command to execute.
   * Send result of execution asynchronously via web socket.
   *
   * The 'serialId' is what connects the request with the asynchronous response.
   * This way a client can filter out events that are a result of its invocation.
   *
   * Please note that the 'serialId' is not any id used in sbt-server.
   */
  def handleSbtPayload(json: JsValue) = {
    def sendResult(subType: String, serialId: Long, result: JsValue, partialCommand: Option[String] = None) = {
      var payload = Seq(
        "type" -> JsString("sbt"),
        "subType" -> JsString(subType),
        "serialId" -> JsNumber(serialId),
        "result" -> result)

      val pc = for {
        pc <- partialCommand
        r = Seq("partialCommand" -> JsString(pc))
      } yield r
      payload ++= pc.getOrElse(Seq.empty)

      context.parent ! NotifyWebSocket(JsObject(payload))
    }

    json.validate[SbtPayload](SbtPayload.sbtPayloadReads) match {
      case JsSuccess(payload, path) =>
        payload.requestType match {
          case AppWebSocketActor.requestExecution =>
            context.parent ? RequestExecution(payload.serialId, Some(payload.command)) map {
              case SbtClientResponse(serialId, executionId: Long, command) =>
                sendResult(AppWebSocketActor.requestExecution, serialId, JsNumber(executionId))
              case other =>
                log.debug(s"sbt could not execute command: $other")
            }
          case AppWebSocketActor.cancelExecution =>
            if (payload.executionId.isDefined) {
              context.parent ? CancelExecution(payload.serialId, payload.executionId.get) map {
                case SbtClientResponse(serialId, result: Boolean, _) =>
                  sendResult(AppWebSocketActor.cancelExecution, serialId, JsBoolean(result))
                case other =>
                  log.debug("sbt could not cancel command")
              }
            } else {
              log.debug("Cannot cancel sbt request without execution id.")
              None
            }
          case AppWebSocketActor.possibleAutoCompletions =>
            context.parent ? PossibleAutoCompletions(payload.serialId, Some(payload.command)) map {
              case SbtClientResponse(serialId, choicesAny: Vector[_], command) =>
                val choices = choicesAny.map(_.asInstanceOf[sbt.protocol.Completion])
                sendResult(AppWebSocketActor.possibleAutoCompletions, serialId, JsArray(choices.toList map { Json.toJson(_) }), command)
              case other => log.debug(s"sbt could not execute possible auto completions")
            }
          case other =>
            log.debug("Unknown sbt request type: $other")
            None
        }
      case e: JsError =>
        log.debug(s"Could not parse $json to valid SbtPayload. Error is: $e")
        None
    }
  }

  def handleAppDynamicsRequest(in: AppDynamicsRequest.Request): Unit = {
    in match {
      case x @ AppDynamicsRequest.Provision(username, password) =>
        val sink = context.actorOf(Props(new ProvisioningSink(ProvisioningSinkState(), log => new ProvisioningSinkUnderlying(log, produce))))
        askAppDynamics[monitor.AppDynamicsActor.Provisioned](monitor.AppDynamicsActor.InternalProvision(sink, username, password), x,
          f => s"Failed to provision AppDynamics: ${f.getMessage}")(_ => produce(toJson(x.response)))
      case x @ AppDynamicsRequest.Available =>
        askAppDynamics[monitor.AppDynamicsActor.InternalAvailableResponse](monitor.AppDynamicsActor.InternalAvailable, x,
          f => s"Failed AppDynamics availability check: ${f.getMessage}")(r => produce(toJson(x.response(r.result))))
      case x @ AppDynamicsRequest.ProjectEnabled =>
        askAppDynamics[monitor.AppDynamicsActor.InternalProjectEnabledResponse](monitor.AppDynamicsActor.InternalProjectEnabled(config.location), x,
          f => s"Failed AppDynamics enabled check: ${f.getMessage}")(r => produce(toJson(x.response(r.result))))
      case x @ AppDynamicsRequest.Deprovision =>
        askAppDynamics[monitor.AppDynamicsActor.Deprovisioned.type](monitor.AppDynamicsActor.InternalDeprovision, x,
          f => s"Failed AppDynamics deprovisioning: ${f.getMessage}")(r => produce(toJson(x.response)))
      case x @ AppDynamicsRequest.GenerateFiles(location, applicationName, nodeName, tierName, accountName, accessKey, hostName, port, sslEnabled) =>
        askAppDynamics[monitor.AppDynamicsActor.InternalGenerateFilesResult](monitor.AppDynamicsActor.InternalGenerateFiles(
          location, InstrumentationRequestTypes.AppDynamics(
            applicationName = applicationName,
            nodeName = nodeName,
            tierName = tierName,
            accountName = accountName,
            accessKey = accessKey,
            hostName = hostName,
            port = port.toInt,
            sslEnabled = sslEnabled)), x,
          f => s"Failed generating AppDynamics monitoring files: ${f.getMessage}")(r => produce(toJson(x.response)))
    }
  }

  def askAppDynamics[T <: monitor.AppDynamicsActor.InternalResponse](msg: monitor.AppDynamicsActor.InternalRequest, omsg: AppDynamicsRequest.Request, onFailure: Throwable => String)(body: T => Unit)(implicit tag: ClassTag[T]): Unit = {
    appDynamicsActor.ask(msg).mapTo[monitor.AppDynamicsActor.InternalResponse].onComplete {
      case Success(r: monitor.AppDynamicsActor.InternalErrorResponse) => produce(toJson(omsg.error(r.message)))
      case Success(`tag`(r)) => body(r)
      case Success(r: monitor.AppDynamicsActor.InternalResponse) =>
        log.error(s"Unexpected response from request: $msg got: $r expected: ${tag.toString()}")
      case Failure(f) =>
        val errorMsg = onFailure(f)
        log.error(f, errorMsg)
        produce(toJson(omsg.error(errorMsg)))
    }
  }

  def handleNewRelicRequest(in: NewRelicRequest.Request): Unit = {
    import monitor.NewRelicActor._
    in match {
      case x @ NewRelicRequest.Provision =>
        val sink = context.actorOf(Props(new ProvisioningSink(ProvisioningSinkState(), log => new ProvisioningSinkUnderlying(log, produce))))
        askNewRelic[monitor.NewRelicActor.InternalProvisioned](monitor.NewRelicActor.InternalProvision(sink), x,
          f => s"Failed to provision New Relic: ${f.getMessage}")(_ => produce(toJson(x.response)))
      case x @ NewRelicRequest.Available =>
        askNewRelic[monitor.NewRelicActor.InternalAvailableResponse](monitor.NewRelicActor.InternalAvailable, x,
          f => s"Failed New Relic availability check: ${f.getMessage}")(r => produce(toJson(x.response(r.result))))
      case x @ NewRelicRequest.EnableProject(key, name) =>
        askNewRelic[InternalProjectEnabled](monitor.NewRelicActor.InternalEnableProject(config.location, key, name), x,
          f => s"Failed to enable project[${config.location}] for New Relic: ${f.getMessage}")(_ => produce(toJson(x.response)))
      case x @ NewRelicRequest.IsProjectEnabled =>
        askNewRelic[InternalIsProjectEnabledResult](monitor.NewRelicActor.InternalIsProjectEnabled(config.location), x,
          f => s"Failed check if New Relic enabled: ${f.getMessage}")(r => produce(toJson(x.response(r.result))))
      case x @ NewRelicRequest.Deprovision =>
        askNewRelic[monitor.NewRelicActor.Deprovisioned.type](monitor.NewRelicActor.InternalDeprovision, x,
          f => s"Failed New Relic deprovisioning: ${f.getMessage}")(r => produce(toJson(x.response)))
      case x @ NewRelicRequest.IsSupportedJavaVersion =>
        askNewRelic[InternalIsSupportedJavaVersionResult](monitor.NewRelicActor.InternalIsSupportedJavaVersion, x,
          f => s"Failed checking for supported Java version: ${f.getMessage}")(r => produce(toJson(x.response(r.result, r.version))))
      case x @ NewRelicRequest.GenerateFiles(location, info) =>
        askNewRelic[monitor.NewRelicActor.InternalGenerateFilesResult](monitor.NewRelicActor.InternalGenerateFiles(location, config.location), x,
          f => s"Failed generating NewRelic monitoring files: ${f.getMessage}")(r => produce(toJson(x.response)))
    }
  }

  def askNewRelic[T <: monitor.NewRelicActor.InternalResponse](msg: monitor.NewRelicActor.InternalRequest, originalMsg: NewRelicRequest.Request, onFailure: Throwable => String)(body: T => Unit)(implicit tag: ClassTag[T]): Unit = {
    newRelicActor.ask(msg).mapTo[monitor.NewRelicActor.InternalResponse].onComplete {
      case Success(r: monitor.NewRelicActor.InternalErrorResponse) => produce(toJson(originalMsg.error(r.message)))
      case Success(`tag`(r)) => body(r)
      case Success(r: monitor.NewRelicActor.InternalResponse) =>
        log.error(s"Unexpected response from request: $msg got: $r expected: ${tag.toString()}")
      case Failure(f) =>
        val errorMsg = onFailure(f)
        log.error(f, errorMsg)
        produce(toJson(originalMsg.error(errorMsg)))
    }
  }

  override def subReceive: Receive = {
    case NotifyWebSocket(json) =>
      log.debug("sending message on web socket: {}", json)
      produce(json)
    case UIActor.WebSocket.Outbound(msg) =>
      import UIActor.WebSocket._
      produce(Json.toJson(msg))
    case TypesafeComProxyUIActor.Outbound(msg) =>
      import TypesafeComProxyUIActor._
      produce(Json.toJson(msg))
  }
}

object AppWebSocketActor {
  val requestExecution = "RequestExecution"
  val cancelExecution = "CancelExecution"
  val possibleAutoCompletions = "PossibleAutoCompletions"

  def bestEffortCreateTypesafeProperties(location: java.io.File, subscriptionId: String): Unit = {
    val sid = subscriptionId.trim
    if (sid.nonEmpty) {
      val propertiesFile = new java.io.File(location, "project/typesafe.properties")
      try {
        // templates should not have the file already, but if they do, punt because
        // we don't know what's going on.
        if (location.exists && !propertiesFile.exists) {
          propertiesFile.getParentFile().mkdirs() // in case project/ doesn't exist
          val props = new java.util.Properties()
          props.setProperty("typesafe.subscription", sid)
          val stream = new java.io.FileOutputStream(propertiesFile)
          props.store(stream, "Typesafe Reactive Platform subscription ID, see https://typesafe.com/subscription")
          stream.close()
        } else {
          System.out.println(s"Not writing project/typesafe.properties to $location ${if (location.exists) s"($location does not exist)"} ${if (propertiesFile.exists) s"($propertiesFile already exists)"}")
        }
      } catch {
        case NonFatal(e) =>
          System.err.println(s"Failed to write $propertiesFile: ${e.getClass.getName}: ${e.getMessage}")
      }
    }
  }

}

case class InspectRequest(json: JsValue)

case class SbtRequest(json: JsValue)

case class SbtPayload(serialId: Long, requestType: String, command: String, executionId: Option[Long])

case class WriteTypesafeProperties(subscriptionId: String)

object WriteTypesafeProperties {
  val tag = "WriteTypesafeProperties"

  implicit val writeTypesafePropertiesReads: Reads[WriteTypesafeProperties] =
    extractRequest[WriteTypesafeProperties](tag)((__ \ "subscriptionId").read[String].map(WriteTypesafeProperties.apply _))

  implicit val writeTypesafePropertiesWrites: Writes[WriteTypesafeProperties] =
    emitRequest(tag)(in => obj("subscriptionId" -> in.subscriptionId))

  def unapply(in: JsValue): Option[WriteTypesafeProperties] = Json.fromJson[WriteTypesafeProperties](in).asOpt
}

object InspectRequest {
  val tag = "InspectRequest"

  implicit val inspectRequestReads: Reads[InspectRequest] =
    extractRequest[InspectRequest](tag)((__ \ "location").read[JsValue].map(InspectRequest.apply _))

  implicit val inspectRequestWrites: Writes[InspectRequest] =
    emitRequest(tag)(in => obj("location" -> in.json))

  def unapply(in: JsValue): Option[InspectRequest] = Json.fromJson[InspectRequest](in).asOpt
}

object SbtRequest {
  val tag = "sbt"

  implicit val sbtRequestReads: Reads[SbtRequest] =
    extractRequest[SbtRequest](tag)((__ \ "payload").read[JsValue].map(SbtRequest.apply _))

  implicit val sbtRequestWrites: Writes[SbtRequest] =
    emitRequest(tag)(in => obj("payload" -> in.json))

  def unapply(in: JsValue): Option[SbtRequest] = Json.fromJson[SbtRequest](in).asOpt
}

object SbtPayload {

  import play.api.libs.functional.syntax._

  implicit val sbtPayloadReads = (
    (__ \ "serialId").read[Long] and
    (__ \ "type").read[String] and
    (__ \ "command").read[String] and
    (__ \ "executionId").readNullable[Long])(SbtPayload.apply _)
}

case class ProvisioningSinkState(progress: Int = 0)

class ProvisioningSinkUnderlying(log: LoggingAdapter, produce: JsValue => Unit) {
  import monitor.Provisioning._
  def onMessage(state: ProvisioningSinkState, status: Status, sender: ActorRef, self: ActorRef, context: ActorContext): ProvisioningSinkState = status match {
    case x @ Authenticating(diagnostics, url) =>
      produce(toJson(x))
      state
    case x @ ProvisioningError(message, exception) =>
      produce(toJson(x))
      log.error(exception, message)
      context stop self
      state
    case x @ Downloading(url) =>
      produce(toJson(x))
      state
    case x @ Progress(Left(value)) =>
      val p = state.progress
      if ((value / 100000) != p) {
        produce(toJson(x))
        state.copy(progress = value / 100000)
      } else state
    case x @ Progress(Right(value)) =>
      val p = state.progress
      if ((value.toInt / 10) != p) {
        produce(toJson(x))
        state.copy(progress = value.toInt / 10)
      } else state
    case x @ DownloadComplete(url) =>
      produce(toJson(x))
      state
    case x @ Validating =>
      produce(toJson(x))
      state
    case x @ Extracting =>
      produce(toJson(x))
      state
    case x @ Complete =>
      produce(toJson(x))
      context stop self
      state
  }
}

class ProvisioningSink(init: ProvisioningSinkState,
  underlyingBuilder: LoggingAdapter => ProvisioningSinkUnderlying) extends Actor with ActorLogging {
  val underlying = underlyingBuilder(log)
  import monitor.Provisioning._
  override def receive: Receive = {
    var state = init

    {
      case x: Status =>
        state = underlying.onMessage(state, x, sender, self, context)
    }
  }
}

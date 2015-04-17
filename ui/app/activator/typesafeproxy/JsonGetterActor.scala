package activator.typesafeproxy

import java.util.concurrent.TimeoutException

import activator.typesafeproxy.TypesafeComProxy.ActionPair
import akka.actor._
import play.api.Play.current
import play.api.libs.json._
import play.api.libs.ws._
import activator.HttpHelper

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.{ Try, Failure, Success }

object JsonGetterActor {
  type DoGetJson = (String, ActorRef) => Unit
  type ToPut = (Try[JsValue], Long, ActorRef) => ActionPair[JsValue]#Put

  sealed trait Notification

  def httpGetJson(timeout: FiniteDuration,
    executionContext: ExecutionContext,
    timeoutMessage: (String, FiniteDuration) => String = (url, d) => s"Timeout from $url.  Waited $d",
    failureMessage: (String, Throwable) => String = (url, e) => s"Failure getting $url.  Error: ${e.getMessage}")(jsonUrl: String,
      sendTo: ActorRef): Unit = {
    implicit val ec = executionContext

    def respondWith(result: Try[JsValue]): Unit = sendTo ! result

    val req = HttpHelper.proxyHolder(WS.url(jsonUrl)
      .withRequestTimeout(timeout.toMillis.intValue))

    req.get() onComplete {
      case Success(response) => respondWith {
        response.status match {
          case 200 =>
            Try(Json.parse(response.body))
          case status =>
            Failure(new ProxyFailure(s"Unexpected response code: $status"))
        }
      }
      case Failure(exception) => respondWith {
        exception match {
          case x: TimeoutException => Failure(new ProxyTimeout(timeoutMessage(jsonUrl, timeout), x))
          case e => Failure(new ProxyFailure(failureMessage(jsonUrl, e), e))
        }
      }
    }

  }

  def props(jsonUrl: String,
    doGetJson: JsonGetterActor.DoGetJson,
    toPut: JsonGetterActor.ToPut,
    uiActorProps: ActorRef => Props,
    version: Long,
    replyTo: ActorRef,
    websocketActor: ActorRef,
    startMessage: String => String = url => s"Fetching from $url",
    cancelString: String => String = url => s"Fetching from $url canceled by user"): Props =
    Props(new JsonGetterActor(jsonUrl, doGetJson, toPut, uiActorProps, version, replyTo, websocketActor, startMessage, cancelString))

}

class JsonGetterActor(jsonUrl: String,
  doGetJson: JsonGetterActor.DoGetJson,
  toPut: JsonGetterActor.ToPut,
  uiActorProps: ActorRef => Props,
  version: Long,
  replyTo: ActorRef,
  websocketActor: ActorRef,
  startMessage: String => String = url => s"Fetching from $url",
  cancelString: String => String = url => s"Fetching from $url canceled by user") extends Actor with ActorLogging {
  import TypesafeComProxy._

  private final val uiActor: ActorRef = context.actorOf(uiActorProps(websocketActor))

  def cancel(message: String = cancelString(jsonUrl)): Unit = {
    replyTo ! ActivatorInfo.Put(Failure(new ProxyCanceled(message)), version, self)
    context.become(doStop())
  }

  def onFailure(onRetry: () => Unit): Receive = {
    case UIActor.Cancel => cancel()
    case UIActor.Retry => onRetry()
  }

  def doStop(): Receive = {
    context.watch(uiActor)
    uiActor ! PoisonPill

    {
      case Terminated(`uiActor`) =>
        context stop self
    }
  }

  def handleResult(result: Try[JsValue]): Unit = result match {
    case x @ Success(_) =>
      replyTo ! toPut(x, version, self)
      context.become(doStop())
    case Failure(e: ProxyTimeout) =>
      log.error(s"Unable to fetch from: $jsonUrl", e)
      uiActor ! UIActor.RetryableRequests.Failure(e.getMessage, self, retryable = true)
      context.become(onFailure(() => context.become(runRequest())))
    case x @ Failure(e) =>
      log.error(s"Unknown exception fetching $jsonUrl", e)
      replyTo ! toPut(x, version, self)
      context.become(doStop())
  }

  def awaitResults(endReport: UIActor.ReportEndAction): Receive = {
    case UIActor.Cancel =>
      uiActor ! endReport
      cancel()
    case x @ Success(_: JsValue) =>
      uiActor ! endReport
      handleResult(x.asInstanceOf[Success[JsValue]])
    case x: Failure[_] =>
      uiActor ! endReport
      handleResult(x.asInstanceOf[Failure[JsValue]])
  }

  def runRequest(): Receive = {
    val actionId: String = UIActor.genActionId()
    doGetJson(jsonUrl, self)
    val startReport = UIActor.CancelableRequests.ReportStartAction(startMessage(jsonUrl), actionId, self)
    uiActor ! startReport
    awaitResults(startReport.endReport())
  }

  def receive: Receive = {
    runRequest()
  }
}

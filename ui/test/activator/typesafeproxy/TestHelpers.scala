package activator.typesafeproxy

import java.util.concurrent.atomic.AtomicReference

import activator.typesafeproxy.TypesafeComProxy.ActionPair
import akka.actor._
import akka.testkit._
import com.typesafe.config.{ Config, ConfigFactory }

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration._
import scala.util.{ Random, Try }
import java.util.concurrent.Executors

object AkkaTestKitHelper {
  val configString =
    """
      |akka {
      |  loglevel = "OFF"
      |  stdout-loglevel = "OFF"
      |}
      |test-dispatcher {
      |  type = Dispatcher
      |  executor = "fork-join-executor"
      |  fork-join-executor {
      |    parallelism-min = 2
      |    parallelism-factor = 2.0
      |    parallelism-max = 10
      |  }
      |  throughput = 1
      |}
    """.stripMargin

  val config = ConfigFactory.parseString(configString)
  def randomActorSystemName: String = s"test-actor-system-${new String(Random.alphanumeric.take(10).toArray)}"
}

class AkkaTestKitHelper(_system: ActorSystem) extends TestKit(_system) with ImplicitSender {
  def this(config: Config) = this(ActorSystem(AkkaTestKitHelper.randomActorSystemName, config))
  def this() = this(AkkaTestKitHelper.config)

  def after() = {
    system.shutdown()
    system.awaitTermination()
  }

  def pinnedDispatcher = system.dispatchers.lookup("test-dispatcher")

  lazy val name: String = s"test-${new String(Random.alphanumeric.take(10).toArray)}"

  case class FakeAuthenticatorInit(version: Long)

  case class FakeAuthenticatorMessage(replyTo: ActorRef, uiActor: ActorRef, message: Any) {
    def reply(reply: Any)(implicit sender: ActorRef): Unit = replyTo.tell(message, sender)
  }

  class FakeAuthenticator(version: Long, replyTo: ActorRef, webSocketActor: ActorRef) extends Actor {
    def receive: Receive = {
      testActor ! FakeAuthenticatorInit(version)

      {
        case msg => testActor ! FakeAuthenticatorMessage(replyTo, webSocketActor, msg)
      }
    }
  }

  case object FakeSubscriptionRPCInit

  case class FakeSubscriptionRPCMessage(replyTo: ActorRef, webSocketActor: ActorRef, message: Any) {
    def reply(reply: Any)(implicit sender: ActorRef): Unit = replyTo.tell(message, sender)
  }

  class FakeSubscriptionRPC(version: Long, replyTo: ActorRef, webSocketActor: ActorRef) extends Actor {
    def receive: Receive = {
      testActor ! FakeSubscriptionRPCInit

      {
        case msg => testActor ! FakeSubscriptionRPCMessage(replyTo, webSocketActor, msg)
      }
    }
  }

  case object FakeActivatorInfoInit

  case class FakeActivatorInfoMessage(replyTo: ActorRef, webSocketActor: ActorRef, message: Any) {
    def reply(reply: Any)(implicit sender: ActorRef): Unit = replyTo.tell(message, sender)
  }

  class FakeActivatorInfo(version: Long, replyTo: ActorRef, webSocketActor: ActorRef) extends Actor {
    def receive: Receive = {
      testActor ! FakeActivatorInfoInit

      {
        case msg => testActor ! FakeActivatorInfoMessage(replyTo, webSocketActor, msg)
      }
    }
  }

  def fakeAuthenticatorProps(request: ActionPair[AuthenticationState]#Get, version: Long, replyTo: ActorRef, webSocketActor: ActorRef): Props =
    Props(new FakeAuthenticator(version, replyTo, webSocketActor))

  def fakeSubscriptionRPCProps(request: ActionPair[SubscriberData]#Get, version: Long, replyTo: ActorRef, webSocketActor: ActorRef): Props =
    Props(new FakeSubscriptionRPC(version, replyTo, webSocketActor))

  def fakeActivatorInfoProps(request: ActionPair[ActivatorLatestInfo]#Get, version: Long, replyTo: ActorRef, webSocketActor: ActorRef): Props =
    Props(new FakeActivatorInfo(version, replyTo, webSocketActor))

  def withProxy[T](initialCacheState: TypesafeComProxy.CacheState = TypesafeComProxy.initialStateBuilder(authGetter = fakeAuthenticatorProps,
    subscriberDataGetter = fakeSubscriptionRPCProps,
    activatorInfoGetter = fakeActivatorInfoProps),
    webSocketActor: ActorRef = testActor)(body: ActorRef => T): T = {
    val proxy = system.actorOf(TypesafeComProxy.props(initialCacheState))
    val r = body(proxy)
    system stop proxy
    r
  }

  def authenticationResult(result: Try[AuthenticationState]): (String, String, ActorRef) => Unit = { (_, _, sendTo) =>
    sendTo ! result
  }

  def delayedAuthenticationResult(delay: FiniteDuration, result: Option[Try[AuthenticationState]] = None): (String, String, ActorRef) => Unit = {
    (_, _, sendTo) =>
      {
        Future {
          Thread.sleep(delay.toMillis)
          result.foreach(r => sendTo ! r)
        }(pinnedDispatcher)
        ()
      }
  }

  def mutableAuthenticationResult(result: Try[AuthenticationState]): (AtomicReference[Try[AuthenticationState]], (String, String, ActorRef) => Unit) = {
    val ref = new AtomicReference[Try[AuthenticationState]](result)
    (ref, (_, _, sendTo) => sendTo ! ref.get())
  }

  def withAuthenticationActor[T](doAuthenticate: AuthenticationActor.DoAuthenticate,
    uiActorProps: ActorRef => Props = UIActor.props,
    version: Long = 0L,
    replyTo: ActorRef = testActor,
    websocketActor: ActorRef = testActor,
    initMessage: Option[String] = None)(body: ActorRef => T): T = {
    val authenticator = system.actorOf(AuthenticationActor.props(doAuthenticate, uiActorProps, version, replyTo, websocketActor, initMessage))
    val r = body(authenticator)
    system stop authenticator
    r
  }

  def activatorLatestResult(result: Try[ActivatorLatestInfo]): ActorRef => Unit = { sendTo =>
    sendTo ! result
  }

  def delayedActivatorLatestResult(delay: FiniteDuration, result: Option[Try[ActivatorLatestInfo]] = None): ActorRef => Unit = {
    sendTo =>
      {
        Future {
          Thread.sleep(delay.toMillis)
          result.foreach(r => sendTo ! r)
        }(pinnedDispatcher)
        ()
      }
  }

  def mutableActivatorLatestResult(result: Try[ActivatorLatestInfo]): (AtomicReference[Try[ActivatorLatestInfo]], ActorRef => Unit) = {
    val ref = new AtomicReference[Try[ActivatorLatestInfo]](result)
    (ref, sendTo => sendTo ! ref.get())
  }

  def withActivatorLatestActor[T](doGetActivatorLatest: ActivatorLatestActor.DoGetActivatorLatest,
    uiActorProps: ActorRef => Props = UIActor.props,
    version: Long = 0L,
    replyTo: ActorRef = testActor,
    websocketActor: ActorRef = testActor)(body: ActorRef => T): T = {
    val authenticator = system.actorOf(ActivatorLatestActor.props(doGetActivatorLatest, uiActorProps, version, replyTo, websocketActor))
    val r = body(authenticator)
    system stop authenticator
    r
  }

  def subscriptionDataResult(result: Try[SubscriberData]): (AuthenticationStates.AuthenticationData, ActorRef) => Unit = { (_, sendTo) =>
    sendTo ! result
  }

  def mutableSubscriptionDataResult(result: Try[SubscriberData]): (AtomicReference[Try[SubscriberData]], (AuthenticationStates.AuthenticationData, ActorRef) => Unit) = {
    val ref = new AtomicReference[Try[SubscriberData]](result)
    (ref, (_, sendTo) => sendTo ! ref.get())
  }

  def delayedSubscriptionDataResult(delay: FiniteDuration, result: Option[SubscriberData] = None): (AuthenticationStates.AuthenticationData, ActorRef) => Unit = {
    (_, sendTo) =>
      {
        Future {
          Thread.sleep(delay.toMillis)
          result.foreach(r => sendTo ! r)
        }(pinnedDispatcher)
        ()
      }
  }

  def withSubscriptionDataActor[T](doGetSubscriptionData: SubscriptionDataActor.DoGetSubscriptionData,
    uiActorProps: ActorRef => Props = UIActor.props,
    version: Long = 0L,
    replyTo: ActorRef = testActor,
    websocketActor: ActorRef = testActor)(body: ActorRef => T): T = {
    val rpc = system.actorOf(SubscriptionDataActor.props(doGetSubscriptionData, uiActorProps, version, replyTo, websocketActor))
    val r = body(rpc)
    system stop rpc
    r
  }
}

abstract class Specification[T <: AkkaTestKitHelper] {
  def gen(): T
  def withHelper[U](body: T => U): U = {
    val h = gen()
    try body(h)
    finally (h.after())
  }
}

class DefaultSpecification extends Specification[AkkaTestKitHelper] {
  def gen() = new AkkaTestKitHelper()
}

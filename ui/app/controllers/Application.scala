/**
 * Copyright (C) 2013 Typesafe <http://typesafe.com/>
 */
package controllers

import activator.typesafeproxy._
import play.api.mvc.{ Action, Controller, WebSocket }
import java.io.File
import scala.concurrent.Future
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json._
import activator.{ RootConfig, AppConfig, AppManager, Platform, DeathReportingProxy }
import activator.properties.ActivatorProperties
import play.Logger
import play.api.libs.iteratee.{ Iteratee, Enumerator }
import play.api.Play
import play.api.Mode
import play.filters.csrf._
import java.util.concurrent.atomic.AtomicInteger
import activator.cache.TemplateMetadata
import java.util.UUID

case class ApplicationModel(
  id: String,
  socketId: String,
  location: String,
  plugins: Seq[String],
  name: String,
  template: Option[String],
  recentApps: Seq[AppConfig],
  hasLocalTutorial: Boolean) {
}

object ApplicationModel {
  implicit val writes = Json.writes[ApplicationModel]
}

case class HomeModel(
  userHome: String,
  templates: Seq[TemplateMetadata],
  otherTemplateCount: Long,
  recentApps: Seq[AppConfig],
  tags: Seq[String])

// Data we get from the new application form.
case class NewAppForm(
  name: String,
  location: String,
  template: String)

case class FromLocationForm(location: String)

// Here is where we detect if we're running at a given project...
object Application extends Controller {

  /**
   * Our index page.  Either we load an app from the CWD, or we direct
   * to the homepage to create a new app.
   */
  def index = CSRFAddToken {
    Action.async {
      AppManager.loadAppIdFromLocation(cwd) map {
        case activator.ProcessSuccess(name) => Redirect(routes.Application.app(name))
        case activator.ProcessFailure(errors) =>
          // TODO FLASH THE ERROR, BABY
          Redirect(routes.Application.forceHome)
      }
    }
  }

  import play.api.data._
  import play.api.data.Forms._
  /** The new application form on the home page. */
  val newAppForm = Form(
    mapping(
      "name" -> text,
      "location" -> text,
      "template" -> text)(NewAppForm.apply)(NewAppForm.unapply))

  def getAppsThatExist(applications: Seq[AppConfig]): Seq[AppConfig] = applications.filter { appConfig =>
    new File(appConfig.location, "project/build.properties").exists()
  }

  /** Reloads the model for the home page. */
  private def homeModel = api.Templates.templateCache.metadata map { templates =>
    val tempSeq = templates.toSeq
    val featured = tempSeq sortBy (!_.featured)
    val config = RootConfig.user
    HomeModel(
      userHome = ActivatorProperties.GLOBAL_USER_HOME,
      templates = featured,
      otherTemplateCount = tempSeq.length,
      recentApps = getAppsThatExist(config.applications),
      tags = Seq("reactive", "scala", "java", "java8", "starter", "akka", "play", "slick", "spray", "streams", "spark", "angular", "javascript", "database", "websocket"))
  }

  def redirectToApp(id: String) = CSRFAddToken {
    Action {

      Redirect(routes.Application.app(id))
    }
  }

  /** Loads the homepage, with a blank new-app form. */
  def forceHome = CSRFAddToken {
    Action.async { implicit request =>
      homeModel map { model =>
        import controllers.api.Templates.Protocol
        val templates = play.api.libs.json.Json.toJson(model.templates)
        Ok(views.html.home(model, newAppForm, templates))
      }
    }
  }

  def test = CSRFAddToken {
    Action { implicit request =>
      import Play.current
      if (Play.mode == Mode.Dev)
        Ok(views.html.test())
      else
        NotFound
    }
  }

  /** Loads an application model and pushes to the view by id. */
  def app(id: String) = CSRFAddToken {
    Action.async { implicit request =>
      // TODO - Different results of attempting to load the application....
      Logger.debug("Loading app for /app html page")
      AppManager.getOrCreateApp(activator.AppIdSocketId(id, UUID.randomUUID())).map { theApp =>
        Logger.debug(s"loaded for html page: ${theApp}")
        Ok(views.html.main(getApplicationModel(theApp)))
      } recover {
        case e: Exception =>
          // display it on home screen
          Logger.error("Failed to load app id " + id + ": " + e.getMessage)
          Redirect(routes.Application.forceHome).flashing("error" -> e.getMessage)
      }
    }
  }

  case class SearchResult(title: String, subtitle: String, url: String, tpe: String)
  object SearchResult {
    import play.api.libs.json._
    implicit object MyFormat extends Writes[SearchResult] {
      def writes(o: SearchResult): JsValue = {
        JsObject(Seq(
          "title" -> JsString(o.title),
          "subtitle" -> JsString(o.subtitle),
          "url" -> JsString(o.url),
          "type" -> JsString(o.tpe)))
      }
    }
  }

  // TODO - Clean this up a bit, maybe add semantic knowledge of the possible return types...
  // maybe some caching, basically not so naive.
  def search(id: String, search: String) = Action.async { implicit request =>
    // Logger.debug(s"Searching for actions on app [$id]: $search")

    AppManager.loadConfigFromAppId(id).map { config =>
      val fileResults = searchFileResults(search, config.location)
      import play.api.libs.json._
      Ok(Json toJson fileResults)
    } recover {
      case e: Exception =>
        Logger.error(s"Failed to run search on app $id - $search:  ${e.getMessage}")
        Ok("")
    }
  }

  private def searchFileResults(search: String, base: File): Seq[SearchResult] = {
    val files = searchFiles(search, base)
    // TODO - Prioritize these results...
    for {
      file <- files
    } yield SearchResult(
      title = file.getName,
      subtitle = file.getAbsolutePath,
      url = Platform.getClientFriendlyLink(file, base),
      tpe = "Code")
  }

  // Ironically, this was a google interview question.
  private def searchFiles(search: String, base: File): Seq[File] = {
    import sbt._
    import sbt.Path._
    def searchMatches(name: String): Boolean = {
      var i = 0;
      var j = 0;
      while (i < name.length && j < search.length) {
        if (Character.toLowerCase(name.charAt(i)) == Character.toLowerCase(search.charAt(j))) {
          j += 1
        }
        i += 1
      }
      // If we exhausted the search string, we've successfully found the thing
      (j == search.length)
    }
    // TODO - We should filter out stupid files (like more than target/, etc.)
    for {
      file <- (base.*** --- base).get
      if (!file.getAbsolutePath.contains("target"))
      if searchMatches(file.getName)
    } yield file
  }

  private def connectionStreams(socketId: UUID): Future[(Iteratee[JsValue, _], Enumerator[JsValue])] = {
    Logger.debug(s"Computing connection streams for app ID $socketId")
    val streamsFuture = AppManager.getApp(socketId) flatMap { app =>
      Logger.debug(s"Loaded app for connection: $app")
      // this is just easier to debug than a timeout; it isn't reliable
      if (app.isTerminated) throw new RuntimeException("App is dead")

      import activator.WebSocketActor.timeout
      DeathReportingProxy.ask(app.system, app.actor, activator.CreateWebSocket).map {
        case activator.WebSocketAlreadyUsed =>
          Logger.warn("web socket already in use for $app")
          throw new RuntimeException("can only open apps in one tab at a time")
        case whatever =>
          Logger.debug(s"CreateWebSocket resulted in $whatever")
          whatever
      }.mapTo[(Iteratee[JsValue, _], Enumerator[JsValue])].map { streams =>
        Logger.debug("WebSocket streams created")
        streams
      }
    }

    streamsFuture onFailure {
      case e: Throwable =>
        Logger.info(s"WebSocket failed to open: ${e.getClass.getName}: ${e.getMessage}")
    }

    streamsFuture
  }

  /**
   * Connects from an application page to the "stateful" actor/server we use
   * per-application for information.
   */
  def connectApp(socketId: String) = activator.WebSocketUtil.socketCSRFCheck {

    val id = UUID.fromString(socketId)

    WebSocket.tryAccept[JsValue] { request =>
      Logger.debug("Connect request for app id: " + id)

      AppManager.getApp(id) flatMap { theApp =>
        val streamsFuture = activator.Akka.retryOverMilliseconds(2000)(connectionStreams(id))

        streamsFuture onFailure {
          case e: Throwable =>
            Logger.warn("Giving up on opening websocket")
        }

        streamsFuture.mapTo[(play.api.libs.iteratee.Iteratee[JsValue, _], play.api.libs.iteratee.Enumerator[JsValue])].map { streams => Right(streams) }
      }
    }
  }

  /** List all the applications in our history as JSON. */
  def getHistory = Action { request =>
    Ok(Json.toJson(RootConfig.user.applications))
  }

  def forgetApp(id: String) = Action.async { request =>
    AppManager.forgetApp(id) map { _ =>
      Logger.debug(s"Forgot app $id from app history")
      Ok
    } recover {
      case e: Exception =>
        Logger.warn(s"Failed to forget $id: ${e.getClass.getName}: ${e.getMessage}")
        Forbidden(e.getMessage)
    }
  }

  /**
   * Returns the application model (for rendering the page) based on
   * the current activator App.
   */
  def getApplicationModel(app: activator.App) =
    ApplicationModel(
      app.id.appId,
      app.id.socketId.toString(),
      Platform.getClientFriendlyFilename(app.config.location),
      // TODO - These should be drawn from the template itself...
      Seq("plugins/welcome/welcome", "plugins/code/code", "plugins/compile/compile", "plugins/test/test", "plugins/run/run", "plugins/inspect/inspect"),
      app.config.cachedName getOrElse app.config.id,
      // TODO - something less lame than exception here...
      app.config.templateID,
      getAppsThatExist(RootConfig.user.applications),
      hasLocalTutorial(app.config))

  def hasLocalTutorial(config: activator.AppConfig): Boolean = {
    val tutorialConfig = new java.io.File(config.location, activator.cache.Constants.METADATA_FILENAME)
    tutorialConfig.exists
  }

  def appTutorialFile(id: String, location: String) = CSRFAddToken {
    Action.async { request =>
      AppManager.loadConfigFromAppId(id) map { config =>
        // If we're debugging locally, pull the local tutorial, otherwise redirect
        // to the templates tutorial file.
        if (hasLocalTutorial(config)) {
          // TODO - Don't hardcode tutorial directory name!
          val localTutorialDir = new File(config.location, "tutorial")
          val file = new File(localTutorialDir, location)
          if (file.exists) Ok sendFile file
          else NotFound
        } else config.templateID match {
          case Some(template) => Redirect(api.routes.Templates.tutorial(template, location))
          case None => NotFound
        }
      } recover {
        case e: Exception =>
          // TODO we need to have an error message and "flash" it then
          // display it on home screen
          Logger.error("Failed to find tutorial app id " + id + ": " + e.getMessage(), e)
          NotFound
      }
    }
  }

  val homeActorCount = new AtomicInteger(1)

  val typesafeComConfig = TypesafeComProxy.fromConfig(Play.current.configuration.underlying)
  val lookupTimeout = typesafeComConfig.lookupTimeout
  val loginEndpoint = AuthenticationActor.httpDoAuthenticate(typesafeComConfig.login.url, typesafeComConfig.login.timeout, defaultContext)_
  val subscriberEndpoint = SubscriptionDataActor.httpGetSubscriptionData(typesafeComConfig.subscriptionData.url, typesafeComConfig.subscriptionData.timeout, defaultContext)_
  val activatorInfoEndpoint = ActivatorLatestActor.httpGetActivatorLatest(typesafeComConfig.activatorInfo.url, typesafeComConfig.activatorInfo.timeout, defaultContext)_
  val httpJsonGetter = JsonGetterActor.httpGetJson(typesafeComConfig.activatorInfo.timeout, defaultContext)_
  val initState = TypesafeComProxy.initialStateBuilder(authGetter = (_, v, r, w) => AuthenticationActor.props(loginEndpoint, UIActor.props, v, r, w),
    subscriberDataGetter = (_, v, r, w) => SubscriptionDataActor.props(subscriberEndpoint, UIActor.props, v, r, w),
    activatorInfoGetter = (_, v, r, w) => ActivatorLatestActor.props(activatorInfoEndpoint, UIActor.props, v, r, w),
    httpJsonGetter = (req, v, r, w) => JsonGetterActor.props(req.url, httpJsonGetter, req.toPut, UIActor.props, v, r, w))
  val typesafeComActor = activator.Akka.system.actorOf(TypesafeComProxy.props(initialCacheState = initState))

  /** Opens a stream for home events. */
  def homeStream =
    activator.WebSocketActor.create(activator.Akka.system, new activator.HomePageActor(typesafeComActor, lookupTimeout), "home-socket-" + homeActorCount.getAndIncrement())

  /** The current working directory of the app. */
  val cwd = (new java.io.File(".").getAbsoluteFile.getParentFile)
}

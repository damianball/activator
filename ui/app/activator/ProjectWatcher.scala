/**
 * Copyright (C) 2013 Typesafe <http://typesafe.com/>
 */
package activator

import akka.actor._
import scala.concurrent.duration._
import java.io.File
import java.io.IOException
import play.api.libs.json._

sealed trait ProjectWatcherRequest
case object RescanProjectRequest extends ProjectWatcherRequest
case class SetSourceFilesRequest(files: Set[File]) extends ProjectWatcherRequest

sealed trait ProjectWatcherEvent

// for the actual decision to recompile, we use the list of sources
// retrieved from the sbt build, not just everything matching a glob
// (which is what we find here). We use a poll in this actor to find
// added and removed files. File changes are detected by the two child
// FileWatcher actors.
// So this actor is responsible for:
// - telling the client-side JS to reload sources from sbt
// - telling its child actor to change the set of sbt build sources
// - reloading the sbt build when sbt build sources change
class ProjectWatcher(val location: File, val newSourcesSocket: ActorRef, val appActor: ActorRef)
  extends EventSourceActor with ActorLogging {

  private val interval = 3.seconds

  case class Contents(projectSources: Set[File], buildSources: Set[File]) {
    def ++(other: Contents): Contents = {
      copy(projectSources = (projectSources ++ other.projectSources),
        buildSources = (buildSources ++ other.buildSources))
    }
  }

  object Contents {
    val empty = Contents(Set.empty, Set.empty)
  }

  private var files: Contents = Contents.empty

  private val timer = {
    implicit val ec = context.dispatcher
    context.system.scheduler.schedule(interval, interval, self, RescanProjectRequest)
  }

  private val sbtBuildWatcher = context.actorOf(Props(new FileWatcher()), name = "sbt-build-file-watcher")
  private val sourcesWatcher = context.actorOf(Props(new FileWatcher()), name = "sources-watcher")

  context.watch(sbtBuildWatcher)
  sbtBuildWatcher ! SubscribeFileChanges(self)
  context.watch(sourcesWatcher)
  sourcesWatcher ! SubscribeFileChanges(self)

  override def receive = {
    case Terminated(ref) if (ref == sbtBuildWatcher || ref == sourcesWatcher) =>
      log.debug("child watcher {} died, so we are too", ref)
      self ! PoisonPill

    case req: ProjectWatcherRequest =>
      req match {
        case RescanProjectRequest =>
          rescan()
        case SetSourceFilesRequest(files) =>
          log.debug("Setting {} source files to watch", files.size)
          sourcesWatcher ! SetFilesToWatch(files)
      }

    case event: FileWatcherEvent => event match {
      case FilesChanged(source) =>
        if (source == sourcesWatcher) {
          appActor ! ProjectFilesChanged
        } else if (source == sbtBuildWatcher) {
          appActor ! ReloadSbtBuild
        } else {
          log.debug("Unknown files changed notification from {}", source)
        }
    }
  }

  private def isSource(f: File): Boolean = {
    val name = f.getName
    name.endsWith(".scala") || name.endsWith(".java")
  }

  private def isSbt(f: File): Boolean = {
    val name = f.getName
    name.endsWith(".sbt")
  }

  private def isTypesafeProperties(f: File): Boolean = f.getName == "typesafe.properties"

  private def scanBuildDir(dir: File): Set[File] = {
    dir.listFiles().toList.map { f =>
      if (isSource(f) || isSbt(f) || isTypesafeProperties(f)) {
        Set(f)
      } else if (f.isDirectory()) {
        scanBuildDir(f)
      } else {
        Set.empty[File]
      }
    }.fold(Set.empty[File]) { (sofar: Set[File], next: Set[File]) => sofar ++ next }
  }

  // this is inefficient, sue me. if it breaks in practice we can fix it.
  private def scanAnyDir(dir: File): Contents = {
    dir.listFiles().toList.map { f =>
      if (isSource(f)) {
        Contents(projectSources = Set(f), buildSources = Set.empty)
      } else if (isSbt(f)) {
        Contents(projectSources = Set.empty, buildSources = Set(f))
      } else if (f.isDirectory()) {
        if (f.getName == "project")
          Contents(projectSources = Set.empty, buildSources = scanBuildDir(f))
        else if (f.getName == "target")
          Contents.empty
        else
          scanAnyDir(f)
      } else {
        Contents.empty
      }
    }.fold(Contents.empty) { (sofar: Contents, c: Contents) => sofar ++ c }
  }

  private def rescan(): Unit = {
    try {
      val newContents = scanAnyDir(location)
      if (newContents != files) {
        if (newContents.buildSources != files.buildSources) {
          log.debug("Setting {} sbt build files to watch", newContents.buildSources.size)
          sbtBuildWatcher ! SetFilesToWatch(newContents.buildSources)
        }
        if (newContents.projectSources != files.projectSources) {
          log.debug("Setting {} project source files to watch", newContents.projectSources.size)
          sourcesWatcher ! SetFilesToWatch(newContents.projectSources)
        }
        files = newContents
      }
    } catch {
      case e: IOException =>
        log.debug(s"Failed to scan directory $location", e)
    }
  }

  override def postStop(): Unit = {
    log.debug("postStop")
    timer.cancel()
  }
}

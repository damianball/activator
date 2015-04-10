/*
 Copyright (C) 2014 Typesafe, Inc <http://typesafe.com>
 */
define([
  'commons/websocket',
  'commons/stream',
  'commons/types',
  './app',
  'widgets/modals/modals',
  'services/monitoring/monitoringSolutions'
], function(
  websocket,
  Stream,
  types,
  app,
  modals,
  monitoringSolutions
) {

  /**
  Tasks lists
  */
  var executionsById = {};
  var executions = ko.observableArray([]);
  var executionsByJobId = {};
  var tasksById = {};

  function findExecutionIdByTaskId(id) {
    return tasksById[id] && tasksById[id].executionId;
  }

  function findCommandByExecutionId(id) {
    return executionsById[id] && executionsById[id].command;
  }

  /**
  Tasks status
  */
  var workingTasks = {
    // these three are just flags (are they running)
    compile:  ko.observable(false),
    run:      ko.observable(false),
    test:     ko.observable(false),
    // this one is the Execution object or null
    current:  ko.observable(null)
  };
  var pendingTasks = {
    compile:  ko.observable(false),
    run:      ko.observable(false),
    stoppingRun: ko.observable(false),
    test:     ko.observable(false)
  };

  var reactivePlatform = (function() {
    var self = {};
    self.platformRelease = ko.observable(null);
    self.propertiesFileExists = ko.observable(false);
    self.subscriptionId = ko.observable(null);
    self.authorizedForProduction = ko.observable(false);
    self.availableFullVersion = ko.observable(null);
    self.availableMajorVersion = ko.observable(null);
    self.installedFullVersion = ko.observable(null);
    self.installedMajorVersion = ko.observable(null);
    self.fullUpdate = ko.observable(false);
    self.majorUpdate = ko.observable(false);
    self.isReactivePlatformProject = ko.observable(false);
    self.reset = function () {
      self.platformRelease(null);
      self.propertiesFileExists(false);
      self.subscriptionId(null);
      self.authorizedForProduction(false);
      self.availableFullVersion(null);
      self.availableMajorVersion(null);
      self.installedFullVersion(null);
      self.installedMajorVersion(null);
      self.fullUpdate(false);
      self.majorUpdate(false);
      self.isReactivePlatformProject(false);
    };
    return self;
  })();

  var mostRecentWithCompilationErrors = ko.observable(null);
  var mostRecentWithTestResults = ko.observable(null);

  /**
  Stream Events
  */
  var ProcessedExecutionsStream = Stream();

  /**
  Observable as an event dispatcher for complete tasks
  */
  var taskCompleteEvent = ko.observable({});
  taskCompleteEvent.extend({ notify: 'always' });
  function taskComplete(command, succeded){
    taskCompleteEvent({
      command:  command,
      succeded: succeded
    });
  }

  /**
  Task Event results (compile errors and tests)
  */
  var testResults = ko.computed(function() {
    var current = workingTasks.current();
    var past = mostRecentWithTestResults();
    // Priority: prefer to stream from current task;
    // then prefer to show last results we got;
    // then prefer to show nothing.
    if (current && current.testResults().length > 0) {
      return current.testResults();
    } else if (past) {
      return past.testResults();
    } else {
      return [];
    }
  });
  var testErrors = ko.computed(function() {
    return testResults().filter(function(t) {
      return t.outcome === "failed";
    });
  });
  var compilationErrors = ko.computed(function() {
    var current = workingTasks.current();
    var past = mostRecentWithCompilationErrors();
    // Priority: prefer to stream from current task;
    // then prefer to show last results we got;
    // then prefer to show nothing.
    if (current && (current.compilationErrors().length > 0 || current.changedCompileResult)) {
      return current.compilationErrors();
    } else if (past) {
      return past.compilationErrors();
    } else {
      return [];
    }
  });

  /**
  Temp holder for deferred possible outcomes.
  Uses the serialId as key to the deferred object.
  */
  var deferredRequests = {};
  var clientSerialId = 1;

  function sbtRequest(what, command, executionId) {
    var id = clientSerialId++
    var request = {
      "request" : "sbt",
      "payload" : {
        "serialId": id,
        "type" : what,
        "command": command,
        "executionId": executionId
      }
    };
    websocket.send(request);
    return id;
  }

  /**
   * Returns the client serial id used for this action.
   */
  function requestExecution(command) {
    return sbtRequest('RequestExecution', command);
  }

  /**
   * Reset inspect data
   */
  function resetInspect() {
    debug && console.log("Resetting Inspect data")
    websocket.send({
      "commands": [{
        "module": "lifecycle",
        "command": "reset"
      }]
    });
  }

  var isPlayApplication = function() {
    if (app.mainClass() && app.mainClass() === "play.core.server.NettyServer") {
      return true;
    }
    return false;
  };

  var playApplicationUrl = ko.observable(null);
  var playServerStarted = ko.computed(function() {
    return workingTasks.run() && playApplicationUrl() !== null;
  });
  // Watch whenever any task ends, because we can't target specifically play stop
  workingTasks.run.subscribe(function(v) {
    if (!v) {
      pendingTasks.stoppingRun(false);
      playApplicationUrl(null);
    }
  });

  /**
  Run command
  */
  var runCommand = ko.computed(function() {
    var forceRunCommand = false;
    var prependCommand = monitoringSolutions.prependCommand();
    if (isPlayApplication()) {
      debug && console.log("Using 'run' rather than 'run-main' for Play's server class");
      forceRunCommand = true;
      monitoringSolutions.isPlayApplication(true);
    }

    if (app.currentMainClass() && !forceRunCommand){
      return (prependCommand + "backgroundRunMain " + app.currentMainClass());
    } else {
      return (prependCommand + "backgroundRun");
    }
  });

  /**
   * Returns the result of the execution directly (deferred).
   * Use only when the caller must get the result back in "this" call.
   * Default should be to use "requestExection" as this has better overall performance.
   */
  function requestDeferredExecution(command) {
    var serialId = requestExecution(command);
    var result = $.Deferred();
    deferredRequests[serialId] = result;
    return result;
  }

  /**
   * Returns the client serial id used for this action.
   */
  function cancelExecution(id) {
    return sbtRequest('CancelExecution', "", id);
  }

  /**
   * Returns the result of the cancel execution directly (deferred).
   * Use only when the caller must get the result back in "this" call.
   * Default should be to use "cancelExecution" as this has better overall performance.
   */
  // function cancelDeferredExecution(id) {
  //   var serialId = cancelExecution(id);
  //   var result = $.Deferred();
  //   deferredRequests[serialId] = result;
  //   return result;
  // }

  /**
   * Uses a deferred object to "wait" for the result to come back from the server.
   * In other words the caller of this method can expect a result back.
   * See method 'subTypeEventStream("PossibleAutoCompletions")' below for more information about the result layout.
   */
  function deferredPossibleAutoCompletions(partialCommand) {
    var serialId = sbtRequest('PossibleAutoCompletions', partialCommand);
    var result = $.Deferred();
    deferredRequests[serialId] = result;
    return result;
  }

  var sbtEventStream = websocket.subscribe('type','sbt');
  var subTypeEventStream = function(subType) {
    return sbtEventStream.matchOnAttribute('subType',subType);
  }

  // Tasks
  subTypeEventStream("TaskStarted").each(function(message) {
    var execution = executionsById[message.event.executionId]
    if (execution) {
      var task = new Task(message);
      debug && console.log("Starting task ", task);
      // we want to be in the by-id hash before we notify
      // on the tasks array
      tasksById[task.taskId] = task;
      execution.tasks[task.taskId] = task;

      if (task.key === "compile" || task.key === "compileIncremental") {
        // for most executions we'll get "compile" AND "compileIncremental",
        // so this has to be idempotent
        execution.changedCompileResult = true;
      }
    } else {
      debug && console.log("Ignoring task for unknown execution " + message.event.executionId)
    }
  });

  subTypeEventStream("TaskFinished").each(function(message) {
    var task = tasksById[message.event.taskId];
    if (task) {
      // we want succeeded flag up-to-date when finished notifies
      task.finished(true);
      delete tasksById[task.taskId];
    }
  });

  var packageRegexp = new RegExp(".*\\.");

  subTypeEventStream("TaskEvent").each(function(message) {
    var event = message.event;
    var execution = executionsById[tasksById[event.taskId].executionId];
    if (!execution) throw "Orphan task detected";

    // TODO quit doing this, in theory we could look at someone
    // else's unrelated event
    var name = event.serialized.$type.replace(packageRegexp, "");

    if (name === "CompilationFailure") {
      debug && console.log("CompilationFailure: ", event);
      execution.compilationErrors.push(event.serialized);
    } else if (name === "TestEvent") {
      debug && console.log("TestEvent: ", event);
      execution.testResults.push(event.serialized);
    } else if (name === "PlayServerStarted") {
      playApplicationUrl(event.serialized.url);
    }
  });

  subTypeEventStream("DetachedEvent").each(function(message) {
    var event = message.event;

    var name = event.serialized.$type;
    // Some or all of these RP events arrive before the build
    // successfully loads, so be aware of that. None of them
    // arrive if the RP plugin isn't installed, so to handle the
    // case where you remove RP and reload, we will
    // need to look at whether we have the RP plugin listed
    // in MinimalBuildStructure and discard all the RP data if we
    // load a build with no RP plugin present.
    if (name === "com.typesafe.rp.protocol.SubscriptionIdEvent") {
      debug && console.log("SubscriptionIdEvent: ", event.serialized);
      reactivePlatform.propertiesFileExists(event.serialized.fileExists);
      reactivePlatform.subscriptionId(event.serialized.subscriptionId);
      reactivePlatform.isReactivePlatformProject(true);
    } else if (name === "com.typesafe.rp.protocol.SubscriptionLevelEvent") {
      debug && console.log("SubscriptionLevelEvent: ", event.serialized);
      reactivePlatform.authorizedForProduction(event.serialized.authorizedForProduction);
    } else if (name === "com.typesafe.rp.protocol.PlatformRelease") {
      debug && console.log("PlatformRelease: ", event.serialized);
      reactivePlatform.platformRelease(event.serialized);
      reactivePlatform.availableFullVersion(event.serialized.availableFullVersion);
      reactivePlatform.availableMajorVersion(event.serialized.availableMajorVersion);
      reactivePlatform.installedFullVersion(event.serialized.installedFullVersion);
      reactivePlatform.installedMajorVersion(event.serialized.installedMajorVersion);
      reactivePlatform.fullUpdate(event.serialized.fullUpdate);
      reactivePlatform.majorUpdate(event.serialized.majorUpdate);
    } else {
      debug && console.log("Ignoring DetachedEvent " + name, event.serialized);
    }
  });

  // We hard-code the association between a BackgroundJob and its Execution
  // The Execution that invokes it, ends right after the BackgroundJob started
  // It just makes things easier to force the excution to keep a reference of the job(s)
  subTypeEventStream("BackgroundJobStarted").each(function(message) {
    var execution = executionsById[message.event.executionId];
    var jobId = message.event.job.id;
    debug && console.log("BackgroundJobStarted: ", message);
    executionsByJobId[jobId] = execution;
    execution.jobIds.push(jobId);
  });
  subTypeEventStream("BackgroundJobFinished").each(function(message) {
    debug && console.log(message);
    var execution = executionsById[message.event.executionId];
    // TODO: inconsistency if you look at "BackgroundJobStarted", we have message.event.job.id
    var jobId = message.event.jobId;
    // /inconsistency
    debug && console.log("BackgroundJobFinished: ", message);
    postExecutionProcess(execution, true);
    delete executionsByJobId[jobId];
  });


  subTypeEventStream("ExecutionWaiting").each(function(message) {

    // If the execution is to stop execution...
    if (stopJob(message)) return;

    var execution = new Execution(message);
    debug && console.log("Waiting execution ", execution);
    // we want to be in the by-id hash before we notify
    // on the executions array
    executionsById[execution.executionId] = execution;
    executions.push(execution);

    // Increment active tasks (to make icons glowing)
    switch(execution.commandId){
      case "compile":
        // Reset the compilation errors
        pendingTasks.compile(pendingTasks.compile()+1);
        break;
      case "run":
        pendingTasks.run(pendingTasks.run()+1);
        break;
      case "test":
        pendingTasks.test(pendingTasks.test()+1);
        break;
    }
  });

  subTypeEventStream("ExecutionStarting").each(function(message) {
    var execution = executionsById[message.event.id];
    if (execution) {
      execution.started(new Date());
      workingTasks.current(execution);

      // Increment active tasks (to make icons glowing)
      switch(execution.commandId){
        case "compile":
          // Reset the compilation errors
          workingTasks.compile(workingTasks.compile()+1);
          break;
        case "run":
          workingTasks.run(workingTasks.run()+1);
          break;
        case "test":
          workingTasks.test(workingTasks.test()+1);
          break;
      }
    }
  });

  function handleSuccessOrFailure(message){
    var id = message.event.id;
    var succeeded = message.subType === "ExecutionSuccess";
    var execution = executionsById[id];

    if (execution && !execution.jobIds().length) {
      postExecutionProcess(execution, succeeded);
    }
  }
  subTypeEventStream("ExecutionFailure").each(handleSuccessOrFailure);
  subTypeEventStream("ExecutionSuccess").each(handleSuccessOrFailure);

  // As a separate function to handle both execution and background jobs
  function postExecutionProcess(execution, succeeded) {

    // we want succeeded flag up-to-date when finished notifies
    execution.succeeded(succeeded);
    taskComplete(execution.commandId, succeeded); // Throw an event
    execution.finished(new Date());

    if (execution.changedCompileResult) {
      mostRecentWithCompilationErrors(execution);
    }
    if (execution.testResults().length > 0) {
      mostRecentWithTestResults(execution);
    }

    var current = workingTasks.current();
    if (current !== null && current.executionId === execution.executionId) {
      workingTasks.current(null);
    }

    // Decrement active tasks (to stop icons glowing if no pending task ;; if counter is 0)
    switch(execution.commandId){
      case "compile":
        workingTasks.compile(workingTasks.compile()-1);
        pendingTasks.compile(pendingTasks.compile()-1);
        break;
      case "run":
        workingTasks.run(workingTasks.run()-1);
        pendingTasks.run(pendingTasks.run()-1);
        break;
      case "test":
        workingTasks.test(workingTasks.test()-1);
        pendingTasks.test(pendingTasks.test()-1);
        break;
    }

    ProcessedExecutionsStream.push(execution);
  }

  subTypeEventStream("BuildStructureChanged").each(function(message) {
    var projects = message.event.structure.projects;
    if (projects !== undefined && projects.length > 0) {
      reactivePlatform.reset();
      app.removeExistingProjects();

      $.each(projects, function(i, v) {
        app.projects.push(v.id.name);
      });

      // FIXME : is there any way to get the current project from the build structure?
      // Right now we just say that the first project in the list also is the current one.
      app.currentProject(app.projects()[0]);
    }
  });

  subTypeEventStream("RequestExecution").each(function(message) {
    debug && console.log("Received request execution result", message);

    var req = deferredRequests[message.serialId];
    if (req !== undefined) {
      delete deferredRequests[message.serialId];
      req.resolve({"result": message.result});
    }
  });

  subTypeEventStream("CancelExecution").each(function(message) {
    debug && console.log("Received cancel execution result", message);

    var req = deferredRequests[message.serialId];
    if (req !== undefined) {
      delete deferredRequests[message.serialId];
      req.resolve({"result": message.result});
    }
  });

  subTypeEventStream("PossibleAutoCompletions").each(function(message) {
    debug && console.log("Received possible auto completions", message);

    var pac = deferredRequests[message.serialId]
    if (pac !== undefined) {
      delete deferredRequests[message.serialId];
      pac.resolve(
        message.result.map(function(completion) {

          var command = message.partialCommand + completion.append;
          var redirected;

          // Hard-coded replacement of run -> backgroundRun
          if (command === "run") {
            redirected = runCommand();
            command = "run";
          } else if (command.slice(0,7) === "runMain") {
            redirected = "backgroundRunMain" + command.slice(7);
            command = "runMain";
          }

          return {
            title: completion.display,
            subtitle: "run sbt task " + completion.display,
            type: "Sbt",
            url: false,
            execute: command,
            redirected: redirected,
            callback: function () {
              if (redirected) {
                requestExecution(redirected);
              } else {
                requestExecution(command);
              }
              window.location.hash = "#build";
            }
          }
        })
      );
    }
  });

  var valueChanged = subTypeEventStream("ValueChanged").map(function(message) {
    var valueOrNull = null;
    if (message.event.value.$type.indexOf("Success") >= 0)
      valueOrNull = message.event.value.value.serialized;
    debug && console.log("ValueChanged for ", message.event.key.key.name, valueOrNull, message.event);
    return {
      key: message.event.key.key.name,
      value: valueOrNull,
      // TODO insert a project object instance from our projects list ?
      //project: message.event.key.scope.project,
      scopedKey: message.event.key
    }
  });

  // discoveredMainClasses
  valueChanged.matchOnAttribute('key', 'discoveredMainClasses').each(function(message) {
    var discovered = [];
    if (message.value && message.value.length)
      discovered = message.value;
    // TODO this is broken, if there are two projects with main classes we'll just
    // pick "last one wins," we need to separately track main classes per-project.
    app.mainClasses(discovered); // All main classes
    if (!app.currentMainClass() && discovered[0]){
      app.currentMainClass(discovered[0]); // Selected main class, if empty
    }
  });

  // Inspect-related (sbt-echo) observables.
  //
  // FIXME these need to be tracked separately for each project.
  // FIXME logically these go in run.js, but they can't go there
  // because it loads lazily so would miss ValueChanged events.

  // Note: this is whether inspect WORKS on the project;
  // It may not be enabled by the user.
  var inspectSupported = ko.observable(false);
  var inspectAkkaVersionReport = ko.observable("");
  var inspectPlayVersionReport = ko.observable("");
  var inspectHasPlayVersion = ko.observable(false);
  var whyInspectIsNotSupported = ko.computed(function() {
    if (inspectSupported())
      return "";
    else if (inspectHasPlayVersion())
      return inspectPlayVersionReport();
    else if (inspectAkkaVersionReport() !== "")
      return inspectAkkaVersionReport();
    else
      return "The sbt-echo plugin may not be present on this project or may not be enabled.";
  });

  if (debug) {
    whyInspectIsNotSupported.subscribe(function(why) {
      if (inspectSupported())
        console.log("Inspect is supported");
      else
        console.log("Inspect is not supported because ", why);
    });
  }

  valueChanged.matchOnAttribute('key', 'echoTraceSupported').each(function(message) {
    debug && console.log('echoTraceSupported',message);
    inspectSupported(message.value === true);
  });

  valueChanged.matchOnAttribute('key', 'echoAkkaVersionReport').each(function(message) {
    debug && console.log('echoAkkaVersionReport',message);
    var report = "";
    if (message.value)
      report = message.value;
    inspectAkkaVersionReport(report);
  });

  valueChanged.matchOnAttribute('key', 'echoPlayVersionReport').each(function(message) {
    debug && console.log('echoPlayVersionReport',message);
    var report = "";
    if (message.value)
      report = message.value;
    inspectPlayVersionReport(report);
  });

  valueChanged.matchOnAttribute('key', 'echoTracePlayVersion').each(function(message) {
    debug && console.log('echoTracePlayVersion',message);
    if (message.value && message.value !== '')
      inspectHasPlayVersion(true);
    else
      inspectHasPlayVersion(false);
  });

  // mainClass
  valueChanged.matchOnAttribute('key', 'mainClass').each(function(message) {
    app.mainClass(message.value);
  });

  // Did we load build or fail to - they always have opposite
  // values except *before* we've loaded/failed to load
  // the build when both are false.
  // We reset them on losing client connection.
  var buildReady = ko.observable(false);
  var buildFailed = ko.observable(false);

  // Are we connected to sbt server?
  var clientReady = ko.observable(false);
  // Do we have main classes (meaning we've compiled
  // successfully)? TODO rename this to haveMainClasses
  // or something, and check that where we use it
  // makes sense.
  var applicationReady = ko.computed(function() {
    return (app.mainClasses().length || app.mainClass() !== null) && clientReady();
  });
  var applicationNotReady = ko.computed(function() { return !applicationReady(); });
  subTypeEventStream('ClientOpened').each(function (msg) {
    clientReady(true);
    // reset the build flags
    buildReady(false);
    buildFailed(false);
  });
  subTypeEventStream('ClientClosed').each(function (msg) {
    app.mainClasses([]);
    app.mainClass(null);
    clientReady(false);
  });

  subTypeEventStream("BuildLoaded").each(function(message) {
    buildReady(true);
    buildFailed(false);
  });
  subTypeEventStream("BuildFailedToLoad").each(function(message) {
    buildReady(false);
    buildFailed(true);
  });


  // Killing an execution
  function stopJob(message) {
    if (message.event && message.event.command && message.event.command.slice(0, 7) === "jobStop") {
      var id = message.event.command.slice(8);
      if (executionsById[id]) executionsById[id].stopping(true);
      return true;
    } else {
      return false;
    }
  }

  /**
  Execution object constructor
  */
  function Execution(message) {
    var self = this;
    if (message.event.command[0] === "{"){
      // Get rid of {file://path/to/project} in task names
      message.event.command = message.event.command.replace(/\{.*\}/ig, "");
    }

    self.executionId = message.event.id;
    self.command     = message.event.command;
    self.commandId   = message.event.command.split(/[:\ ]/)[0];
    self.started     = ko.observable(0);
    self.finished    = ko.observable(0); // 0 here stands for no Date() object, yet
    self.finished.extend({ notify: 'always' });
    self.succeeded   = ko.observable();
    self.stopping    = ko.observable(false);
    self.read        = ko.observable(false);
    self.jobIds      = ko.observableArray([]);

    var isRun = /^([a-z]+:)?(run|runMain\ .+|backgroundRunMain\ .+|backgroundRun)$/ig;
    if (isRun.test(self.command)) self.commandId = "run";

    // Data produced:
    self.tasks          = {};
    // true if during the execution we see a compile task
    self.changedCompileResult = false;
    // if this is non-empty, then changedCompileResult ought to end up true...
    self.compilationErrors  = ko.observableArray([]);
    self.testResults    = ko.observableArray([]);

    // Statuses
    self.running = ko.computed(function() {
      return !self.finished();
    });
    self.error = ko.computed(function() {
      return self.finished() && !self.succeeded();
    });
    self.time = ko.computed(function() {
      if (self.finished() && self.started()){
        var time = Math.round((self.finished() - self.started()) /1000) +" s";
        var status = self.stopping()||self.jobIds().length?"Stopped after":self.succeeded()?"Completed in":"Failed after";
        return status +" "+ time;
      } else if (self.jobIds().length){
        return "Running in background";
      } else if (self.stopping()) {
        return "Stopping the task...";
      } else if (self.started()) {
        return "Running for " + Math.round((new Date() - self.started()) /1000) +" s";
      } else {
        return "Pending...";
      }
    });

    // Update counters in UI
    (function timer() {
      if (!self.finished()){
        self.finished(0); // Force the update of the counter
        setTimeout(timer, 100)
      }
    }());
  }

  function Task(message) {
    var self = this;
    self.executionId = message.event.executionId;
    self.taskId = message.event.taskId;
    self.key = message.event.key ? message.event.key.key.name : null;
    self.finished = ko.observable(0); // 0 here stands for no Date() object
    self.succeeded = ko.observable(0); // 0 here stands for no Date() object
  }


  /**
  Kill tasks by command name (or all pending tasks)
  */
  function killTask(task) {
    executions().filter(function(execution) {
      return !execution.finished() && (execution.jobIds().length || (!task || execution.command === task));
    }).forEach(killExecution);
  }
  function killExecution(execution) {
    if (execution.jobIds().length){
      execution.jobIds().forEach(function(id) {
        requestExecution("jobStop "+id);
      });
    } else {
      cancelExecution(execution.executionId);
    }
  }

  // Helper for stopping run
  function stopRun() {
    pendingTasks.stoppingRun(true);
    killTask("run");
  }

  $("body").on("click","button[data-exec]",function() {
    var command = $(this).attr('data-exec');
    if (command === "run"){
      command = runCommand();
    }
    if (command) {
      requestExecution(command);
    }
  });

  return {
    sbtRequest:              sbtRequest,
    deferredPossibleAutoCompletions: deferredPossibleAutoCompletions,
    requestExecution:        requestExecution,
    requestDeferredExecution: requestDeferredExecution,
    executions:              executions,
    findCommandByExecutionId:   findCommandByExecutionId,
    findExecutionIdByTaskId: findExecutionIdByTaskId,
    workingTasks:            workingTasks,
    pendingTasks:            pendingTasks,
    testResults:             testResults,
    compilationErrors:       compilationErrors,
    testErrors:              testErrors,
    taskCompleteEvent:       taskCompleteEvent,
    ProcessedExecutionsStream:               ProcessedExecutionsStream,
    kill:                    killExecution,
    clientReady:             clientReady,
    buildReady:              buildReady,
    buildFailed:             buildFailed,
    applicationReady:        applicationReady,
    applicationNotReady:     applicationNotReady,
    isPlayApplication:       isPlayApplication,
    playApplicationUrl:      playApplicationUrl,
    playServerStarted:        playServerStarted,
    inspectSupported:        inspectSupported,
    whyInspectIsNotSupported: whyInspectIsNotSupported,
    reactivePlatform:        reactivePlatform,
    active: {
      turnedOn:     "",
      compiling:    "",
      running:      "",
      testing:      ""
    },
    actions: {
      kill:         killTask,
      stopRun:      stopRun,
      compile:      function() {
        requestExecution("compile");
      },
      run:          function() {
        if (app.settings.automaticResetInspect()){
          resetInspect();
        }
        if (monitoringSolutions.inspectActivated() && !inspectSupported()) {
          // Disactivating
          monitoringSolutions.monitoringSolution(monitoringSolutions.NO_MONITORING);
          // Show a popup
          modals.show({
            title: "Inspect has been deactivated",
            text: whyInspectIsNotSupported(),
            cancel: "close"
          });
        }
        return requestExecution(runCommand());
      },
      test:         function() {
        requestExecution("test");
      },
      resetInspect: resetInspect
    }
  }

});

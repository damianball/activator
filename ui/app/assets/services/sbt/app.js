/*
 Copyright (C) 2014 Typesafe, Inc <http://typesafe.com>
 */
define([
  "commons/settings"
], function(
  settings
) {

  var projects = ko.observableArray([]);
  var currentProject =  ko.observable("");
  var removeExistingProjects = function() {
    this.currentProject();
    this.projects([]);
  };

  var mainClass = ko.observable(null);
  var mainClasses = ko.observable([]);

  return {
    name:               "",
    id:                 "",
    location:           "",
    projects:           projects,
    currentProject:     currentProject,
    removeExistingProjects: removeExistingProjects,
    mainClass:          mainClass,
    mainClasses:        mainClasses,
    currentMainClass:   settings.observable("build.mainClass-"+serverAppModel.id, false), //currentMainClass,
    versions: {
      scala:            ko.observable(false),
      akka:             ko.observable(false),
      play:             ko.observable(false),
      spray:            ko.observable(false),
      slick:            ko.observable(false)
    },
    settings: {
      rerunOnBuild:             settings.observable("build.rerunOnBuild", false),
      retestOnSuccessfulBuild:  settings.observable("build.retestOnSuccessfulBuild", false),
      automaticResetInspect:    settings.observable("build.automaticResetInspect", false),
      recompileOnChange:        settings.observable("build.recompileOnChange", true),
      showLogDebug:             settings.observable("build.showLogDebug", false)
    },
    deviationPrefs: {
      showSystemMessages:       settings.observable("inspect.deviation.showSystemMessages",false),
      showNanoSeconds:          settings.observable("inspect.deviation.showNanoSeconds",false),
      showActorSystems:         settings.observable("inspect.deviation.showActorSystems",false),
      showTraceInformation:     settings.observable("inspect.deviation.showTraceInformation",false)
    },
    customCommands:     ko.observableArray([])
  }

});

/*
 Copyright (C) 2013 Typesafe, Inc <http://typesafe.com>
 */
define([
  'services/sbt',
  'services/typesafe',
  'text!./welcome.html',
  'commons/settings',
  'widgets/layout/layout',
  'widgets/layout/layoutManager',
  'css!./welcome.css'
], function(
  sbt,
  typesafe,
  tpl,
  settings,
  layout,
  layoutManager
){

  var presentationModeStyle = (function() {
    var added = false;
    var cssStr = ".presentation-mode .tutorial .page, .presentation-mode .build .logs, .presentation-mode .run .pluginBlock, .presentation-mode .code .editor, .presentation-mode .monitoring .tabs-step { zoom: 161% !important; }";
    var style;

    try {
      // Create the <style> tag
      style = document.createElement("style");
      style.appendChild(document.createTextNode(cssStr));
    } catch(e){
      style = undefined;
    }

    return function() {
      if (!added && style) {
        document.head.appendChild(style);
        added = true;
      }
    }
  }());

  var WelcomeState = (function(){
    var self = {};

    self.remoteAppVersion = typesafe.getActivatorInfo();
    self.appVersion = window.serverAppVersion;
    self.currentStatus = sbt.events.appStatus;

    self.trp = sbt.tasks.reactivePlatform.platformRelease;

    self.presentationMode = settings.observable("presentationMode", false);

    self.newVersion = ko.computed(function (){
      var info = self.remoteAppVersion();
      var result = false;
      if (info && info.type === "activatorInfo") {
        if (info.data.version !== window.serverAppVersion) {
          result = true;
        }
      }
      return result;
    });

    ko.computed(function() {
      var on = self.presentationMode();
      if (on) {
        document.body.style.zoom = "75%";
        document.body.classList.add("presentation-mode");
        presentationModeStyle();
      } else {
        document.body.style.zoom = "1";
        document.body.classList.remove("presentation-mode");
      }
      layoutManager.panelOpenedSet(false);
      layoutManager.navigationOpened(!on);
      return;
    });

    return self;
  }());

  return {
    render: function() {
      layout.renderPlugin(ko.bindhtml(tpl, WelcomeState));
    }
  }

});

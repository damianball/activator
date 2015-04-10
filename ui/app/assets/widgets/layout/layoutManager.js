/*
 Copyright (C) 2014 Typesafe, Inc <http://typesafe.com>
 */
define([
  'commons/settings',
  'main/router',
  'css!./layoutManager'
], function(
  settings,
  router
) {


  var panelShape =        settings.observable("layoutManager.panelShape", "right1");
  var panelOpenedSet =    settings.observable("layoutManager.panelOpened", false);
  var navigationOpened =  settings.observable("layoutManager.navigationOpened", true);
  var bannerOpened =      ko.observable(false);

  var panelWidth  = settings.observable("layoutManager.panelWidth", 350);
  var panelHeight = settings.observable("layoutManager.panelHeight", 300);

  var resizing = ko.observable(false);
  resizing.subscribe(function(r){
    var all = $("#panels, #app, main");
    if (r) {
      all.css({ // Remove css transition during resize
        "webkitTransition": "none",
        "mozTransition": "none",
        "transition": "none"
      });
    } else {
      all.css({ // Reset css transition
        "webkitTransition": "",
        "mozTransition": "",
        "transition": ""
      })
    }
  });

  var panelOpened = ko.computed(function() {
    return panelOpenedSet() && router.current().id !== "tutorial";
  });

  function startLayout(){
    ko.computed(function(v){
      var opened = panelOpenedSet();
      var shape = panelShape()[0] === "r";
      var w = panelWidth();
      $("#panels").css('width',shape?w:"auto");
      $("#app").css('right',opened && shape?w:1);
    });

    ko.computed(function(v){
      var opened = panelOpenedSet();
      var shape = panelShape()[0] === "b";
      var h = panelHeight();
      $("#panels").css('height',shape?h:"auto");
      $("#app").css('bottom',opened && shape?h:1);
    });
  }

  var State = {
    resizing: resizing,
    panelWidth: panelWidth,
    panelHeight: panelHeight,
    panelShape: panelShape,
    panelOpenedSet: panelOpenedSet,
    panelOpened: panelOpened,
    navigationOpened: navigationOpened,
    bannerOpened: bannerOpened,
    startLayout: startLayout,

    panelChange: function (o, e) {
      panelShape(e.target.className);
      panelOpened(true);
    },
    panelToggle: function () {
      if (router.current().id === "tutorial" && !panelOpenedSet()) {
        window.location.hash = "#build"
      }
      panelOpenedSet(!panelOpenedSet());
    },
    navigationToggle: function () {
      navigationOpened(!navigationOpened());
    },
    bannerToggle: function () {
      bannerOpened(!bannerOpened());
    },
    togglePanelOptions: function (o, e) {
      e.preventDefault();
      e.stopPropagation();
      $("#layoutManager dd").toggle();
    },
    closePanelOptions: function () {
      $("#layoutManager dd").hide();
    }
  };

  return State;

});

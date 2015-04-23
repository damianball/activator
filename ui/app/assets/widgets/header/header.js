/*
 Copyright (C) 2014 Typesafe, Inc <http://typesafe.com>
 */
define([
  'services/sbt',
  'commons/websocket',
  'widgets/omnisearch/omnisearch',
  'widgets/breadcrumb/breadcrumb',
  'widgets/layout/layoutManager',
  'widgets/notifications/notifications',
  'widgets/help/help',
  'text!./header.html',
  'css!./header'
], function(
  sbt,
  websocket,
  omnisearch,
  breadcrumb,
  layoutManager,
  notifications,
  help,
  tpl
){
  var showBanner = ko.computed(function () {
    var irpp = sbt.tasks.reactivePlatform.isReactivePlatformProject();
    var pfe = sbt.tasks.reactivePlatform.propertiesFileExists();
    var sid = sbt.tasks.reactivePlatform.subscriptionId();
    return irpp && (!pfe || !sid);
  });

  var remedy = function () {
    sbt.tasks.reactivePlatform.typesafeIdFormVisible(true);
  };

  var bannerMessage = ko.computed(function () {
    if (showBanner()) {
      if (!sbt.tasks.reactivePlatform.propertiesFileExists()) {
        return "missingProperties"
      } else if (!sbt.tasks.reactivePlatform.subscriptionId()) {
        return "missingId"
      } else {
        return "noError";
      }
    } else {
      return "noError";
    }
  });

  showBanner.subscribe(function (v) {
    layoutManager.bannerOpened(v);
  });

  var State = {
    omnisearch: omnisearch,
    breadcrumb: breadcrumb,
    layoutManager: layoutManager,
    notifications: notifications,
    bannerMessage: bannerMessage,
    remedy: remedy,
    help: help
  };

  return ko.bindhtml(tpl, State);

});

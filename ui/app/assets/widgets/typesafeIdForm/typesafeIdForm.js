/*
 Copyright (C) 2014 Typesafe, Inc <http://typesafe.com>
 */
define([
  'services/sbt',
  'commons/websocket',
  'services/typesafe',
  'text!./typesafeIdForm.html',
  'css!./typesafeIdForm',

],function(
  sbt,
  websocket,
  typesafe,
  tpl
) {
  var FormState = (function(){
    var self = {};
    self.dummyRecompute = ko.observable(1);
    self.forceIdCheck = function () {
      var x = self.dummyRecompute();
      self.dummyRecompute(x + 1);
    };
    self.visible = sbt.tasks.reactivePlatform.typesafeIdFormVisible;
    self.typesafeId = ko.observable(sbt.tasks.reactivePlatform.typesafeId());
    self.lengthOk = ko.computed(function () {
      var id = self.typesafeId();
      return (id && (id.length === 36));
    });
    self.okEnabled = ko.computed(function () {
      if (!self.lengthOk()) {
        return false;
      } else {
        switch(self.acceptedTrpState()) {
          case "ok":
            return true;
          default:
            return false;
        }
      }
    });
    self.getIDFromTypesafeCom = function() {
      var obs = typesafe.getSubscriptionDetail();
      obs.subscribe(function (v) {
        if (v.type === "subscriptionDetails") {
          self.typesafeId(v.data.id);
        }
      });
    };
    self.clickCancel = function() {
      self.visible(false);
    };
    self.clickOk = function() {
      sbt.tasks.reactivePlatform.typesafeId(self.typesafeId());
      self.visible(false);
      var msg = {request: 'WriteTypesafeProperties'};
      msg.subscriptionId = self.typesafeId();
      websocket.send(msg);
    };
    self.checkId = function() {
      self.forceIdCheck();
    };
    self.runningRequest = ko.observable(null);
    self.acceptedTrpState = ko.computed(function () {
      self.dummyRecompute();
      if (self.lengthOk()) {
        if (!self.runningRequest()) {
          self.runningRequest(typesafe.checkSubscriptionId(self.typesafeId()));
        }
        var r = self.runningRequest();
        if (r()) {
          var result = r();
          if (result.type ===  "fromTypesafeCom") {
            self.runningRequest(null);
            if (result.data.idCheckResult === "valid") {
              if (result.data.acceptedDate) {
                if (result.data.acceptedDate < result.data.latestTermsDate) {
                  return "needsUpdate";
                } else {
                  return "ok";
                }
              } else {
                return "needsAccept";
              }
            } else if (result.data.idCheckResult === "invalid") {
              return "invalid";
            }
          } else if (result.type === "proxyFailure") {
            self.runningRequest(null);
            return "error";
          }
        } else {
          return "checking";
        }
      } else {
        return "unknown";
      }
    });
    self.typesafeIdError = ko.computed(function() {
      switch(self.acceptedTrpState()) {
        case "needsUpdate":
          return "Updated terms for the <a href='https://www.typesafe.com/account/id' target='_blank'>Typesafe Subscription Agreement</a> are available.  Please agree and continue.";
        case "needsAccept":
          return "You need to accept the terms of the <a href='https://www.typesafe.com/account/id' target='_blank'>Typesafe Subscription Agreement</a> to continue.";
        case "invalid":
          return "The Typesafe Subscription ID you have provided is invalid.  Please correct to continue.";
        case "error":
          return "Unable to check the status of your Typesafe Subscription ID";
        default:
          return "";
      }
    });
    self.errorVisible = ko.computed(function() {
      switch(self.acceptedTrpState()) {
        case "needsUpdate":
        case "needsAccept":
        case "invalid":
        case "error":
          return true;
        default:
          return false;
      }
    });
    self.continueVisible = ko.computed(function() {
      switch(self.acceptedTrpState()) {
        case "needsUpdate":
        case "needsAccept":
          return true;
        default:
          return false;
      }
    });
    self.getIDFromTypesafeComVisible = ko.computed(function () {
      return (!self.okEnabled() && !self.errorVisible());
    });
    self.typesafeIdStateClass = ko.computed(function () {
      if (!self.lengthOk()) {
        return "invalid-id";
      } else {
        switch(self.acceptedTrpState()) {
          case "ok":
            return "valid-id";
          case "checking":
            return "checking-id";
          default:
            return "invalid-id";
        }
      }
    });
    return self;
  }());

  return ko.bindhtml(tpl,FormState);
});

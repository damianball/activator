/*
 Copyright (C) 2014 Typesafe, Inc <http://typesafe.com>
 */
define([
  'widgets/login/login',
  'widgets/error/error',
  'commons/websocket',
  'commons/stream'
], function(
  login,
  error,
  websocket,
  Stream
){

  var proxyEventStream = websocket.subscribe('tag','TypesafeComProxy');

  function pseudoUniqueId() {
    return 'id-' + Math.random().toString(36).substr(2, 16);
  }

  // Open login popup:
  // login(sendCredentials, sendCancel);
  // Reopen login, with error:
  // login(sendCredentials, sendCancel, "Can't Login");

  proxyEventStream.each(function(message) {
    var type = message.type;
    var state = {
      type: type
    };
    if (typeof message.actorPath !== 'undefined' && message.actorPath !== null) {
      state.actorPath = message.actorPath;
    }
    if (typeof message.actionId !== 'undefined' && message.actionId !== null) {
      state.actionId = message.actionId;
    }
    if (type === "requestCredentials") {
      if (message.message) {
        state.message = message.message;
      }
      state.cancel = function () {
        sendCancel(state.actorPath);
      };
      state.credentials = function (username,password) {
        sendCredentials(username,password,state.actorPath);
      };
    } else if (type === "reportStartAction") {
      state.message = message.message;
      state.cancel = function () {
        sendCancel(state.actorPath);
      };
    } else if (type === "reportEndAction") {
      state.message = message.message;
    } else if (type === "failure") {
      state.message = message.message;
      if (message.retryable === true) {
        state.cancel = function () {
          sendCancel(state.actorPath);
        };
        state.retry = function () {
          sendRetry(state.actorPath);
        };
      }
    } else {
      state = null;
    }

    if (state) {
      proxyUiRequestState(state);
    }
  });

  var proxyUiRequestState = ko.observable(null);
  proxyUiRequestState.subscribe(function (state) {
    if (state.type === "requestCredentials") {
      login(state.credentials, state.cancel, state.message);
    } else if (state.type === "failure") {
      error("Error",state.message,state.retry,state.cancel);
    }
  });

  function proxyRequest(type, payload) {
    var request = {
      "tag" : "TypesafeComProxy",
      "type" : type
    };

    if (payload) {
      request = $.extend(request,payload);
    }

    websocket.send(request);
  }

  function getSubscriptionDetail() {
    var id = pseudoUniqueId();
    var response = ko.observable();
    var subs = websocket.subscribe('tag','TypesafeComProxy');
    proxyRequest("getSubscriptionDetail",{requestId: id});
    subs.each(function(message) {
      var type = message.type;
      var requestId = null;
      if (typeof message.requestId !== 'undefined' && message.requestId !== null) {
        requestId = message.requestId;
      }

      if (requestId === id) {
        if (type === "notASubscriber") {
          response(message);
        } else if (type === "subscriptionDetails") {
          response(message);
        } else if (type === "proxyFailure") {
          response(message);
        }
      }
    });

    response.subscribe(function(newValue) {
      subs.close();
    });
    return response;
  }

  function getJsonFromTypesafeCom(path) {
    var id = pseudoUniqueId();
    var response = ko.observable();
    var subs = websocket.subscribe('tag','TypesafeComProxy');
    proxyRequest("getFromTypesafeCom",{requestId: id, path: path});
    subs.each(function(message) {
      var type = message.type;
      var requestId = null;
      if (typeof message.requestId !== 'undefined' && message.requestId !== null) {
        requestId = message.requestId;
      }

      if (requestId === id) {
        if (type === "fromTypesafeCom") {
          response(message);
        } else if (type === "proxyFailure") {
          response(message);
        }
      }
    });

    response.subscribe(function(newValue) {
      subs.close();
    });
    return response;
  }


  function checkSubscriptionId(id) {
    return getJsonFromTypesafeCom("product/typesafe-reactive-platform/api/idCheck/"+id);
  }

  function getActivatorInfo() {
    var id = pseudoUniqueId();
    var response = ko.observable(null);
    var subs = websocket.subscribe('tag','TypesafeComProxy');
    proxyRequest("getActivatorInfo",{requestId: id});
    subs.each(function(message) {
      var type = message.type;
      var requestId = null;
      if (typeof message.requestId !== 'undefined' && message.requestId !== null) {
        requestId = message.requestId;
      }

      if (requestId === id) {
        if (type === "activatorInfo") {
          response(message);
        } else if (type === "proxyFailure") {
          response(message);
        }
      }
    });

    response.subscribe(function(newValue) {
      subs.close();
    });
    return response;
  }


  function sendCancel(actorPath) {
    proxyRequest("cancel",{actorPath: actorPath});
  }

  function sendCredentials(username,password, actorPath) {
    proxyRequest("credentials",{username: username, password: password, actorPath: actorPath});
  }

  function sendRetry(actorPath) {
    proxyRequest("retry",{actorPath: actorPath});
  }


  var register = {};
  var receiveMessage = function(event) {
    if (event.origin === "https://typesafe.com") { // TODO change to typesafe.com
      var obj = JSON.parse(event.data);
      debug && console.log("received message:", obj);
      for (var eventType in register){
        if (eventType in obj) {
          for (var i in register[eventType]) {
            register[eventType][i](obj[eventType]);
          }
        }
      }
    }
  };

  window.addEventListener("message", receiveMessage, false);

  function subscribe(label, callback){
    register[label] = register[label] || [];
    register[label].push(callback);
  }

  function send(target, msg){
    target.postMessage(JSON.stringify(msg), "https://typesafe.com");
  }

  return {
    subscribe: subscribe,
    send: send,
    getSubscriptionDetail: getSubscriptionDetail,
    getActivatorInfo: getActivatorInfo,
    proxyUiRequestState: proxyUiRequestState,
    sendCredentials: sendCredentials, // useful for debugging
    getJsonFromTypesafeCom: getJsonFromTypesafeCom,
    checkSubscriptionId: checkSubscriptionId
  }

});

/*
 Copyright (C) 2014 Typesafe, Inc <http://typesafe.com>
 */
define([
  'services/sbt',
  'text!./appManager.html',
  'css!./appManager'
],function(
  sbt,
  tpl
){

  return ko.bindhtml(tpl, {
    trp: sbt.tasks.reactivePlatform.platformRelease
  });

});

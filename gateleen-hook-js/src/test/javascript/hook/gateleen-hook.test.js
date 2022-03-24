/*
 * ------------------------------------------------------------------------------------------------
 * Copyright 2015 by Swiss Post, Information Technology Services
 * ------------------------------------------------------------------------------------------------
 */

describe('hook', function(){
  'use strict';

  var $rootScope,
    Hook,
    eventBus,
    $window,
    $interval,
    $timeout,
    _originalEventBus,
    $httpBackend,
    spies;

  beforeEach(module('gateleen.hook'));
  beforeEach(inject(function(_$rootScope_, _$httpBackend_, _Hook_, _$window_, _$interval_, _$timeout_){
    $rootScope = _$rootScope_;
    $httpBackend = _$httpBackend_;
    $httpBackend.whenGET('core/rest/translations/de/messages.json').respond({});

    $window = _$window_;
    $interval = _$interval_;
    $timeout = _$timeout_;
    Hook = _Hook_;
    spyOn(Math, 'random').and.returnValue(0.123);
    _originalEventBus = $window.vertx.EventBus;
    $window.vertx.EventBus = function() {
      eventBus = jasmine.createSpyObj('eventBus', [ 'onopen', 'onclose', 'close', 'registerHandler', 'unregisterHandler']);
      return eventBus;
    };
    var i=0;
    spies=[];
    spies.push(jasmine.createSpy(''+(i++)));
    spies.push(jasmine.createSpy(''+(i++)));
    spies.push(jasmine.createSpy(''+(i)));
  }));
  afterEach(function() {
    $window.vertx.EventBus = _originalEventBus;
  });

  describe('registering listener', function(){
    it('should open event bus', function(){
      Hook.listen('/context/path', function() {});
      expect(eventBus.onopen).toBeDefined();
    });
    it('should register an event bus handler', function() {
      Hook.listen('/context/path', function() {});
      eventBus.onopen();
      expect(eventBus.registerHandler).toHaveBeenCalled();
    });
    it('should create the hook with HTTP PUT from context path', function(){
      $httpBackend.expectPUT('/context/path/_hooks/listeners/http/gateleen-hook-js-0123',
        {
          'methods':['PUT', 'POST' ],
          'destination':'/context/server/event/v1/channels/gateleen-hook-js-0123',
          'expireAfter':10,
          'headers': [
            {
              'header':'x-queue-mode',
              'value':'transient'
            }
          ]
        }
      ).respond(200,'OK');
      Hook.listen('/context/path', function() {});
      $httpBackend.flush();
      $httpBackend.verifyNoOutstandingExpectation();
      $httpBackend.verifyNoOutstandingRequest();
    });
    it('should refresh hook after 100 seconds', function(){
      $httpBackend.expectPUT('/context/path/_hooks/listeners/http/gateleen-hook-js-0123').respond(200,'OK');
      Hook.listen('/context/path', function() {});
      $httpBackend.flush();
      $httpBackend.resetExpectations();
      $httpBackend.expectPUT('/context/path/_hooks/listeners/http/gateleen-hook-js-0123').respond(200,'OK');
      $interval.flush(110000);
      $httpBackend.flush();
      $httpBackend.verifyNoOutstandingExpectation();
      $httpBackend.verifyNoOutstandingRequest();
    });
  });

  describe('removing listener', function() {
    it('should remove hook with HTTP DELETE', function(){
      $httpBackend.expectPUT('/context/path/_hooks/listeners/http/gateleen-hook-js-0123').respond(200,'OK');
      var remove = Hook.listen('/context/path', function() {});
      $httpBackend.expectDELETE('/context/path/_hooks/listeners/http/gateleen-hook-js-0123').respond(200,'OK');
      remove();
      $httpBackend.flush();
      $httpBackend.verifyNoOutstandingExpectation();
      $httpBackend.verifyNoOutstandingRequest();
    });
    it('should remove handler but without open event bus not deregistering it', function(){
      var remove = Hook.listen('/context/path', function() {});
      remove();
      expect(eventBus.unregisterHandler).not.toHaveBeenCalled();
    });
    it('should remove handler and deregister on the event bus', function(){
      var remove = Hook.listen('/context/path', function() {});
      eventBus.onopen();
      remove();
      expect(eventBus.unregisterHandler).toHaveBeenCalled();
    });
    it('should close the event bus if it is the last one', function () {
      var remove1 = Hook.listen('/context/path1', spies[0]);
      var remove2 = Hook.listen('/context/path1/bla', spies[1]);
      eventBus.onopen();
      remove1();
      expect(eventBus.close).not.toHaveBeenCalled();
      remove2();
      expect(eventBus.close).toHaveBeenCalled();
    });
  });

  describe('single fetch', function() {
    it('should issue a GET after some time', function() {
      $httpBackend.expectPUT('/context/path/_hooks/listeners/http/gateleen-hook-js-0123').respond(200,'OK');
      $httpBackend.expectGET('/context/path').respond(200, { text: 'hello'});
      Hook.listen('/context/path', function() {}, { fetch: 'single'});
      eventBus.onopen();
      $timeout.flush(1000);
      $httpBackend.flush();
      $httpBackend.verifyNoOutstandingExpectation();
      $httpBackend.verifyNoOutstandingRequest();
    });

    it('should queue requests until GET is finished', function() {
      $httpBackend.expectPUT('/context/path/_hooks/listeners/http/gateleen-hook-js-0123').respond(200,'OK');
      var spy = jasmine.createSpy('handler');
      Hook.listen('/context/path', spy, { fetch: 'single'});
      eventBus.onopen();
      $timeout.flush(50);
      $httpBackend.flush();
      eventBus.registerHandler.calls.mostRecent().args[1]( { payload: { text: 'yo'} });
      expect(spy).not.toHaveBeenCalled();
    });

    it('should dequeue requests when GET is finished', function() {
      $httpBackend.expectPUT('/context/path/_hooks/listeners/http/gateleen-hook-js-0123').respond(200,'OK');
      var spy = jasmine.createSpy('handler');
      Hook.listen('/context/path', spy, { fetch: 'single'});
      eventBus.onopen();
      $timeout.flush(50);
      $httpBackend.flush();
      eventBus.registerHandler.calls.mostRecent().args[1]( { payload: { text: 'yo'} });
      $httpBackend.expectGET('/context/path').respond(200, { text: 'hello'});
      $timeout.flush(1000);
      $httpBackend.flush();
      expect(spy.calls.count()).toBe(2);
      expect(spy.calls.argsFor(0)[0].text).toEqual('hello');
      expect(spy.calls.argsFor(1)[0].text).toEqual('yo');
    });

    it('should call handler directly after usage of queue', function() {
      $httpBackend.expectPUT('/context/path/_hooks/listeners/http/gateleen-hook-js-0123').respond(200,'OK');
      var spy = jasmine.createSpy('handler');
      Hook.listen('/context/path', spy, { fetch: 'single'});
      eventBus.onopen();
      $timeout.flush(50);
      $httpBackend.flush();
      eventBus.registerHandler.calls.mostRecent().args[1]( { payload: { text: 'yo'} });
      $httpBackend.expectGET('/context/path').respond(200, { text: 'hello'});
      $timeout.flush(1000);
      $httpBackend.flush();
      eventBus.registerHandler.calls.mostRecent().args[1]( { payload: { text: 'hey'} });
      expect(spy.calls.count()).toBe(3);
      expect(spy.calls.argsFor(0)[0].text).toEqual('hello');
      expect(spy.calls.argsFor(1)[0].text).toEqual('yo');
      expect(spy.calls.argsFor(2)[0].text).toEqual('hey');
    });

    it('should support multiple listeners registered separately', function() {
      $httpBackend.expectPUT('/context/path/_hooks/listeners/http/gateleen-hook-js-0123').respond(200,'OK');
      $httpBackend.expectGET('/context/path').respond(200, { text: 'hello'});
      var spy = jasmine.createSpy('handler');
      var spy2 = jasmine.createSpy('handler');
      Hook.listen('/context/path', spy, { fetch: 'single'});
      eventBus.onopen();
      eventBus.registerHandler.calls.argsFor(0)[1]( { payload: { text: 'yo'} });
      $timeout.flush(1000);
      $httpBackend.flush();
      $httpBackend.expectPUT('/context/path/_hooks/listeners/http/gateleen-hook-js-0123').respond(200,'OK');
      Hook.listen('/context/path', spy2, { fetch: 'single'});
      eventBus.registerHandler.calls.argsFor(1)[1]( { payload: { text: 'ya'} });
      $httpBackend.expectGET('/context/path').respond(200, { text: 'hello'});
      $timeout.flush(1000);
      $httpBackend.flush();
      expect(spy.calls.count()).toBe(2);
      expect(spy.calls.argsFor(0)[0].text).toEqual('hello');
      expect(spy.calls.argsFor(1)[0].text).toEqual('yo');
      expect(spy2.calls.count()).toBe(2);
      expect(spy2.calls.argsFor(0)[0].text).toEqual('hello');
      expect(spy2.calls.argsFor(1)[0].text).toEqual('ya');
    });

    it('should support multiple listeners registered together', function() {
      $httpBackend.expectPUT('/context/path/_hooks/listeners/http/gateleen-hook-js-0123').respond(200,'OK');
      $httpBackend.expectPUT('/context/path/_hooks/listeners/http/gateleen-hook-js-0123').respond(200,'OK');
      var spy = jasmine.createSpy('handler');
      var spy2 = jasmine.createSpy('handler');
      Hook.listen('/context/path', spy, { fetch: 'single'});
      Hook.listen('/context/path', spy2, { fetch: 'single'});
      eventBus.onopen();
      $timeout.flush(50);
      $httpBackend.flush();
      eventBus.registerHandler.calls.argsFor(0)[1]( { payload: { text: 'yo'} });
      eventBus.registerHandler.calls.argsFor(1)[1]( { payload: { text: 'yo'} });
      $httpBackend.expectGET('/context/path').respond(200, { text: 'hello'});
      $httpBackend.expectGET('/context/path').respond(200, { text: 'hello'});
      $timeout.flush(1000);
      $httpBackend.flush();
      expect(spy.calls.count()).toBe(2);
      expect(spy.calls.argsFor(0)[0].text).toEqual('hello');
      expect(spy.calls.argsFor(1)[0].text).toEqual('yo');
      expect(spy2.calls.count()).toBe(2);
      expect(spy2.calls.argsFor(0)[0].text).toEqual('hello');
      expect(spy2.calls.argsFor(1)[0].text).toEqual('yo');
    });
  });

  describe('collection fetch', function() {
    it('should issue an expand GET', function(){
      $httpBackend.expectPUT('/context/items/_hooks/listeners/http/gateleen-hook-js-0123').respond(200,'OK');
      $httpBackend.expectGET('/context/items/?expand=1').respond(200, { items: { hello: { text: 'world'}, foo: { text: 'bar' } } });
      Hook.listen('/context/items', function() {}, { fetch: 'collection'});
      eventBus.onopen();
      $timeout.flush(1000);
      $httpBackend.flush();
      $httpBackend.verifyNoOutstandingExpectation();
      $httpBackend.verifyNoOutstandingRequest();
    });

    it('should call handler with collection content', function(){
      $httpBackend.expectPUT('/context/items/_hooks/listeners/http/gateleen-hook-js-0123').respond(200,'OK');
      var spy = jasmine.createSpy('handler');
      $httpBackend.expectGET('/context/items/?expand=1').respond(200, { items: { hello: { text: 'world'}, foo: { text: 'bar' } } });
      Hook.listen('/context/items/', spy, { fetch: 'collection'});
      eventBus.onopen();
      $timeout.flush(1000);
      $httpBackend.flush();
      expect(spy.calls.count()).toBe(2);
      expect(spy.calls.argsFor(0)[0].text).toEqual('world');
      expect(spy.calls.argsFor(1)[0].text).toEqual('bar');
    });

  });
});

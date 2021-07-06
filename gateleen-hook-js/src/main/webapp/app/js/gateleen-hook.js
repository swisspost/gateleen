(function () {
    'use strict';

    angular.module('gateleen.hook', [])

    /**********************************************************
     * Easy hook placing and handling.
     * @author bovetl
     */
        .factory('Hook', ['$timeout', '$interval', '$http', '$window', '$rootScope', HookService]);

    function HookService($timeout, $interval, $http, $window, $rootScope) {

        var
            hookRefreshInterval = 100000, // ms
            hookTimeToLive = 120, // s
            registrations = [],
            context,
            eventBus,
            open,
            pendingFetches = [];

        /**
         * Places a listener hooks to an URI path.
         * @param path The path to hook.
         * @param handler The function called upon hook trigger. It is called with the following arguments: (payload, [request]).
         * The payload argument is the request content mapped to the appropriate type: application/json => object, text/* => string, others => base64 string.
         * The request argument is the original request metadata that is { uri: string, headers: object, method: string }.
         * @options an object containing settings for this hook. { methods: array, fetch: 'single'|'collection' }.
         * The methods option defines for which methods the hook will be triggered. It defaults to [ 'PUT', 'POST' ].
         * The fetch options defines if the registration should trigger the handler with the current state of the hooked resource. Default: null.
         * @returns a function removing the hook.
         */
        function listen(path, handler, options) {
            console.debug('gateleen-hook-js listen');
            var id = ('gateleen-hook-js-' + Math.random()).replace('.', '');
            if (/\/$/.test(path)) {
                path = path.slice(0, -1);
            }
            var match = /(.*:\/\/.+?)?\/.+?\//.exec(path);
            context = match[0].substring(0, match[0].length - 1);
            var queue;
            if (options && options.fetch) {
                queue = [];
                pendingFetches.push(function () {
                    $timeout(function () {
                        var getPath = path;
                        if (options.fetch === 'collection') {
                            getPath = path + '/?expand=1';
                        }
                        $http.get(getPath).then(function (result) {
                            if (options.fetch === 'collection') {
                                var name = path.split('/').pop();
                                angular.forEach(result.data[name], function (v, k) {
                                    handler(v, {uri: path + '/' + k, headers: {}, method: 'PUT'});
                                });
                            } else {
                                //TODO: treat mimetypes
                                handler(result.data, {uri: path, headers: {}, method: 'PUT'});
                            }
                        }).finally(function () {
                            while (queue.length > 0) {
                                var request = queue.shift();
                                handler(request.payload, {
                                    uri: request.uri,
                                    headers: request.headers,
                                    method: request.method
                                });
                            }
                            queue = null;
                        });
                    }, 100);
                });
            }
            var registration = {
                path: path, address: 'event/channels/' + id, handler: function (request) {
                    if (queue) {
                        queue.push(request);
                    } else {
                        handler(request.payload, {
                            uri: request.uri,
                            headers: request.headers,
                            method: request.method,
                            channelId: id
                        });
                        $rootScope.$digest();
                    }
                }
            };
            registrations.push(registration);
            if (open) {
                eventBus.registerHandler(registration.address, registration.handler);
                if (pendingFetches.length > 0) {
                    pendingFetches.pop()();
                }
            } else {
                if (!eventBus) {
                    initEventBus(); // This will register handlers and run pending fetches as well
                }
            }
            var hookUrl = path + '/_hooks/listeners/http/' + id;
            var refresh = function () {
                $http.put(hookUrl, {
                    methods: (options && options.methods) || ['PUT', 'POST'],
                    destination: context + '/server/event/v1/channels/' + id,
                    expireAfter: 10, // notifications are queued for max 10 seconds
                    headers: [
                        {
                            'header': 'x-queue-mode',
                            'value': 'transient'
                        }
                    ],
                    filter: (options && options.filter)
                }, {
                    headers: {
                        'x-expire-after': hookTimeToLive
                    }
                }).then(function () {
                    console.debug('Placed hook', hookUrl);
                }, function (e) {
                    console.error('Could not place hook', hookUrl, e);
                });
            };
            var interval = $interval(refresh, hookRefreshInterval, 0, false);
            refresh();
            return function () { // the unregistering function
                var index = registrations.indexOf(registration);
                if (index > -1) {
                    $interval.cancel(interval);
                    registrations.splice(index, 1);
                    if (open) { // unregisterHandler while bus is not open is not possible
                        eventBus.unregisterHandler(registration.address, registration.handler);
                        if (registrations.length === 0) { // if last listener, then release event bus
                            eventBus.close();
                            eventBus = null;
                        }
                    }
                    $http.delete(hookUrl); // if it fails, this is not critical since the hook will expire
                }
            };
        }

        function initEventBus() {
            eventBus = new $window.vertx.EventBus(context + '/server/event/v1/sock');
            eventBus.onopen = function () {
                console.debug('Opened sock ');
                if (registrations.length === 0) { // the bus was opened but the listeners already removed -> we can close it again
                    eventBus.close();
                    eventBus = null;
                    return;
                }
                angular.forEach(registrations, function (registration) {
                    eventBus.registerHandler(registration.address, registration.handler);
                });
                while (pendingFetches.length > 0) {
                    pendingFetches.pop()();
                }
                open = true;
            };
            eventBus.onclose = function () { // reconnect automatically
                console.debug('Sock closed ');
                if (registrations.length > 0) {
                    $timeout(initEventBus, 5000);
                }
                open = false;
            };
        }

        return {
            listen: listen
        };
    }
})();

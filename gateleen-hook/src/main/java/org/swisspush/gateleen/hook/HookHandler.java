package org.swisspush.gateleen.hook;

import org.swisspush.gateleen.core.http.HttpRequest;
import org.swisspush.gateleen.logging.LoggingResourceManager;
import org.swisspush.gateleen.monitoring.MonitoringHandler;
import org.swisspush.gateleen.core.storage.ResourceStorage;
import org.swisspush.gateleen.core.util.StatusCode;
import org.swisspush.gateleen.queue.expiry.ExpiryCheckHandler;
import org.swisspush.gateleen.queue.queuing.QueueClient;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.*;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.joda.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The HookHandler is responsible for un- and registering hooks (listener, as well as routes). He also
 * handles forwarding requests to listeners / routes.
 *
 * @author https://github.com/ljucam [Mario Ljuca]
 */
public class HookHandler {
    public static final String HOOKED_HEADER = "x-hooked";
    public static final String HOOKS_LISTENERS_URI_PART = "/_hooks/listeners/";
    private static final String LISTENER_QUEUE_PREFIX = "listener-hook";
    private static final String LISTENER_HOOK_TARGET_PATH = "listeners/";

    public static final String HOOKS_ROUTE_URI_PART = "/_hooks/route";

    private static final String HOOK_STORAGE_PATH = "registrations/";
    private static final String HOOK_LISTENER_STORAGE_PATH = HOOK_STORAGE_PATH + "listeners/";
    private static final String HOOK_ROUTE_STORAGE_PATH = HOOK_STORAGE_PATH + "routes/";

    private static final String SAVE_LISTENER_ADDRESS = "gateleen.hook-listener-insert";
    private static final String REMOVE_LISTENER_ADDRESS = "gateleen.hook-listener-remove";
    private static final String SAVE_ROUTE_ADDRESS = "gateleen.hook-route-insert";
    private static final String REMOVE_ROUTE_ADDRESS = "gateleen.hook-route-remove";

    private static final int DEFAULT_HOOK_STORAGE_EXPIRE_AFTER_TIME = 1 * 60 * 60; // 1h in seconds
    private static final int DEFAULT_HOOK_LISTENERS_EXPIRE_AFTER_TIME = 30; // 30 seconds

    private static final int DEFAULT_CLEANUP_TIME = 15000; // 15 seconds
    public static final String REQUESTURL = "requesturl";
    public static final String EXPIRATION_TIME = "expirationTime";
    public static final String HOOK = "hook";
    public static final String EXPIRE_AFTER = "expireAfter";
    public static final String FULL_URL = "fullUrl";

    private Logger log = LoggerFactory.getLogger(HookHandler.class);
    private Vertx vertx;
    private final ResourceStorage storage;
    private MonitoringHandler monitoringHandler;
    private LoggingResourceManager loggingResourceManager;
    private final HttpClient selfClient;
    private String userProfilePath;
    private String hookRootUri;

    private ListenerRepository listenerRepository;
    private RouteRepository routeRepository;
    private QueueClient queueClient;

    /**
     * Creates a new HookHandler.
     * 
     * @param vertx vertx
     * @param selfClient selfClient
     * @param storage storage
     * @param loggingResourceManager loggingResourceManager
     * @param monitoringHandler monitoringHandler
     * @param userProfilePath userProfilePath
     * @param hookRootUri hookRootUri
     */
    public HookHandler(Vertx vertx, HttpClient selfClient, final ResourceStorage storage, LoggingResourceManager loggingResourceManager, MonitoringHandler monitoringHandler, String userProfilePath, String hookRootUri) {
        log.debug("Creating HookHandler ...");
        this.vertx = vertx;
        this.selfClient = selfClient;
        this.storage = storage;
        this.loggingResourceManager = loggingResourceManager;
        this.monitoringHandler = monitoringHandler;
        this.userProfilePath = userProfilePath;
        this.hookRootUri = hookRootUri;

        queueClient = new QueueClient(vertx, monitoringHandler);

        listenerRepository = new LocalListenerRepository();
        routeRepository = new LocalRouteRepository();
    }

    public void init() {
        registerListenerRegistrationHandler();
        registerRouteRegistrationHandler();

        loadStoredListeners();
        loadStoredRoutes();

        registerCleanupHandler();
    }

    /**
     * Registers a cleanup timer
     */
    private void registerCleanupHandler() {
        vertx.setPeriodic(DEFAULT_CLEANUP_TIME, new Handler<Long>() {
            public void handle(Long timerID) {
                log.trace("Running hook cleanup ...");

                LocalDateTime nowAsTime = ExpiryCheckHandler.getActualTime();

                // Loop through listeners first
                for (Listener listener : listenerRepository.getListeners()) {

                    if (listener.getHook().getExpirationTime().isBefore(nowAsTime)) {
                        log.debug("Listener " + listener.getListenerId() + " expired at " + listener.getHook().getExpirationTime() + " and actual time is " + nowAsTime);
                        listenerRepository.removeListener(listener.getListenerId());
                        routeRepository.removeRoute(hookRootUri + LISTENER_HOOK_TARGET_PATH + listener.getListenerId());
                    }
                }

                // Loop through routes
                Map<String, Route> routes = routeRepository.getRoutes();
                for (String key : routes.keySet()) {
                    Route route = routes.get(key);
                    if (route.getHook().getExpirationTime().isBefore(nowAsTime)) {
                        routeRepository.removeRoute(key);
                    }
                }

                log.trace("done");
            }
        });
    }

    /**
     * Loads the stored routes
     * from the resource storage,
     * if any are available.
     */
    private void loadStoredRoutes() {
        log.debug("loadStoredRoutes");

        /*
         * In order to get this working, we
         * have to create a self request
         * to see if there are any routes
         * stored.
         */

        HttpClientRequest selfRequest = selfClient.request(HttpMethod.GET, hookRootUri + HOOK_ROUTE_STORAGE_PATH + "?expand=1", response -> {

            // response OK
            if (response.statusCode() == StatusCode.OK.getStatusCode()) {
                makeResponse(response);
            } else if (response.statusCode() == StatusCode.NOT_FOUND.getStatusCode()) {
                log.debug("No route previously stored");
            } else {
                log.error("Routes could not be loaded.");
            }
        });

        selfRequest.setTimeout(120000); // avoids blocking other requests

        selfRequest.end();
    }

    private void makeResponse(HttpClientResponse response) {
        response.bodyHandler(event -> {

            /*
             * the body of our response contains
             * every storage id for each registred
             * route in the storage.
             */

            JsonObject responseObject = new JsonObject(event.toString());
            if (responseObject.getValue("routes") instanceof JsonObject) {
                JsonObject routes = responseObject.getJsonObject("routes");

                for (String routeStorageId : routes.fieldNames()) {
                    log.info("Loading route with storage id: " + routeStorageId);

                    JsonObject storageObject = routes.getJsonObject(routeStorageId);
                    registerRoute(Buffer.buffer(storageObject.toString()));
                }
            } else {
                log.info("Currently are no routes stored!");
            }
        });
    }

    /**
     * Loads the stored listeners
     * from the resource storage, if
     * any are available.
     */
    private void loadStoredListeners() {
        log.debug("loadStoredListeners");
        /*
         * In order to get this working, we
         * have to create a self request
         * to see if there are any listeners
         * stored.
         */

        HttpClientRequest selfRequest = selfClient.request(HttpMethod.GET, hookRootUri + HOOK_LISTENER_STORAGE_PATH + "?expand=1", new Handler<HttpClientResponse>() {
            public void handle(final HttpClientResponse response) {

                // response OK
                if (response.statusCode() == StatusCode.OK.getStatusCode()) {
                    response.bodyHandler(new Handler<Buffer>() {

                        @Override
                        public void handle(Buffer event) {

                            /*
                             * the body of our response contains
                             * every storage id for each registred
                             * listener in the storage.
                             */

                            JsonObject responseObject = new JsonObject(event.toString());

                            if (responseObject.getValue("listeners") instanceof JsonObject) {
                                JsonObject listeners = responseObject.getJsonObject("listeners");

                                for (String listenerStorageId : listeners.fieldNames()) {
                                    log.info("Loading listener with storage id: " + listenerStorageId);

                                    JsonObject storageObject = listeners.getJsonObject(listenerStorageId);
                                    registerListener(Buffer.buffer(storageObject.toString()));
                                }
                            } else {
                                log.info("Currently are no listeners stored!");
                            }
                        }
                    });
                } else if (response.statusCode() == StatusCode.NOT_FOUND.getStatusCode()) {
                    log.debug("No listener previously stored");
                } else {
                    log.error("Listeners could not be loaded.");
                }
            }
        });

        selfRequest.setTimeout(120000); // avoids blocking other requests

        selfRequest.end();
    }

    /**
     * Registers all needed handlers for the
     * route registration / unregistration.
     */
    private void registerRouteRegistrationHandler() {
        // Receive listener insert notifications
        vertx.eventBus().consumer(SAVE_ROUTE_ADDRESS, new Handler<Message<String>>() {
            @Override
            public void handle(final Message<String> event) {
                storage.get(event.body(), buffer -> {
                    if (buffer != null) {
                        registerRoute(buffer);
                    } else {
                        log.warn("Could not get URL '" + (event.body() == null ? "<null>" : event.body()) + "' (getting hook route).");
                    }
                });
            }
        });

        // Receive listener remove notifications
        vertx.eventBus().consumer(REMOVE_ROUTE_ADDRESS, new Handler<Message<String>>() {
            @Override
            public void handle(final Message<String> event) {
                unregisterRoute(event.body());
            }
        });
    }

    /**
     * Registers all needed handlers for the
     * listener registration / unregistration.
     */
    private void registerListenerRegistrationHandler() {
        // Receive listener insert notifications
        vertx.eventBus().consumer(SAVE_LISTENER_ADDRESS, new Handler<Message<String>>() {
            @Override
            public void handle(final Message<String> event) {
                storage.get(event.body(), buffer -> {
                    if (buffer != null) {
                        registerListener(buffer);
                    } else {
                        log.warn("Could not get URL '" + (event.body() == null ? "<null>" : event.body()) + "' (getting hook listener).");
                    }
                });
            }
        });

        // Receive listener remove notifications
        vertx.eventBus().consumer(REMOVE_LISTENER_ADDRESS, new Handler<Message<String>>() {
            @Override
            public void handle(final Message<String> event) {
                unregisterListener(event.body());
            }
        });
    }

    /**
     * Handles requests, which are either listener or
     * route related.
     * Takes on:
     * <ul>
     * <li>hook un-/registration</li>
     * <li>enqueueing a request for the registred listeners</li>
     * <li>forwarding a request to the reistred listeners</li>
     * <li>creating a self request for the original request (if necessary)</li>
     * </ul>
     * 
     * @param request request
     * @return true if a request is processed by the handler, otherwise false
     */
    public boolean handle(final HttpServerRequest request) {
        boolean consumed = false;

        /*
         * 1) Un- / Register Listener / Routes
         */
        if (isHookListenerRegistration(request)) {
            handleListenerRegistration(request);
            return true;
        }

        if (isHookListenerUnregistration(request)) {
            handleListenerUnregistration(request);
            return true;
        }

        if (isHookRouteRegistration(request)) {
            handleRouteRegistration(request);
            return true;
        }

        if (isHookRouteUnregistration(request)) {
            handleRouteUnregistration(request);
            return true;
        }

        /*
         * 2) Check if we have to queue a request for listeners
         */
        final List<Listener> listeners = listenerRepository.findListeners(request.uri(), request.method().name());

        if (!listeners.isEmpty() && !isRequestAlreadyHooked(request)) {
            installBodyHandler(request, listeners);
            consumed = true;
        }

        if (!consumed) {
            return routeRequestIfNeeded(request);
        } else {
            return true;
        }
    }

    private boolean routeRequestIfNeeded(HttpServerRequest request) {
        Route route = routeRepository.getRoute(request.uri());

        if (route != null && (route.getHook().getMethods().isEmpty() || route.getHook().getMethods().contains(request.method().name()))) {
            log.debug("Forward request " + request.uri());
            route.forward(request);
            return true;
        } else {
            return false;
        }
    }

    private void installBodyHandler(final HttpServerRequest request, final List<Listener> listeners) {
        // Read the original request and queue a new one for every listener
        request.bodyHandler(buffer -> {
            // this handler is called by the queueclient
            // for each committed listener
            Handler<Void> doneHandler = new Handler<Void>() {
                private AtomicInteger currentCount = new AtomicInteger(0);
                private boolean sent = false;

                @Override
                public void handle(Void event) {

                    // If the last queued request is performed
                    // the original request will be triggered.
                    // Because this handler is called async. we
                    // have to secure, that it is only executed
                    // once.
                    if (currentCount.incrementAndGet() == listeners.size() && !sent) {
                        sent = true;

                        /*
                         * we should find exactly one or none route (first match rtl)
                         * routes will only be found for requests coming from
                         * enqueueing through the listener and only for external
                         * requests.
                         */
                        Route route = routeRepository.getRoute(request.uri());

                        if (route != null && (route.getHook().getMethods().isEmpty() || route.getHook().getMethods().contains(request.method().name()))) {
                            log.debug("Forward request (consumed) " + request.uri());
                            route.forward(request, buffer);
                        } else {
                            // mark the original request as hooked
                            request.headers().add(HOOKED_HEADER, "true");

                            /*
                             * self requests are only made for original
                             * requests which were consumed during the
                             * enqueueing process, therefore it is
                             * imperative to use isRequestAlreadyHooked(HttpServerRequest request)
                             * before calling the handle method of
                             * this class!
                             */
                            createSelfRequest(request, buffer);
                        }
                    }
                }
            };

            for (Listener listener : listeners) {
                log.debug("Enqueue request matching " + request.method() + " " + listener.getMonitoredUrl() + " with listener " + listener.getListener());

                /*
                 * url suffix (path) after monitored url
                 * => monitored url = http://a/b/c
                 * => request.uri() = http://a/b/c/d/e.x
                 * => url suffix = /d/e.x
                 */
                String path = request.uri();
                if (!listener.getHook().isFullUrl()) {
                    path = request.uri().replace(listener.getMonitoredUrl(), "");
                }

                String targetUri;

                // internal
                if (listener.getHook().getDestination().startsWith("/")) {
                    targetUri = listener.getListener() + path;
                    log.debug(" > internal target: " + targetUri);
                }
                // external
                else {
                    targetUri = hookRootUri + LISTENER_HOOK_TARGET_PATH + listener.getListener() + path;
                    log.debug(" > external target: " + targetUri);
                }

                String queue = LISTENER_QUEUE_PREFIX + "-" + listener.getListenerId();

                // Create a new multimap, copied from the original request,
                // so that the original request is not overridden with the new values.
                MultiMap queueHeaders = new CaseInsensitiveHeaders();
                queueHeaders.addAll(request.headers());
                if (ExpiryCheckHandler.getExpireAfter(queueHeaders) == null) {
                    ExpiryCheckHandler.setExpireAfter(queueHeaders, listener.getHook().getExpireAfter());
                }

                // in order not to block the queue because one client returns a creepy response,
                // we translate all status codes of the listeners to 200.
                // Therefor we set the header x-translate-status-4xx
                queueHeaders.add("x-translate-status-4xx", "200");

                queueClient.enqueue(new HttpRequest(request.method(), targetUri, queueHeaders, buffer.getBytes()), queue, doneHandler);
            }
        });
    }

    /**
     * This method is called after an incoming route
     * unregistration is detected.
     * This method deletes the route from the resource
     * storage.
     * 
     * @param request request
     */
    private void handleRouteUnregistration(final HttpServerRequest request) {
        log.debug("handleRouteUnregistration > " + request.uri());

        // eg. /server/hooks/v1/registrations/+my+storage+id+
        final String routeStorageUri = hookRootUri + HOOK_ROUTE_STORAGE_PATH + getStorageIdentifier(request.uri());

        storage.delete(routeStorageUri, status -> {
            /*
             * In case of an unregistration, it does not matter,
             * if the route is still stored in the resource
             * storage or not. It may even be the case, that the
             * route has already expired and therefore vanished
             * from the resource storage, but the cleanup job for the
             * in-memory storage hasn't run yet.
             * Even the service which calls the unregistration
             * doesn't have to be notified if an unregistration
             * 'fails', therefore always an OK status is sent.
             */

            vertx.eventBus().publish(REMOVE_ROUTE_ADDRESS, request.uri());

            request.response().end();
        });
    }

    /**
     * This method is called after an incoming route
     * registration is detected.
     * This method puts the registration request to the
     * resource storage, so it can be reloaded even after
     * a restart of the communication service.
     * The request will be consumed in this process!
     * 
     * @param request request
     */
    private void handleRouteRegistration(final HttpServerRequest request) {
        log.debug("handleRouteRegistration > " + request.uri());

        request.bodyHandler(hookData -> {
            // eg. /server/hooks/v1/registrations/+my+storage+id+
            final String routeStorageUri = hookRootUri + HOOK_ROUTE_STORAGE_PATH + getStorageIdentifier(request.uri());

            // Extract expireAfter from the registration header.
            Integer expireAfter = ExpiryCheckHandler.getExpireAfter(request.headers());
            if (expireAfter == null) {
                expireAfter = DEFAULT_HOOK_STORAGE_EXPIRE_AFTER_TIME;
            }

            // Update the PUT header
            ExpiryCheckHandler.setExpireAfter(request, expireAfter);

            // calculate the expiration time for the listener / routes
            LocalDateTime expirationTime = ExpiryCheckHandler.getExpirationTime(expireAfter);

            /*
             * Create a new json object containing the request url
             * and the hook itself.
             * {
             * "requesturl" : "http://...",
             * "expirationTime" : "...",
             * "hook" : { ... }
             * }
             */
            JsonObject storageObject = new JsonObject();
            storageObject.put(REQUESTURL, request.uri());
            storageObject.put(EXPIRATION_TIME, ExpiryCheckHandler.printDateTime(expirationTime));
            storageObject.put(HOOK, new JsonObject(hookData.toString()));

            storage.put(routeStorageUri, request.headers(), Buffer.buffer(storageObject.toString()), status -> {
                if (status == StatusCode.OK.getStatusCode()) {
                    vertx.eventBus().publish(SAVE_ROUTE_ADDRESS, routeStorageUri);
                } else {
                    request.response().setStatusCode(status);
                }
                request.response().end();
            });
        });
    }

    /**
     * Returns the identifier of the hook (only route) used in the
     * resource storage.
     * For listener identifiere take a look at <code>getUniqueListenerId(...)</code>.
     * 
     * @param url
     * @return identifier
     */
    private String getStorageIdentifier(String url) {
        return url.replace("/", "+");
    }

    /**
     * This method is called after an incoming listener
     * unregistration is detected.
     * This method deletes the listener from the resource
     * storage.
     * 
     * @param request request
     */
    private void handleListenerUnregistration(final HttpServerRequest request) {
        log.debug("handleListenerUnregistration > " + request.uri());

        // eg. /server/hooks/v1/registrations/listeners/http+myservice+1
        final String listenerStorageUri = hookRootUri + HOOK_LISTENER_STORAGE_PATH + getUniqueListenerId(request.uri());

        storage.delete(listenerStorageUri, status -> {
            /*
             * In case of an unregistration, it does not matter,
             * if the listener is still stored in the resource
             * storage or not. It may even be the case, that the
             * listener has already expired and therefore vanished
             * from the resource storage, but the cleanup job for the
             * in-memory storage hasn't run yet.
             * Even the service which calls the unregistration
             * doesn't have to be notified if an unregistration
             * 'fails', therefore always an OK status is sent.
             */

            vertx.eventBus().publish(REMOVE_LISTENER_ADDRESS, request.uri());

            request.response().end();
        });
    }

    /**
     * This method is called after an incoming listener
     * registration is detected.
     * This method puts the registration request to the
     * resource storage, so it can be reloaded even after
     * a restart of the communication service.
     * The request will be consumed in this process!
     * 
     * @param request request
     */
    private void handleListenerRegistration(final HttpServerRequest request) {
        log.debug("handleListenerRegistration > " + request.uri());

        request.bodyHandler(hookData -> {
            // eg. /server/hooks/v1/registrations/listeners/http+serviceName+hookId
            final String listenerStorageUri = hookRootUri + HOOK_LISTENER_STORAGE_PATH + getUniqueListenerId(request.uri());

            // Extract expireAfter from the registration header.
            Integer expireAfter = ExpiryCheckHandler.getExpireAfter(request.headers());
            if (expireAfter == null) {
                expireAfter = DEFAULT_HOOK_STORAGE_EXPIRE_AFTER_TIME;
            }

            // Update the PUT header
            ExpiryCheckHandler.setExpireAfter(request, expireAfter);

            // calculate the expiration time for the listener / routes
            LocalDateTime expirationTime = ExpiryCheckHandler.getExpirationTime(expireAfter);

            /*
             * Create a new json object containing the request url
             * and the hook itself.
             * {
             * "requesturl" : "http://...",
             * "expirationTime" : "...",
             * "hook" : { ... }
             * }
             */
            JsonObject storageObject = new JsonObject();
            storageObject.put(REQUESTURL, request.uri());
            storageObject.put(EXPIRATION_TIME, ExpiryCheckHandler.printDateTime(expirationTime));
            storageObject.put(HOOK, new JsonObject(hookData.toString()));

            storage.put(listenerStorageUri, request.headers(), Buffer.buffer(storageObject.toString()), status -> {
                if (status == StatusCode.OK.getStatusCode()) {
                    vertx.eventBus().publish(SAVE_LISTENER_ADDRESS, listenerStorageUri);
                } else {
                    request.response().setStatusCode(status);
                }
                request.response().end();
            });
        });
    }

    /**
     * Creates a self Request from the original Request.
     * 
     * @param request - consumed request
     * @param requestBody - copy of request body
     */
    private void createSelfRequest(final HttpServerRequest request, Buffer requestBody) {
        log.debug("Create self request for " + request.uri());

        HttpClientRequest selfRequest = selfClient.request(request.method(), request.uri(), new Handler<HttpClientResponse>() {
            public void handle(final HttpClientResponse response) {

                /*
                 * it shouldn't matter if the request is
                 * already consumed to write a response.
                 */

                request.response().setStatusCode(response.statusCode());
                request.response().setStatusMessage(response.statusMessage());
                request.response().setChunked(true);

                request.response().headers().addAll(response.headers());
                request.response().headers().remove("Content-Length");

                response.handler(data -> request.response().write(data));

                response.endHandler(v -> request.response().end());
            }
        });

        if (request.headers() != null && !request.headers().isEmpty()) {
            selfRequest.headers().setAll(request.headers());
        }

        selfRequest.exceptionHandler(exception -> log.warn("HookHandler HOOK_ERROR: Failed self request to " + request.uri() + ": " + exception.getMessage()));

        selfRequest.setTimeout(120000); // avoids blocking other requests

        if (requestBody != null) {
            selfRequest.end(requestBody);
        } else {
            selfRequest.end();
        }
    }

    /**
     * Checks if the original Request was already hooked.
     * Eg. After a request is processed by the hook handler
     * (register), the handler creates a self request with
     * a copy of the original request. Therefore it's
     * necessary to mark the request as already hooked.
     * 
     * @param request request
     * @return true if the original request was already hooked.
     */
    public boolean isRequestAlreadyHooked(HttpServerRequest request) {
        String hooked = request.headers().get(HOOKED_HEADER);
        return hooked != null ? hooked.equals("true") : false;
    }

    /**
     * Removes the route from the repository.
     * 
     * @param requestUrl requestUrl
     */
    private void unregisterRoute(String requestUrl) {
        String routedUrl = getRoutedUrlSegment(requestUrl);

        log.debug("Unregister route " + routedUrl);

        routeRepository.removeRoute(routedUrl);
    }

    /**
     * Removes the listener and its route from the repository.
     * 
     * @param requestUrl
     */
    private void unregisterListener(String requestUrl) {
        String listenerId = getUniqueListenerId(requestUrl);

        log.debug("Unregister listener " + listenerId);

        routeRepository.removeRoute(hookRootUri + LISTENER_HOOK_TARGET_PATH + getListenerUrlSegment(requestUrl));
        listenerRepository.removeListener(listenerId);
    }

    /**
     * Registers or updates an already existing listener and
     * creates the necessary forwarder depending on the hook resource.
     * 
     * @param buffer buffer
     */
    @SuppressWarnings("unchecked")
    private void registerListener(Buffer buffer) {
        JsonObject storageObject = new JsonObject(buffer.toString());
        String requestUrl = storageObject.getString(REQUESTURL);

        if (log.isTraceEnabled()) {
            log.trace("Request URL: " + requestUrl);
        }

        // target = "http/colin/1234578" or destination url for internal forwarder (set later by if statement)
        String target = getListenerUrlSegment(requestUrl);

        // needed to identify listener
        String listenerId = getUniqueListenerId(requestUrl);

        if (log.isTraceEnabled()) {
            log.trace("Target (1st): " + target);
        }

        // create and add a new Forwarder (or replace an already existing forwarder)
        JsonObject jsonHook = storageObject.getJsonObject(HOOK);
        JsonArray jsonMethods = jsonHook.getJsonArray("methods");

        HttpHook hook = new HttpHook(jsonHook.getString("destination"));
        if (jsonMethods != null) {
            hook.setMethods(jsonMethods.getList());
        }

        if (jsonHook.getInteger(EXPIRE_AFTER) != null) {
            hook.setExpireAfter(jsonHook.getInteger(EXPIRE_AFTER));
        } else {
            hook.setExpireAfter(DEFAULT_HOOK_LISTENERS_EXPIRE_AFTER_TIME);
        }

        /*
         * Despite the fact, that every hook
         * should have an expiration time,
         * we check if the value is present.
         */
        String expirationTimeExpression = storageObject.getString(EXPIRATION_TIME);

        LocalDateTime expirationTime = null;
        if (expirationTimeExpression != null) {
            try {
                expirationTime = ExpiryCheckHandler.parseDateTime(expirationTimeExpression);
            } catch (Exception e) {
                log.warn("Listener " + listenerId + " for target " + target + " has an invalid expiration time " + expirationTimeExpression + " and will not be registred!", e);
                return;
            }
        } else {
            log.warn("Listener " + listenerId + " for target " + target + " has no expiration time and will not be registred!");
            return;
        }

        log.debug("Register listener and  route " + target + " with expiration at " + expirationTime);

        hook.setExpirationTime(expirationTime);

        boolean fullUrl = jsonHook.getBoolean(FULL_URL, false);
        hook.setFullUrl(fullUrl);

        // for internal use we don't need a forwarder
        if (hook.getDestination().startsWith("/")) {

            if (log.isTraceEnabled()) {
                log.trace("internal target, switching target!");
            }

            target = hook.getDestination();
        } else {
            String urlPattern = hookRootUri + LISTENER_HOOK_TARGET_PATH + target;
            routeRepository.addRoute(urlPattern, createRoute(urlPattern, hook));

            if (log.isTraceEnabled()) {
                log.trace("external target, add route for urlPattern: " + urlPattern);
            }
        }

        if (log.isTraceEnabled()) {
            log.trace("Target (2nd): " + target);
        }

        // create and add a new listener (or update an already existing listener)
        listenerRepository.addListener(new Listener(listenerId, getMonitoredUrlSegment(requestUrl), target, hook));
    }

    /**
     * Creates a listener id, which is unique for the given service, and the
     * monitored url.
     * 
     * @param requestUrl requestUrl
     * @return String
     */
    private String getUniqueListenerId(String requestUrl) {
        StringBuffer listenerId = new StringBuffer();

        // eg. http/colin/1 -> http+colin+1
        listenerId.append(convertToStoragePattern(getListenerUrlSegment(requestUrl)));

        // eg. /gateleen/trip/v1 -> +gateleen+trip+v1
        listenerId.append(convertToStoragePattern(getMonitoredUrlSegment(requestUrl)));

        return listenerId.toString();
    }

    /**
     * Replaces all unwanted charakters (like "/", ".", ":") with "+".
     * 
     * @param urlSegment urlSegment
     * @return String
     */
    private String convertToStoragePattern(String urlSegment) {
        return urlSegment.replace("/", "+").replace(".", "+").replace(":", "+");
    }

    /**
     * Registers or updates an already existing route and
     * creates the necessary forwarder depending on the hook resource.
     * 
     * @param buffer buffer
     */
    @SuppressWarnings("unchecked")
    private void registerRoute(Buffer buffer) {
        JsonObject storageObject = new JsonObject(buffer.toString());
        String requestUrl = storageObject.getString(REQUESTURL);
        String routedUrl = getRoutedUrlSegment(requestUrl);

        log.debug("Register route to  " + routedUrl);

        // create and add a new Forwarder (or replace an already existing forwarder)
        JsonObject jsonHook = storageObject.getJsonObject(HOOK);
        JsonArray jsonMethods = jsonHook.getJsonArray("methods");

        HttpHook hook = new HttpHook(jsonHook.getString("destination"));
        if (jsonMethods != null) {
            hook.setMethods(jsonMethods.getList());
        }

        if (jsonHook.getInteger(EXPIRE_AFTER) != null) {
            hook.setExpireAfter(jsonHook.getInteger(EXPIRE_AFTER));
        } else {
            hook.setExpireAfter(DEFAULT_HOOK_LISTENERS_EXPIRE_AFTER_TIME);
        }

        /*
         * Despite the fact, that every hook
         * should have an expiration time,
         * we check if the value is present.
         */
        String expirationTimeExpression = storageObject.getString(EXPIRATION_TIME);

        if (expirationTimeExpression != null) {
            try {
                hook.setExpirationTime(ExpiryCheckHandler.parseDateTime(expirationTimeExpression));
            } catch (Exception e) {
                log.warn("Route " + routedUrl + " has an invalid expiration time " + expirationTimeExpression + " and will not be registred!");
                return;
            }
        } else {
            log.warn("Route " + routedUrl + " has no expiration time and will not be registred!");
            return;
        }

        boolean fullUrl = storageObject.getBoolean(FULL_URL, false);
        hook.setFullUrl(fullUrl);

        routeRepository.addRoute(routedUrl, createRoute(routedUrl, hook));
    }

    /**
     * Creates a new dynamic routing for the given hook.
     * 
     * @param urlPattern urlPattern
     * @param hook hook
     * @return Route
     */
    private Route createRoute(String urlPattern, HttpHook hook) {
        return new Route(vertx, storage, loggingResourceManager, monitoringHandler, userProfilePath, hook, urlPattern);
    }

    /**
     * Returns the url segment to which the route should be hooked.
     * For "http://a/b/c/_hooks/route" this would
     * be "http://a/b/c".
     * 
     * @param requestUrl requestUrl
     * @return url segment which requests should be routed
     */
    private String getRoutedUrlSegment(String requestUrl) {
        return requestUrl.substring(0, requestUrl.indexOf(HOOKS_ROUTE_URI_PART));
    }

    /**
     * Returns the url segment to which the listener should be hooked.
     * For "http://a/b/c/_hooks/listeners/http/colin/1234578" this would
     * be "http://a/b/c".
     * 
     * @param requestUrl requestUrl
     * @return url segment to which the listener should be hooked.
     */
    private String getMonitoredUrlSegment(String requestUrl) {
        return requestUrl.substring(0, requestUrl.indexOf(HOOKS_LISTENERS_URI_PART));
    }

    /**
     * Returns the url segment which represents the listener.
     * For "http://a/b/c/_hooks/listeners/http/colin/1234578" this would
     * be "http/colin/1234578".
     * 
     * @param requestUrl requestUrl
     * @return url segment
     */
    private String getListenerUrlSegment(String requestUrl) {
        String segment = requestUrl.substring(requestUrl.indexOf(HOOKS_LISTENERS_URI_PART));

        // remove hook - part
        segment = segment.replace(HOOKS_LISTENERS_URI_PART, "");

        return segment;
    }

    /**
     * Checks if the given request is a listener unregistration instruction.
     * 
     * @param request request
     * @return boolean
     */
    private boolean isHookListenerUnregistration(HttpServerRequest request) {
        return request.uri().contains(HOOKS_LISTENERS_URI_PART) && HttpMethod.DELETE == request.method();
    }

    /**
     * Checks if the given request is a listener registration instruction.
     * 
     * @param request request
     * @return boolean
     */
    private boolean isHookListenerRegistration(HttpServerRequest request) {
        return request.uri().contains(HOOKS_LISTENERS_URI_PART) && HttpMethod.PUT == request.method();
    }

    /**
     * Checks if the given request is a route registration instruction.
     * 
     * @param request request
     * @return boolean
     */
    private boolean isHookRouteRegistration(HttpServerRequest request) {
        return request.uri().contains(HOOKS_ROUTE_URI_PART) && HttpMethod.PUT == request.method();
    }

    /**
     * Checks if the given request is a route registration instruction.
     * 
     * @param request request
     * @return boolean
     */
    private boolean isHookRouteUnregistration(HttpServerRequest request) {
        return request.uri().contains(HOOKS_ROUTE_URI_PART) && HttpMethod.DELETE == request.method();
    }
}

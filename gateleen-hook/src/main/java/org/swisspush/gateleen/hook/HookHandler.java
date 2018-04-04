package org.swisspush.gateleen.hook;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.fge.jsonschema.util.JsonLoader;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.ValidationMessage;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.*;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.joda.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.http.HeaderFunction;
import org.swisspush.gateleen.core.http.HeaderFunctions;
import org.swisspush.gateleen.core.http.HttpRequest;
import org.swisspush.gateleen.core.logging.LoggableResource;
import org.swisspush.gateleen.core.logging.RequestLogger;
import org.swisspush.gateleen.core.storage.ResourceStorage;
import org.swisspush.gateleen.core.util.CollectionContentComparator;
import org.swisspush.gateleen.core.util.HttpRequestHeader;
import org.swisspush.gateleen.core.util.HttpServerRequestUtil;
import org.swisspush.gateleen.core.util.StatusCode;
import org.swisspush.gateleen.hook.queueingstrategy.*;
import org.swisspush.gateleen.hook.reducedpropagation.ReducedPropagationManager;
import org.swisspush.gateleen.logging.LoggingResourceManager;
import org.swisspush.gateleen.monitoring.MonitoringHandler;
import org.swisspush.gateleen.queue.expiry.ExpiryCheckHandler;
import org.swisspush.gateleen.queue.queuing.QueueClient;
import org.swisspush.gateleen.queue.queuing.RequestQueue;
import org.swisspush.gateleen.routing.Rule;

import java.net.URL;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.swisspush.gateleen.core.util.HttpRequestHeader.CONTENT_LENGTH;

/**
 * The HookHandler is responsible for un- and registering hooks (listener, as well as routes). He also
 * handles forwarding requests to listeners / routes.
 *
 * @author https://github.com/ljucam [Mario Ljuca]
 */
public class HookHandler implements LoggableResource {
    public static final String HOOKED_HEADER = "x-hooked";
    public static final String HOOK_ROUTES_LISTED = "x-hook-routes-listed";
    public static final String HOOKS_LISTENERS_URI_PART = "/_hooks/listeners/";
    public static final String LISTENER_QUEUE_PREFIX = "listener-hook";
    private static final String X_QUEUE = "x-queue";
    private static final String X_EXPIRE_AFTER = "X-Expire-After";
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
    public static final String QUEUE_EXPIRE_AFTER = "queueExpireAfter";
    public static final String STATIC_HEADERS = "staticHeaders";
    public static final String FULL_URL = "fullUrl";
    public static final String DISCARD_PAYLOAD = "discardPayload";
    public static final String HOOK_TRIGGER_TYPE = "type";
    public static final String LISTABLE = "listable";
    public static final String COLLECTION = "collection";

    private final Comparator<String> collectionContentComparator;
    private static final Logger log = LoggerFactory.getLogger(HookHandler.class);

    private Vertx vertx;
    private final ResourceStorage userProfileStorage;
    private final ResourceStorage hookStorage;
    private MonitoringHandler monitoringHandler;
    private LoggingResourceManager loggingResourceManager;
    private final HttpClient selfClient;
    private String userProfilePath;
    private String hookRootUri;
    private boolean listableRoutes;
    private ListenerRepository listenerRepository;
    private RouteRepository routeRepository;
    private RequestQueue requestQueue;

    private ReducedPropagationManager reducedPropagationManager;

    private boolean logHookConfigurationResourceChanges = false;

    private Handler<Void> doneHandler;

    private final JsonSchema jsonSchemaHook;


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
    public HookHandler(Vertx vertx, HttpClient selfClient, final ResourceStorage storage,
                       LoggingResourceManager loggingResourceManager, MonitoringHandler monitoringHandler,
                       String userProfilePath, String hookRootUri) {
        this(vertx,selfClient, storage, loggingResourceManager, monitoringHandler, userProfilePath, hookRootUri,
                new QueueClient(vertx, monitoringHandler));
    }


    /**
     * Creates a new HookHandler.

     * @param vertx vertx
     * @param selfClient selfClient
     * @param storage storage
     * @param loggingResourceManager loggingResourceManager
     * @param monitoringHandler monitoringHandler
     * @param userProfilePath userProfilePath
     * @param hookRootUri hookRootUri
     * @param requestQueue requestQueue
     */
    public HookHandler(Vertx vertx, HttpClient selfClient, final ResourceStorage storage,
                       LoggingResourceManager loggingResourceManager, MonitoringHandler monitoringHandler,
                       String userProfilePath, String hookRootUri, RequestQueue requestQueue) {
        this(vertx, selfClient,storage, loggingResourceManager, monitoringHandler, userProfilePath, hookRootUri,
                requestQueue, false);
    }

    public HookHandler(Vertx vertx, HttpClient selfClient, final ResourceStorage storage,
                       LoggingResourceManager loggingResourceManager, MonitoringHandler monitoringHandler,
                       String userProfilePath, String hookRootUri, RequestQueue requestQueue, boolean listableRoutes) {
        this(vertx, selfClient,storage, loggingResourceManager, monitoringHandler, userProfilePath, hookRootUri,
                requestQueue, false, null);
    }

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
     * @param requestQueue requestQueue
     * @param listableRoutes listableRoutes
     * @param reducedPropagationManager reducedPropagationManager
     */
    public HookHandler(Vertx vertx, HttpClient selfClient, final ResourceStorage storage,
                       LoggingResourceManager loggingResourceManager, MonitoringHandler monitoringHandler,
                       String userProfilePath, String hookRootUri, RequestQueue requestQueue, boolean listableRoutes,
                       ReducedPropagationManager reducedPropagationManager) {
        this(vertx, selfClient,storage, loggingResourceManager, monitoringHandler, userProfilePath, hookRootUri,
                requestQueue, listableRoutes, reducedPropagationManager, null, storage);
    }

    /**
     * Creates a new HookHandler.
     *
     * @param vertx vertx
     * @param selfClient selfClient
     * @param userProfileStorage userProfileStorage - where the user profiles are stored
     * @param loggingResourceManager loggingResourceManager
     * @param monitoringHandler monitoringHandler
     * @param userProfilePath userProfilePath
     * @param hookRootUri hookRootUri
     * @param requestQueue requestQueue
     * @param listableRoutes listableRoutes
     * @param reducedPropagationManager reducedPropagationManager
     * @param doneHandler doneHandler
     * @param hookStorage hookStorage - where the hooks are stored
     */
    public HookHandler(Vertx vertx, HttpClient selfClient, final ResourceStorage userProfileStorage,
                       LoggingResourceManager loggingResourceManager, MonitoringHandler monitoringHandler,
                       String userProfilePath, String hookRootUri, RequestQueue requestQueue, boolean listableRoutes,
                       ReducedPropagationManager reducedPropagationManager, Handler doneHandler, ResourceStorage hookStorage) {
        log.debug("Creating HookHandler ...");
        this.vertx = vertx;
        this.selfClient = selfClient;
        this.userProfileStorage = userProfileStorage;
        this.loggingResourceManager = loggingResourceManager;
        this.monitoringHandler = monitoringHandler;
        this.userProfilePath = userProfilePath;
        this.hookRootUri = hookRootUri;
        this.requestQueue = requestQueue;
        this.listableRoutes = listableRoutes;
        this.reducedPropagationManager = reducedPropagationManager;
        listenerRepository = new LocalListenerRepository();
        routeRepository = new LocalRouteRepository();
        collectionContentComparator = new CollectionContentComparator();
        this.doneHandler = doneHandler;
        this.hookStorage = hookStorage;

        URL url = HookHandler.class.getResource("/gateleen_hooking_schema_hook");
        jsonSchemaHook = JsonSchemaFactory.getInstance().getSchema(url);
    }

    public void init() {
        // add all init methods here (!)
        final List<Consumer<Handler<Void>>> initMethods = new ArrayList<>();
        initMethods.add(this::registerListenerRegistrationHandler);
        initMethods.add(this::registerRouteRegistrationHandler);
        initMethods.add(this::loadStoredListeners);
        initMethods.add(this::loadStoredRoutes);
        initMethods.add(this::registerCleanupHandler);

        // ready handler, calls the doneHandler when everything is done and the HookHandler is ready to use
        Handler<Void> readyHandler = new Handler<Void>() {
            // count of methods with may return an OK (ready)
            private AtomicInteger readyCounter = new AtomicInteger(initMethods.size());

            @Override
            public void handle(Void aVoid) {
                if ( readyCounter.decrementAndGet() == 0 ) {
                    log.info("HookHandler is ready!");
                    if ( doneHandler != null ) {
                        doneHandler.handle(null);
                    }
                }
            }
        };

        initMethods.forEach( handlerConsumer -> handlerConsumer.accept(readyHandler) );
    }

    @Override
    public void enableResourceLogging(boolean resourceLoggingEnabled) {
        this.logHookConfigurationResourceChanges = resourceLoggingEnabled;
    }

    /**
     * Registers a cleanup timer
     *
     * @param readyHandler - the ready handler
     */
    private void registerCleanupHandler(Handler<Void> readyHandler) {
        vertx.setPeriodic(DEFAULT_CLEANUP_TIME, new Handler<Long>() {
            public void handle(Long timerID) {
                log.trace("Running hook cleanup ...");

                LocalDateTime nowAsTime = ExpiryCheckHandler.getActualTime();

                // Loop through listeners first
                for (Listener listener : listenerRepository.getListeners()) {

                    final Optional<LocalDateTime> expirationTime = listener.getHook().getExpirationTime();
                    if (!expirationTime.isPresent()) {
                        log.trace("Listener " + listener.getListenerId() + " will never expire.");
                    } else if (expirationTime.get().isBefore(nowAsTime)) {
                        log.debug("Listener " + listener.getListenerId() + " expired at " + expirationTime + " and actual time is " + nowAsTime);
                        listenerRepository.removeListener(listener.getListenerId());
                        routeRepository.removeRoute(hookRootUri + LISTENER_HOOK_TARGET_PATH + listener.getListenerId());
                    }
                }

                // Loop through routes
                Map<String, Route> routes = routeRepository.getRoutes();
                for (String key : routes.keySet()) {
                    Route route = routes.get(key);
                    final Optional<LocalDateTime> expirationTime = route.getHook().getExpirationTime();
                    if (!expirationTime.isPresent()) {
                        log.trace("Route " + key + " will never expire.");
                    } else if (expirationTime.get().isBefore(nowAsTime)) {
                        routeRepository.removeRoute(key);
                    }
                }

                log.trace("done");
            }
        });

        // method done / no async processing pending
        readyHandler.handle(null);
    }

    /**
     * Loads the stored routes
     * from the resource hookStorage,
     * if any are available.
     *
     * @param readyHandler - the ready handler
     */
    private void loadStoredRoutes(Handler<Void> readyHandler) {
        log.debug("loadStoredRoutes");

        // load the names of the routes from the hookStorage
        final String routeBase = hookRootUri + HOOK_ROUTE_STORAGE_PATH;

        hookStorage.get(routeBase, buffer -> {
            if (buffer != null) {
                JsonObject listOfRoutes = new JsonObject(buffer.toString());
                JsonArray routeNames = listOfRoutes.getJsonArray("routes");
                Iterator<String> keys = routeNames.getList().iterator();

                final AtomicInteger storedRoutesCount = new AtomicInteger(routeNames.getList().size());

                // go through the routes ...
                while( keys.hasNext() ) {
                    final String key = keys.next();

                    // ... and load each one
                    hookStorage.get(routeBase + key, routeBody -> {
                        if ( routeBody != null ) {
                            registerRoute(routeBody);
                        }
                        else {
                            log.warn("Could not get URL '" + routeBase + key + "' (getting hook route).");
                        }

                        // send a ready flag
                        if ( storedRoutesCount.decrementAndGet() == 0 ) {
                            readyHandler.handle( null );
                        }
                    });
                }
            } else {
                log.warn("Could not get URL '" + routeBase + "' (getting hook route).");
                // send a ready flag
                readyHandler.handle( null );
            }
        });
    }

    /**
     * Loads the stored listeners
     * from the resource hookStorage, if
     * any are available.
     * @param readyHandler - the ready handler
     */
    private void loadStoredListeners(final Handler<Void> readyHandler) {
        log.debug("loadStoredListeners");

        // load the names of the listener from the hookStorage
        final String listenerBase = hookRootUri + HOOK_LISTENER_STORAGE_PATH;
        hookStorage.get(listenerBase, buffer -> {
            if (buffer != null) {
                JsonObject listOfListeners = new JsonObject(buffer.toString());
                JsonArray listenerNames = listOfListeners.getJsonArray("listeners");
                Iterator<String> keys = listenerNames.getList().iterator();

                final AtomicInteger storedListenerCount = new AtomicInteger(listenerNames.getList().size());

                // go through the listeners ...
                while( keys.hasNext() ) {
                    final String key = keys.next();

                    // ... and load each one
                    hookStorage.get(listenerBase + key, listenerBody -> {
                        if ( listenerBody != null ) {
                            registerListener(listenerBody);
                        }
                        else {
                            log.warn("Could not get URL '" + listenerBase + key + "' (getting hook listener).");
                        }

                        // send a ready flag
                        if ( storedListenerCount.decrementAndGet() == 0 ) {
                            readyHandler.handle( null );
                        }
                    });
                }
            } else {
                log.warn("Could not get URL '" + listenerBase + "' (getting hook listener).");
                // send a ready flag
                readyHandler.handle( null );
            }
        });
    }

    /**
     * Registers all needed handlers for the
     * route registration / unregistration.
     *
     * @param readyHandler - the ready handler
     */
    private void registerRouteRegistrationHandler(Handler<Void> readyHandler) {
        // Receive listener insert notifications
        vertx.eventBus().consumer(SAVE_ROUTE_ADDRESS, new Handler<Message<String>>() {
            @Override
            public void handle(final Message<String> event) {
                hookStorage.get(event.body(), buffer -> {
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

        // method done / no async processing pending
        readyHandler.handle(null);
    }

    /**
     * Registers all needed handlers for the
     * listener registration / unregistration.
     */
    public void registerListenerRegistrationHandler(Handler<Void> readyHandler) {
        // Receive listener insert notifications
        vertx.eventBus().consumer(SAVE_LISTENER_ADDRESS, new Handler<Message<String>>() {
            @Override
            public void handle(final Message<String> event) {
                hookStorage.get(event.body(), buffer -> {
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

        // method done / no async processing pending
        readyHandler.handle(null);
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
            consumed = routeRequestIfNeeded(request);

            if (!consumed) {
                return createListingIfRequested(request);
            }

            return consumed;
        } else {
            return true;
        }
    }

    /**
     * Create a listing of routes in the given parent. This happens
     * only if we have a GET request, the routes are listable and
     * the request is not marked as already listed (x-hook-routes-listed:true).
     *
     * @param request request
     * @return true if a listing was performed (consumed), otherwise false.
     */
    private boolean createListingIfRequested(final HttpServerRequest request) {
        String routesListedHeader = request.headers().get(HOOK_ROUTES_LISTED);
        boolean routesListed = routesListedHeader != null && routesListedHeader.equals("true");

        // GET request / routes not yet listed
        if ( request.method().equals(HttpMethod.GET) && ! routesListed ) {
            // route collection available for parent?
            final List<String> collections = new ArrayList<String>(routeRepository.getCollections(request.uri()));

            if ( ! collections.isEmpty() ) {
                String parentUri = request.uri().contains("?") ? request.uri().substring(0, request.uri().indexOf('?')) : request.uri();
                final String parentCollection = getCollectionName(parentUri);

                // sort the result array
                collections.sort(collectionContentComparator);

                if ( log.isTraceEnabled() ) {
                    log.trace("createListingIfRequested > (parentUri) {}, (parentCollection) {}", parentUri, parentCollection);
                }

                HttpClientRequest selfRequest = selfClient.request(request.method(), request.uri(), response -> {
                    HttpServerRequestUtil.prepareResponse(request, response);

                    request.response().headers().remove(HOOK_ROUTES_LISTED);

                    // if everything is fine, we add the listed collections to the given array
                    if ( response.statusCode() == StatusCode.OK.getStatusCode() ) {
                        if ( log.isTraceEnabled() ) {
                            log.trace("createListingIfRequested > use existing array");
                        }

                        response.handler(data -> {
                            JsonObject responseObject = new JsonObject(data.toString());

                            // we only got an array back, if we perform a simple request
                            if (responseObject.getValue(parentCollection) instanceof JsonArray) {
                                JsonArray parentCollectionArray = responseObject.getJsonArray(parentCollection);

                                // add the listed routes
                                collections.forEach(parentCollectionArray::add);
                            }

                            if ( log.isTraceEnabled() ) {
                                log.trace("createListingIfRequested > response: {}", responseObject.toString() );
                            }

                            // write the response
                            request.response().write(Buffer.buffer(responseObject.toString()));
                        });
                    }
                    // if nothing is found, we create a new array
                    else if ( response.statusCode() == StatusCode.NOT_FOUND.getStatusCode() ) {
                        if ( log.isTraceEnabled() ) {
                            log.trace("createListingIfRequested > creating new array");
                        }

                        response.handler(data -> {
                            // override status message and code
                            request.response().setStatusCode(StatusCode.OK.getStatusCode());
                            request.response().setStatusMessage(StatusCode.OK.getStatusMessage());

                            JsonObject responseObject = new JsonObject();
                            JsonArray parentCollectionArray = new JsonArray();
                            responseObject.put(parentCollection, parentCollectionArray);

                            // add the listed routes
                            collections.forEach(parentCollectionArray::add);

                            if ( log.isTraceEnabled() ) {
                                log.trace("createListingIfRequested > response: {}", responseObject.toString() );
                            }

                            // write the response
                            request.response().write(Buffer.buffer(responseObject.toString()));
                        });
                    }
                    // something's wrong ...
                    else {
                        log.debug("createListingIfRequested - got response - ERROR");
                        response.handler(data -> request.response().write(data));
                    }

                    response.endHandler(v -> request.response().end());
                });

                if (request.headers() != null && !request.headers().isEmpty()) {
                    selfRequest.headers().setAll(request.headers());
                }

                // mark request as already listed
                selfRequest.headers().add(HOOK_ROUTES_LISTED, "true");

                selfRequest.exceptionHandler(exception -> log.warn("HookHandler: listing of collections (routes) failed: " + request.uri() + ": " + exception.getMessage()));
                selfRequest.setTimeout(120000); // avoids blocking other requests
                selfRequest.end();

                // consumed
                return true;
            }
        }

        // not consumed
        return false;
    }

    private String getCollectionName(String url) {
        if ( url.endsWith("/") ) {
            url = url.substring(0, url.lastIndexOf("/"));
        }

        return url.substring(url.lastIndexOf("/") + 1, url.length());
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
            // Create separate lists with filtered listeners
            List<Listener> beforeListener = getFilteredListeners(listeners, HookTriggerType.BEFORE);
            List<Listener> afterListener = getFilteredListeners(listeners, HookTriggerType.AFTER);

            // Create handlers for before/after - cases
            Handler<Void> afterHandler = installAfterHandler(request, buffer, afterListener);
            Handler<Void> beforeHandler = installBeforeHandler(request, buffer, beforeListener, afterHandler);

            // call the listeners (before)
            callListener(request, buffer, beforeListener, beforeHandler);
        });
    }

    /**
     * Calls the passed listeners and passes the given handler to the enqueued listener requests.
     *
     * @param request original request
     * @param buffer buffer
     * @param filteredListeners all listeners which should be called
     * @param handler the handler, which should handle the requests
     */
    private void callListener(final HttpServerRequest request, final Buffer buffer, final List<Listener> filteredListeners, final Handler<Void> handler) {
        for (Listener listener : filteredListeners) {
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

            // Create a new multimap, copied from the original request,
            // so that the original request is not overridden with the new values.
            MultiMap queueHeaders = new CaseInsensitiveHeaders();
            queueHeaders.addAll(request.headers());

            // Apply the header manipulation chain - errors (unresolvable references) will just be WARN logged - but we still enqueue
            final HeaderFunctions.EvalScope evalScope = listener.getHook().getHeaderFunction().apply(queueHeaders);
            if (evalScope.getErrorMessage() != null) {
                log.warn("problem applying header manipulator chain {} in listener {}", evalScope.getErrorMessage(), listener.getListenerId());
            }

            if (ExpiryCheckHandler.getExpireAfter(queueHeaders) == null) {
                ExpiryCheckHandler.setExpireAfter(queueHeaders, listener.getHook().getExpireAfter());
            }

            if(ExpiryCheckHandler.getQueueExpireAfter(queueHeaders) == null && listener.getHook().getQueueExpireAfter() != -1 ) {
                ExpiryCheckHandler.setQueueExpireAfter(queueHeaders, listener.getHook().getQueueExpireAfter());
            }

            // if there is an x-queue header (after applying the header manipulator chain!),
            // then directly enqueue to this queue - else enqueue to a queue named alike this listener hook
            String queue = queueHeaders.get(X_QUEUE);
            if (queue == null) {
                queue = LISTENER_QUEUE_PREFIX + "-" + listener.getListenerId(); // default queue name for this listener hook
            } else {
                queueHeaders.remove(X_QUEUE); // remove the "x-queue" header - otherwise we take a second turn through the queue
            }

            // in order not to block the queue because one client returns a creepy response,
            // we translate all status codes of the listeners to 200.
            // Therefor we set the header x-translate-status-4xx
            queueHeaders.add("x-translate-status-4xx", "200");

            QueueingStrategy queueingStrategy = listener.getHook().getQueueingStrategy();

            if(queueingStrategy instanceof DefaultQueueingStrategy){
                requestQueue.enqueue(new HttpRequest(request.method(), targetUri, queueHeaders, buffer.getBytes()), queue, handler);
            } else if(queueingStrategy instanceof DiscardPayloadQueueingStrategy){
                if(HttpRequestHeader.containsHeader(queueHeaders, CONTENT_LENGTH)) {
                    queueHeaders.set(CONTENT_LENGTH.getName(), "0");
                }
                requestQueue.enqueue(new HttpRequest(request.method(), targetUri, queueHeaders, null), queue, handler);
            } else if(queueingStrategy instanceof ReducedPropagationQueueingStrategy){
                if(reducedPropagationManager != null) {
                    reducedPropagationManager.processIncomingRequest(request.method(), targetUri, queueHeaders, buffer,
                            queue, ((ReducedPropagationQueueingStrategy) queueingStrategy).getPropagationIntervalMs(), handler);
                } else {
                    log.error("ReducedPropagationQueueingStrategy without configured ReducedPropagationManager. Not going to handle (enqueue) anything!");
                }
            } else {
                log.error("QueueingStrategy '"+queueingStrategy.getClass().getSimpleName()+"' is not handled. Could be an error, check the source code!");
            }
        }

        // if for e.g. the beforListeners are empty,
        // we have to ensure, that the original request
        // is executed. This way the after handler will
        // also be called properly.
        if ( filteredListeners.isEmpty() && handler != null ) {
            handler.handle(null);
        }
    }

    /**
     * This handler is called after the self request (original request) is performed
     * successfully.
     * The handler calls all listener (after), so this requests happen AFTER the original
     * request is performed.
     *
     * @param request original request
     * @param buffer buffer
     * @param afterListener list of listeners which should be called after the original request
     * @return the after handler
     */
    private Handler<Void> installAfterHandler(final HttpServerRequest request, final Buffer buffer, final List<Listener> afterListener) {
        Handler<Void> afterHandler = event -> callListener(request, buffer, afterListener, null);
        return afterHandler;
    }

    /**
     * This handler is called by the queueclient
     * for each listener (before).
     * The request  happens BEFORE the original request is
     * performed.

     * @param request original request
     * @param buffer buffer
     * @param beforeListener list of listeners which should be called before the original request
     * @param afterHandler the handler for listeners which have to be called after the original request
     * @return the before handler
     */
    private Handler<Void> installBeforeHandler(final HttpServerRequest request, final Buffer buffer, final List<Listener> beforeListener, final Handler<Void> afterHandler) {
        Handler<Void> beforeHandler = new Handler<Void>() {
            private AtomicInteger currentCount = new AtomicInteger(0);
            private boolean sent = false;

            @Override
            public void handle(Void event) {
                // If the last queued request is performed
                // the original request will be triggered.
                // Because this handler is called async. we
                // have to secure, that it is only executed
                // once.
                if ( ( currentCount.incrementAndGet() == beforeListener.size() || beforeListener.isEmpty() ) && !sent) {
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
                        createSelfRequest(request, buffer, afterHandler);
                    }
                }
            }
        };

        return beforeHandler;
    }

    /**
     * Returns a list with listeners which fires before / after the original request.
     *
     * @param listeners all listeners
     * @return filtered listeners
     */
    private List<Listener> getFilteredListeners(final List<Listener> listeners, final HookTriggerType hookTriggerType) {
        return listeners.stream()
                .filter(listener -> listener.getHook().getHookTriggerType().equals(hookTriggerType))
                .collect(Collectors.toList());
    }

    /**
     * This method is called after an incoming route
     * unregistration is detected.
     * This method deletes the route from the resource
     * hookStorage.
     * 
     * @param request request
     */
    private void handleRouteUnregistration(final HttpServerRequest request) {
        log.debug("handleRouteUnregistration > " + request.uri());

        // eg. /server/hooks/v1/registrations/+my+storage+id+
        final String routeStorageUri = hookRootUri + HOOK_ROUTE_STORAGE_PATH + getStorageIdentifier(request.uri());

        hookStorage.delete(routeStorageUri, status -> {
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
            if(isHookJsonInvalid(request, hookData)) {
                return;
            }

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
            JsonObject hook;
            try {
                hook = new JsonObject(hookData.toString());
            } catch (DecodeException e) {
                badRequest(request, "Cannot decode JSON", e.getMessage());
                return;
            }

            JsonObject storageObject = new JsonObject();
            storageObject.put(REQUESTURL, request.uri());
            storageObject.put(EXPIRATION_TIME, ExpiryCheckHandler.printDateTime(expirationTime));
            storageObject.put(HOOK, hook);
            Buffer buffer = Buffer.buffer(storageObject.toString());
            hookStorage.put(routeStorageUri, request.headers(), buffer, status -> {
                if (status == StatusCode.OK.getStatusCode()) {
                    if(logHookConfigurationResourceChanges){
                        RequestLogger.logRequest(vertx.eventBus(), request, status, buffer);
                    }
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

        hookStorage.delete(listenerStorageUri, status -> {
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
            if(isHookJsonInvalid(request, hookData)) {
                return;
            }

            JsonObject hook;
            try {
                hook = new JsonObject(hookData);
            } catch (DecodeException e) {
                log.error("Cannot decode JSON", e);
                badRequest(request, "Cannot decode JSON", e.getMessage());
                return;
            }
            String destination = hook.getString("destination");
            String hookOnUri = getMonitoredUrlSegment(request.uri());
            if (destination.startsWith(hookOnUri)) {
                badRequest(request, "illegal destination", "Destination-URI should not be within subtree of your hooked resource. This would lead to an infinite loop.");
                return;
            }

            // eg. /server/hooks/v1/registrations/listeners/http+serviceName+hookId
            final String listenerStorageUri = hookRootUri + HOOK_LISTENER_STORAGE_PATH + getUniqueListenerId(request.uri());

            final String expirationTime = extractExpTimeAndManipulatePassedRequestAndReturnExpTime(request)
                    .orElse(null);
            if (log.isDebugEnabled()) {
                log.debug("Hook " + request.uri() + " expirationTime is " + expirationTime + ".");
            }

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
            storageObject.put(EXPIRATION_TIME, expirationTime);
            storageObject.put(HOOK, hook);

            Buffer buffer = Buffer.buffer(storageObject.toString());
            hookStorage.put(listenerStorageUri, request.headers(), buffer, status -> {
                if (status == StatusCode.OK.getStatusCode()) {
                    if(logHookConfigurationResourceChanges){
                        RequestLogger.logRequest(vertx.eventBus(), request, status, buffer);
                    }
                    vertx.eventBus().publish(SAVE_LISTENER_ADDRESS, listenerStorageUri);
                } else {
                    request.response().setStatusCode(status);
                }
                request.response().end();
            });
        });
    }

    public boolean isHookJsonInvalid(HttpServerRequest request, Buffer hookData) {
        try {
            JsonNode hook = JsonLoader.fromString(hookData.toString());
            final Set<ValidationMessage> valMsgs = jsonSchemaHook.validate(hook);
            if (valMsgs.size() > 0) {
                badRequest(request, "Hook JSON invalid", valMsgs.toString());
                return true;
            }
        } catch (Exception ex) {
            log.error("Cannot decode JSON", ex);
            badRequest(request, "Cannot decode JSON", ex.getMessage());
            return true;
        }
        return false;
    }

    private void badRequest(HttpServerRequest request, String statusMsg, String longMsg) {
        HttpServerResponse response = request.response();
        response.setStatusCode(StatusCode.BAD_REQUEST.getStatusCode());
        request.response().setStatusMessage(statusMsg);
        request.response().end(longMsg);
    }

    /**
     * Creates a self Request from the original Request.
     * If the requests succeeds (and only then) the after handler is called.
     * 
     * @param request - consumed request
     * @param requestBody - copy of request body
     */
    private void createSelfRequest(final HttpServerRequest request, final Buffer requestBody, final Handler<Void> afterHandler) {
        log.debug("Create self request for " + request.uri());

        HttpClientRequest selfRequest = selfClient.request(request.method(), request.uri(), response -> {
            /*
             * it shouldn't matter if the request is
             * already consumed to write a response.
             */

            HttpServerRequestUtil.prepareResponse(request, response);

            response.handler(data -> request.response().write(data));

            response.endHandler(v -> request.response().end());

            // if everything is fine, we call the after handler
            if ( response.statusCode() == StatusCode.OK.getStatusCode() ) {
                afterHandler.handle(null);
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

        if (jsonHook.containsKey("filter")) {
            hook.setFilter(jsonHook.getString("filter"));
        }

        if (jsonHook.getInteger(EXPIRE_AFTER) != null) {
            hook.setExpireAfter(jsonHook.getInteger(EXPIRE_AFTER));
        } else {
            hook.setExpireAfter(DEFAULT_HOOK_LISTENERS_EXPIRE_AFTER_TIME);
        }

        if (jsonHook.getInteger(QUEUE_EXPIRE_AFTER) != null ) {
            hook.setQueueExpireAfter(jsonHook.getInteger(QUEUE_EXPIRE_AFTER));
        }

        if (jsonHook.getString(HOOK_TRIGGER_TYPE) != null) {
            try {
                hook.setHookTriggerType(HookTriggerType.valueOf(jsonHook.getString(HOOK_TRIGGER_TYPE).toUpperCase()));
            }
            catch(IllegalArgumentException e) {
                log.warn("Listener " + listenerId + " for target " + target + " has an invalid trigger type " + jsonHook.getString(HOOK_TRIGGER_TYPE) + " and will not be registred!", e);
                return;
            }
        }

        extractAndAddStaticHeadersToHook(jsonHook, hook);

        { // Set expiration time
            final String expirationTimeExpression = storageObject.getString(EXPIRATION_TIME);

            if (expirationTimeExpression == null) {
                log.debug("Register listener and route " + target + " with infinite expiration.");
                hook.setExpirationTime(null);
            } else {
                LocalDateTime expirationTime;
                try {
                    expirationTime = ExpiryCheckHandler.parseDateTime(expirationTimeExpression);
                } catch (RuntimeException e) {
                    log.warn("Listener " + listenerId + " for target " + target + " has an invalid expiration time " + expirationTimeExpression + " and will not be registred!", e);
                    return;
                }
                log.debug("Register listener and route " + target + " with expiration at " + expirationTime);
                hook.setExpirationTime(expirationTime);
            }
        }

        hook.setFullUrl(jsonHook.getBoolean(FULL_URL, false));
        hook.setQueueingStrategy(QueueingStrategyFactory.buildQueueStrategy(jsonHook));

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
     * Extract staticHeaders attribute from jsonHook and create a
     * appropriate list in the hook object.
     *
     * This is the same concept as in gateleen-rooting:
     * {@link org.swisspush.gateleen.routing.RuleFactory#setStaticHeaders(Rule, JsonObject)}}
     *
     * @param jsonHook the json hook
     * @param hook the hook object
     */
    private void extractAndAddStaticHeadersToHook(final JsonObject jsonHook, final HttpHook hook) {
        final JsonArray headers = jsonHook.getJsonArray("headers");
        if (headers != null) {
            final HeaderFunction headerFunction = HeaderFunctions.parseFromJson(headers);
            hook.setHeaderFunction(headerFunction);
            return;
        }

        // {@see org.swisspush.gateleen.routing.RuleFactory.setStaticHeaders()}
        // in previous Gateleen versions we only had the "staticHeaders" to unconditionally add headers with fix values
        // We now have a more dynamic concept of a "manipulator chain" - which is also configured different in JSON syntax
        // For backward compatibility we still parse the old "staticHeaders" - but now create a manipulator chain accordingly
        JsonObject staticHeaders = jsonHook.getJsonObject(STATIC_HEADERS);
        if (staticHeaders != null) {
            log.warn("you use the deprecated \"staticHeaders\" syntax in your hook (" + jsonHook+ "). Please migrate to the more flexible \"headers\" syntax");
            hook.setHeaderFunction(HeaderFunctions.parseStaticHeadersFromJson(staticHeaders));
        }
    }

    /**
     * Creates a listener id, which is unique for the given service, and the
     * monitored url.
     * 
     * @param requestUrl requestUrl
     * @return String
     */
    protected String getUniqueListenerId(String requestUrl) {
        StringBuilder listenerId = new StringBuilder();

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

        if (jsonHook.getInteger(QUEUE_EXPIRE_AFTER) != null ) {
            hook.setQueueExpireAfter(jsonHook.getInteger(QUEUE_EXPIRE_AFTER));
        }

        if ( jsonHook.getBoolean(LISTABLE) != null ) {
            hook.setListable(jsonHook.getBoolean(LISTABLE));
        }
        else {
            hook.setListable(listableRoutes);
        }

        if ( jsonHook.getBoolean(COLLECTION) != null ) {
            hook.setCollection(jsonHook.getBoolean(COLLECTION));
        }

        extractAndAddStaticHeadersToHook(jsonHook, hook);

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

        hook.setFullUrl(storageObject.getBoolean(FULL_URL, false));
        hook.setQueueingStrategy(QueueingStrategyFactory.buildQueueStrategy(storageObject));

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
        return new Route(vertx, userProfileStorage, loggingResourceManager, monitoringHandler, userProfilePath, hook, urlPattern, selfClient);
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
        // find the /_hooks/listeners/ identifier ...
        int pos = requestUrl.indexOf(HOOKS_LISTENERS_URI_PART);
        // ... and use substring after it as segment
        String segment = requestUrl.substring(pos + HOOKS_LISTENERS_URI_PART.length());

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

    /**
     * @param request
     *      Request to extract the value from. This instance gets manipulated
     *      internally during call.
     * @return
     *      Expiration time or empty if infinite.
     */
    private static Optional<String> extractExpTimeAndManipulatePassedRequestAndReturnExpTime(HttpServerRequest request) {
        final int expireAfter = ExpiryCheckHandler.getExpireAfterConcerningCaseOfCorruptHeaderAndInfinite(request.headers())
                .orElse(DEFAULT_HOOK_STORAGE_EXPIRE_AFTER_TIME);
        final String expirationTime = ExpiryCheckHandler.getExpirationTimeAsString(expireAfter)
                .orElse(null);

        // Update the PUT header
        ExpiryCheckHandler.setExpireAfter(request, expireAfter);

        return Optional.ofNullable(expirationTime);
    }
}

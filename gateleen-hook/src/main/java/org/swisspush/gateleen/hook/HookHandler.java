package org.swisspush.gateleen.hook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.ValidationMessage;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.*;
import io.vertx.core.http.impl.headers.HeadersMultiMap;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.ProxyOptions;
import io.vertx.ext.web.RoutingContext;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.event.TrackableEventPublish;
import org.swisspush.gateleen.core.http.HeaderFunction;
import org.swisspush.gateleen.core.http.HeaderFunctions;
import org.swisspush.gateleen.core.http.HttpRequest;
import org.swisspush.gateleen.core.logging.LoggableResource;
import org.swisspush.gateleen.core.logging.RequestLogger;
import org.swisspush.gateleen.core.storage.ResourceStorage;
import org.swisspush.gateleen.core.util.*;
import org.swisspush.gateleen.hook.queueingstrategy.DefaultQueueingStrategy;
import org.swisspush.gateleen.hook.queueingstrategy.DiscardPayloadQueueingStrategy;
import org.swisspush.gateleen.hook.queueingstrategy.QueueingStrategy;
import org.swisspush.gateleen.hook.queueingstrategy.QueueingStrategyFactory;
import org.swisspush.gateleen.hook.queueingstrategy.ReducedPropagationQueueingStrategy;
import org.swisspush.gateleen.hook.reducedpropagation.ReducedPropagationManager;
import org.swisspush.gateleen.logging.LogAppenderRepository;
import org.swisspush.gateleen.logging.LoggingResourceManager;
import org.swisspush.gateleen.monitoring.MonitoringHandler;
import org.swisspush.gateleen.core.util.ExpiryCheckHandler;
import org.swisspush.gateleen.queue.queuing.QueueClient;
import org.swisspush.gateleen.queue.queuing.QueueProcessor;
import org.swisspush.gateleen.queue.queuing.RequestQueue;
import org.swisspush.gateleen.queue.queuing.splitter.NoOpQueueSplitter;
import org.swisspush.gateleen.queue.queuing.splitter.QueueSplitter;
import org.swisspush.gateleen.routing.Router;
import org.swisspush.gateleen.routing.Rule;
import org.swisspush.gateleen.routing.RuleFactory;
import org.swisspush.gateleen.validation.RegexpValidator;
import org.swisspush.gateleen.validation.ValidationException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static io.vertx.core.http.HttpMethod.*;
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
    private static final String LISTENER_HOOK_TARGET_PATH = "listeners/";

    public static final String HOOKS_ROUTE_URI_PART = "/_hooks/route";

    private static final String HOOK_STORAGE_PATH = "registrations/";
    private static final String HOOK_LISTENER_STORAGE_PATH = HOOK_STORAGE_PATH + "listeners/";
    private static final String HOOK_ROUTE_STORAGE_PATH = HOOK_STORAGE_PATH + "routes/";

    private static final String REMOVE_LISTENER_ADDRESS = "gateleen.hook-listener-remove";
    static final String SAVE_LISTENER_ADDRESS = "gateleen.hook-listener-insert";
    static final String SAVE_ROUTE_ADDRESS = "gateleen.hook-route-insert";
    static final String REMOVE_ROUTE_ADDRESS = "gateleen.hook-route-remove";
    private static final int STATUS_CODE_2XX = 2;

    private static final int DEFAULT_HOOK_STORAGE_EXPIRE_AFTER_TIME = 60 * 60; // 1h in seconds

    private static final int DEFAULT_CLEANUP_TIME = 15000; // 15 seconds
    public static final String REQUESTURL = "requesturl";
    public static final String EXPIRATION_TIME = "expirationTime";
    public static final String HOOK = "hook";
    public static final String TRANSLATE_STATUS = "translateStatus";
    public static final String METHODS = "methods";
    public static final String HEADERS_FILTER = "headersFilter";
    public static final String DESTINATION = "destination";
    public static final String FILTER = "filter";
    public static final String QUEUE_EXPIRE_AFTER = "queueExpireAfter";
    public static final String STATIC_HEADERS = "staticHeaders";
    public static final String FULL_URL = "fullUrl";
    public static final String DISCARD_PAYLOAD = "discardPayload";
    public static final String HOOK_TRIGGER_TYPE = "type";
    public static final String LISTABLE = "listable";
    public static final String COLLECTION = "collection";

    private static final String CONTENT_TYPE_JSON = "application/json";
    private static final String LISTENERS_KEY = "listeners";
    private static final String ROUTES_KEY = "routes";

    private final Comparator<String> collectionContentComparator;
    private static final Logger log = LoggerFactory.getLogger(HookHandler.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final JsonSchemaFactory JSON_SCHEMA_FACTORY = JsonSchemaFactory.getInstance();

    private final Vertx vertx;
    private final ResourceStorage userProfileStorage;
    private final ResourceStorage hookStorage;
    private final MonitoringHandler monitoringHandler;
    private final LoggingResourceManager loggingResourceManager;
    private final LogAppenderRepository logAppenderRepository;
    private final HttpClient selfClient;
    private final String userProfilePath;
    private final String hookRootUri;
    private final boolean listableRoutes;
    private final ListenerRepository listenerRepository;
    final RouteRepository routeRepository;
    private final RequestQueue requestQueue;
    private final TrackableEventPublish trackableEventPublish;

    private final ReducedPropagationManager reducedPropagationManager;

    private boolean logHookConfigurationResourceChanges = false;

    private final Handler<Void> doneHandler;

    private final JsonSchema jsonSchemaHook;
    private int routeMultiplier;

    private final QueueSplitter queueSplitter;
    private final String routeBase;
    private final String listenerBase;
    private final String normalizedRouteBase;
    private final String normalizedListenerBase;

    private final AtomicLong listenerCount = new AtomicLong(0);
    private final AtomicLong routesCount = new AtomicLong(0);
    private MeterRegistry meterRegistry;

    /**
     * Creates a new HookHandler.
     *
     * @param vertx                  vertx
     * @param selfClient             selfClient
     * @param storage                storage
     * @param loggingResourceManager loggingResourceManager
     * @param monitoringHandler      monitoringHandler
     * @param userProfilePath        userProfilePath
     * @param hookRootUri            hookRootUri
     */
    public HookHandler(Vertx vertx, HttpClient selfClient, final ResourceStorage storage,
                       LoggingResourceManager loggingResourceManager, LogAppenderRepository logAppenderRepository, @Nullable MonitoringHandler monitoringHandler,
                       String userProfilePath, String hookRootUri) {
        this(vertx, selfClient, storage, loggingResourceManager, logAppenderRepository, monitoringHandler, userProfilePath, hookRootUri,
                new QueueClient(vertx, monitoringHandler));
    }


    /**
     * Creates a new HookHandler.
     *
     * @param vertx                  vertx
     * @param selfClient             selfClient
     * @param storage                storage
     * @param loggingResourceManager loggingResourceManager
     * @param monitoringHandler      monitoringHandler
     * @param userProfilePath        userProfilePath
     * @param hookRootUri            hookRootUri
     * @param requestQueue           requestQueue
     */
    public HookHandler(Vertx vertx, HttpClient selfClient, final ResourceStorage storage,
                       LoggingResourceManager loggingResourceManager, LogAppenderRepository logAppenderRepository, @Nullable MonitoringHandler monitoringHandler,
                       String userProfilePath, String hookRootUri, RequestQueue requestQueue) {
        this(vertx, selfClient, storage, loggingResourceManager, logAppenderRepository, monitoringHandler, userProfilePath, hookRootUri,
                requestQueue, false);
    }

    public HookHandler(Vertx vertx, HttpClient selfClient, final ResourceStorage storage,
                       LoggingResourceManager loggingResourceManager, LogAppenderRepository logAppenderRepository, @Nullable MonitoringHandler monitoringHandler,
                       String userProfilePath, String hookRootUri, RequestQueue requestQueue, boolean listableRoutes) {
        this(vertx, selfClient, storage, loggingResourceManager, logAppenderRepository, monitoringHandler, userProfilePath, hookRootUri,
                requestQueue, false, null);
    }

    /**
     * Creates a new HookHandler.
     *
     * @param vertx                     vertx
     * @param selfClient                selfClient
     * @param storage                   storage
     * @param loggingResourceManager    loggingResourceManager
     * @param monitoringHandler         monitoringHandler
     * @param userProfilePath           userProfilePath
     * @param hookRootUri               hookRootUri
     * @param requestQueue              requestQueue
     * @param listableRoutes            listableRoutes
     * @param reducedPropagationManager reducedPropagationManager
     */
    public HookHandler(Vertx vertx, HttpClient selfClient, final ResourceStorage storage,
                       LoggingResourceManager loggingResourceManager, LogAppenderRepository logAppenderRepository, @Nullable MonitoringHandler monitoringHandler,
                       String userProfilePath, String hookRootUri, RequestQueue requestQueue, boolean listableRoutes,
                       @Nullable ReducedPropagationManager reducedPropagationManager) {
        this(vertx, selfClient, storage, loggingResourceManager, logAppenderRepository, monitoringHandler, userProfilePath, hookRootUri,
                requestQueue, listableRoutes, reducedPropagationManager, null, storage);
    }

    public HookHandler(Vertx vertx, HttpClient selfClient, final ResourceStorage userProfileStorage,
                       LoggingResourceManager loggingResourceManager, LogAppenderRepository logAppenderRepository, @Nullable MonitoringHandler monitoringHandler,
                       String userProfilePath, String hookRootUri, RequestQueue requestQueue, boolean listableRoutes,
                       ReducedPropagationManager reducedPropagationManager, @Nullable Handler doneHandler, ResourceStorage hookStorage) {
        this(vertx, selfClient, userProfileStorage, loggingResourceManager, logAppenderRepository, monitoringHandler, userProfilePath, hookRootUri,
                requestQueue, listableRoutes, reducedPropagationManager, doneHandler, hookStorage, Router.DEFAULT_ROUTER_MULTIPLIER);
    }

    public HookHandler(Vertx vertx, HttpClient selfClient, final ResourceStorage userProfileStorage,
                       LoggingResourceManager loggingResourceManager, LogAppenderRepository logAppenderRepository, @Nullable MonitoringHandler monitoringHandler,
                       String userProfilePath, String hookRootUri, RequestQueue requestQueue, boolean listableRoutes,
                       ReducedPropagationManager reducedPropagationManager, @Nullable Handler doneHandler, ResourceStorage hookStorage,
                       int routeMultiplier) {
        this(vertx, selfClient, userProfileStorage, loggingResourceManager, logAppenderRepository, monitoringHandler, userProfilePath, hookRootUri,
                requestQueue, listableRoutes, reducedPropagationManager, doneHandler, hookStorage, routeMultiplier, new NoOpQueueSplitter());
    }

    /**
     * Creates a new HookHandler.
     *
     * @param vertx                     vertx
     * @param selfClient                selfClient
     * @param userProfileStorage        userProfileStorage - where the user profiles are stored
     * @param loggingResourceManager    loggingResourceManager
     * @param monitoringHandler         monitoringHandler
     * @param userProfilePath           userProfilePath
     * @param hookRootUri               hookRootUri
     * @param requestQueue              requestQueue
     * @param listableRoutes            listableRoutes
     * @param reducedPropagationManager reducedPropagationManager
     * @param doneHandler               doneHandler
     * @param hookStorage               hookStorage - where the hooks are stored
     * @param routeMultiplier           the multiplier that is applied to routes, this is typically the number of nodes in
     *                                  a cluster multiplied by the number of router instances within a node. Or in other words
     *                                  the number of {@link Router} instances within a cluster
     * @param queueSplitter             Configured QueueSplitter or NoOpQueueSplitter which dispatches to subqueues for
     *                                  parallel operation.
     */
    public HookHandler(Vertx vertx, HttpClient selfClient, final ResourceStorage userProfileStorage,
                       LoggingResourceManager loggingResourceManager, LogAppenderRepository logAppenderRepository, @Nullable MonitoringHandler monitoringHandler,
                       String userProfilePath, String hookRootUri, RequestQueue requestQueue, boolean listableRoutes,
                       ReducedPropagationManager reducedPropagationManager, @Nullable Handler doneHandler, ResourceStorage hookStorage,
                       int routeMultiplier, @Nonnull QueueSplitter queueSplitter) {
        log.debug("Creating HookHandler ...");
        this.vertx = vertx;
        this.selfClient = selfClient;
        this.userProfileStorage = userProfileStorage;
        this.loggingResourceManager = loggingResourceManager;
        this.logAppenderRepository = logAppenderRepository;
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
        this.routeMultiplier = routeMultiplier;
        this.queueSplitter = queueSplitter;
        String hookSchema = ResourcesUtils.loadResource("gateleen_hooking_schema_hook", true);
        jsonSchemaHook = JSON_SCHEMA_FACTORY.getSchema(hookSchema);
        this.listenerBase = hookRootUri + HOOK_LISTENER_STORAGE_PATH;
        this.routeBase = hookRootUri + HOOK_ROUTE_STORAGE_PATH;
        this.normalizedListenerBase = this.listenerBase.replaceAll("/+$", "");
        this.normalizedRouteBase = this.routeBase.replaceAll("/+$", "");
        this.trackableEventPublish = new TrackableEventPublish(vertx);

    }

    public void init() {
        // add all init methods here (!)
        final List<Consumer<Handler<Void>>> initMethods = new ArrayList<>();
        initMethods.add(this::registerListenerRegistrationHandler);
        initMethods.add(this::registerRouteRegistrationHandler);
        initMethods.add(this::loadStoredListeners);
        initMethods.add(this::loadStoredRoutes);
        initMethods.add(this::registerCleanupHandler);
        initMethods.add(this::registerRouteMultiplierChangeHandler);

        // ready handler, calls the doneHandler when everything is done and the HookHandler is ready to use
        Handler<Void> readyHandler = new Handler<>() {
            // count of methods with may return an OK (ready)
            private final AtomicInteger readyCounter = new AtomicInteger(initMethods.size());

            @Override
            public void handle(Void aVoid) {
                if (readyCounter.decrementAndGet() == 0) {
                    log.info("HookHandler is ready!");
                    if (doneHandler != null) {
                        doneHandler.handle(null);
                    }
                }
            }
        };

        initMethods.forEach(handlerConsumer -> handlerConsumer.accept(readyHandler));
    }

    public void setMeterRegistry(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        if(meterRegistry != null) {
            Gauge.builder("gateleen.listener.count", listenerCount, AtomicLong::get)
                    .description("Amount of listener hooks currently registered").register(meterRegistry);
            Gauge.builder("gateleen.routes.count", routesCount, AtomicLong::get)
                    .description("Amount of route hooks currently registered").register(meterRegistry);
        }
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
        vertx.setPeriodic(DEFAULT_CLEANUP_TIME, timerID -> {
            log.trace("Running hook cleanup ...");

            DateTime now = DateTime.now();

            // Loop through listeners first
            for (Listener listener : listenerRepository.getListeners()) {

                final Optional<DateTime> expirationTime = listener.getHook().getExpirationTime();
                if (expirationTime.isEmpty()) {
                    if (log.isTraceEnabled()) {
                        log.trace("Listener {} will never expire.", listener.getListenerId());
                    }
                } else if (expirationTime.get().isBefore(now)) {
                    log.debug("Listener {} expired at {} and current time is {}", listener.getListenerId(), expirationTime.get(), now);
                    listenerRepository.removeListener(listener.getListenerId());
                    routeRepository.removeRoute(hookRootUri + LISTENER_HOOK_TARGET_PATH + listener.getListenerId());
                }
            }

            // Loop through routes
            Map<String, Route> routes = routeRepository.getRoutes();
            for (String key : routes.keySet()) {
                Route route = routes.get(key);
                final Optional<DateTime> expirationTime = route.getHook().getExpirationTime();
                if (expirationTime.isEmpty()) {
                    if (log.isTraceEnabled()) {
                        log.trace("Route {} will never expire.", key);
                    }
                } else if (expirationTime.get().isBefore(now)) {
                    routeRepository.removeRoute(key);
                }
            }
            if(meterRegistry != null) {
                listenerCount.set(listenerRepository.size());
                routesCount.set(routeRepository.getRoutes().size());
            }

            if(monitoringHandler != null) {
                monitoringHandler.updateListenerCount(listenerRepository.size());
                monitoringHandler.updateRoutesCount(routeRepository.getRoutes().size());
            }
            log.trace("done");
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

        hookStorage.get(routeBase, buffer -> {
            if (buffer != null) {
                JsonObject listOfRoutes = new JsonObject(buffer.toString());
                JsonArray routeNames = listOfRoutes.getJsonArray("routes");
                Iterator<String> keys = routeNames.getList().iterator();

                final AtomicInteger storedRoutesCount = new AtomicInteger(routeNames.getList().size());

                // go through the routes ...
                while (keys.hasNext()) {
                    final String key = keys.next();

                    // ... and load each one
                    hookStorage.get(routeBase + key, routeBody -> {
                        if (routeBody != null) {
                            registerRoute(routeBody);
                        } else {
                            log.warn("Could not get URL '{}' (getting hook route).", routeBase + key);
                        }

                        // send a ready flag
                        if (storedRoutesCount.decrementAndGet() == 0) {
                            readyHandler.handle(null);
                        }
                    });
                }
            } else {
                log.warn("Could not get URL '{}' (getting hook route).", routeBase);
                // send a ready flag
                readyHandler.handle(null);
            }
        });
    }

    /**
     * Loads the stored listeners
     * from the resource hookStorage, if
     * any are available.
     *
     * @param readyHandler - the ready handler
     */
    private void loadStoredListeners(final Handler<Void> readyHandler) {
        log.debug("loadStoredListeners");

        hookStorage.get(listenerBase, buffer -> {
            if (buffer != null) {
                JsonObject listOfListeners = new JsonObject(buffer.toString());
                JsonArray listenerNames = listOfListeners.getJsonArray("listeners");
                Iterator<String> keys = listenerNames.getList().iterator();

                final AtomicInteger storedListenerCount = new AtomicInteger(listenerNames.getList().size());

                // go through the listeners ...
                while (keys.hasNext()) {
                    final String key = keys.next();

                    // ... and load each one
                    hookStorage.get(listenerBase + key, listenerBody -> {
                        if (listenerBody != null) {
                            registerListener(listenerBody);
                        } else {
                            log.warn("Could not get URL '{}' (getting hook listener).", listenerBase + key);
                        }

                        // send a ready flag
                        if (storedListenerCount.decrementAndGet() == 0) {
                            readyHandler.handle(null);
                        }
                    });
                }
            } else {
                log.warn("Could not get URL '{}' (getting hook listener).", listenerBase);
                // send a ready flag
                readyHandler.handle(null);
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
        trackableEventPublish.consumer(vertx, SAVE_ROUTE_ADDRESS, event -> hookStorage.get(event, buffer -> {
            if (buffer != null) {
                registerRoute(buffer);
            } else {
                log.warn("Could not get URL '{}' (getting hook route).", (event == null ? "<null>" : event));
            }
        }));

        // Receive listener remove notifications
        trackableEventPublish.consumer(vertx, REMOVE_ROUTE_ADDRESS, this::unregisterRoute);

        // method done / no async processing pending
        readyHandler.handle(null);
    }

    /**
     * Update all registered router's pool size multiplier
     *
     * @param readyHandler - the ready handler
     */
    private void registerRouteMultiplierChangeHandler(Handler<Void> readyHandler) {
        vertx.eventBus().consumer(Router.ROUTE_MULTIPLIER_ADDRESS, (Handler<Message<String>>) event -> {
            log.info("Updating route multiplier: {}", (event.body() == null ? "<null>" : event.body()));
            try {
                routeMultiplier = Integer.parseInt(event.body());
            } catch (NumberFormatException e) {
                log.info("failed to parse route multiplier: {}", event.body(), e);
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
        trackableEventPublish.consumer(vertx, SAVE_LISTENER_ADDRESS, event -> hookStorage.get(event, buffer -> {
            if (buffer != null) {
                registerListener(buffer);
            } else {
                log.warn("Could not get URL '{}' (getting hook listener).", (event == null ? "<null>" : event));
            }
        }));

        // Receive listener remove notifications
        trackableEventPublish.consumer(vertx, REMOVE_LISTENER_ADDRESS, this::unregisterListener);

        // method done / no async processing pending
        readyHandler.handle(null);
    }

    /**
     * Handles requests, which are either listener or
     * route related.
     * Takes on:
     * <ul>
     * <li>hook un-/registration</li>
     * <li>enqueueing a request for the registered listeners</li>
     * <li>forwarding a request to the reistered listeners</li>
     * <li>creating a self request for the original request (if necessary)</li>
     * </ul>
     *
     * @param ctx routing context
     * @return true if a request is processed by the handler, otherwise false
     */
    public boolean handle(final RoutingContext ctx) {
        HttpServerRequest request = ctx.request();
        boolean consumed = false;
        var requestUri = request.uri();
        /*
         * 1) Un- / Register Listener / Routes
         */
        var requestMethod = request.method();
        if (requestMethod == PUT) {
            if (requestUri.contains(HOOKS_LISTENERS_URI_PART)) {
                handleListenerRegistration(request);
                return true;
            }
            if (requestUri.contains(HOOKS_ROUTE_URI_PART)) {
                handleRouteRegistration(request);
                return true;
            }
        }
        if (requestMethod == DELETE) {
            if (requestUri.contains(HOOKS_LISTENERS_URI_PART)) {
                handleListenerUnregistration(request);
                return true;
            }
            if (requestUri.contains(HOOKS_ROUTE_URI_PART)) {
                handleRouteUnregistration(request);
                return true;
            }
        }

        if (requestMethod == GET && null != request.getParam("q")) {
            if (requestUri.contains(normalizedListenerBase) ) {
                handleListenerSearch(request);
                return true;
            } else if (requestUri.contains(normalizedRouteBase) ) {
                handleRouteSearch(request);
                return true;
            }
        }

        /*
         * 2) Check if we have to queue a request for listeners
         */
        final List<Listener> listeners = listenerRepository.findListeners(request.uri(), request.method().name(), request.headers());

        if (!listeners.isEmpty() && !isRequestAlreadyHooked(request)) {
            installBodyHandler(ctx, listeners);
            consumed = true;
        }

        if (!consumed) {
            consumed = routeRequestIfNeeded(ctx);

            if (!consumed) {
                return createListingIfRequested(request);
            }

            return consumed;
        } else {
            return true;
        }
    }

    private void handleListenerSearch(HttpServerRequest request) {
        handleSearch(
                listenerRepository.getListeners().stream().collect(Collectors.toMap(Listener::getListenerId, listener -> listener)),
                listener -> listener.getHook().getDestination(),
                LISTENERS_KEY,
                request
        );
    }

    private void handleRouteSearch(HttpServerRequest request) {
        handleSearch(
                routeRepository.getRoutes().entrySet().stream().collect(Collectors.toMap(entry -> entry.getValue().getHookDisplayText(), Map.Entry::getValue)),
                route -> route.getHook().getDestination(),
                ROUTES_KEY,
                request
        );
    }

    /**
     * Search the repository for items matching the query parameter.
     * Output a JSON response with the matched results.
     * If parameter queryParam is empty or null a 400 Bad Request is returned.
     * All params cannot be null
     * @param repository The items to search .
     * @param getDestination Function to extract destinations.
     * @param resultKey The key for the result in the response.
     * @param request The HTTP request to make a specific validations and return the results.
     */
    private <T> void handleSearch(Map<String, T> repository, Function<T, String> getDestination, String resultKey, HttpServerRequest request) {
        String queryParam = request.getParam("q");
        if (request.params().size() > 1 || StringUtils.isEmpty(queryParam)) {
            request.response().setStatusCode(StatusCode.BAD_REQUEST.getStatusCode());
            request.response().setStatusMessage(StatusCode.BAD_REQUEST.getStatusMessage());
            request.response().end("Only the 'q' parameter is allowed and can't be empty or null");
            return ;
        }

        JsonArray matchingResults = new JsonArray();
        repository.forEach((key, value) -> {
            String destination = getDestination.apply(value);
            if (destination != null && destination.contains(queryParam)) {
                matchingResults.add(convertToStoragePattern(key));
            }
        });

        JsonObject result = new JsonObject();
        result.put(resultKey, matchingResults);

        String encodedResult = result.encode();

        request.response().putHeader(HttpHeaders.CONTENT_TYPE, CONTENT_TYPE_JSON);
        request.response().end(encodedResult);
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
        if (request.method().equals(HttpMethod.GET) && !routesListed) {
            // route collection available for parent?
            final List<String> collections = new ArrayList<>(routeRepository.getCollections(request.uri()));

            if (!collections.isEmpty()) {
                String parentUri = request.uri().contains("?") ? request.uri().substring(0, request.uri().indexOf('?')) : request.uri();
                final String parentCollection = getCollectionName(parentUri);

                // sort the result array
                collections.sort(collectionContentComparator);

                if (log.isTraceEnabled()) {
                    log.trace("createListingIfRequested > (parentUri) {}, (parentCollection) {}", parentUri, parentCollection);
                }

                selfClient.request(request.method(), request.uri()).onComplete(asyncReqResult -> {
                    if (asyncReqResult.failed()) {
                        log.warn("Failed request to {}: {}", request.uri(), asyncReqResult.cause());
                        return;
                    }
                    HttpClientRequest selfRequest = asyncReqResult.result();


                    if (request.headers() != null && !request.headers().isEmpty()) {
                        selfRequest.headers().setAll(request.headers());
                    }

                    // mark request as already listed
                    selfRequest.headers().add(HOOK_ROUTES_LISTED, "true");

                    selfRequest.exceptionHandler(exception -> log.warn("HookHandler: listing of collections (routes) failed: {}: {}", request.uri(), exception.getMessage()));
                    selfRequest.idleTimeout(120000); // avoids blocking other requests
                    selfRequest.send(asyncResult -> {
                        HttpClientResponse response = asyncResult.result();
                        HttpServerRequestUtil.prepareResponse(request, response);

                        request.response().headers().remove(HOOK_ROUTES_LISTED);

                        // if everything is fine, we add the listed collections to the given array
                        if (response.statusCode() == StatusCode.OK.getStatusCode()) {
                            if (log.isTraceEnabled()) {
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

                                if (log.isTraceEnabled()) {
                                    log.trace("createListingIfRequested > response: {}", responseObject);
                                }

                                // write the response
                                request.response().write(Buffer.buffer(responseObject.toString()));
                            });
                        }
                        // if nothing is found, we create a new array
                        else if (response.statusCode() == StatusCode.NOT_FOUND.getStatusCode()) {
                            if (log.isTraceEnabled()) {
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

                                if (log.isTraceEnabled()) {
                                    log.trace("createListingIfRequested > response: {}", responseObject);
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
                });
                // consumed
                return true;
            }
        }

        // not consumed
        return false;
    }

    private String getCollectionName(String url) {
        if (url.endsWith("/")) {
            url = url.substring(0, url.lastIndexOf("/"));
        }

        return url.substring(url.lastIndexOf("/") + 1, url.length());
    }

    private boolean routeRequestIfNeeded(RoutingContext ctx) {
        Route route = routeRepository.getRoute(ctx.request().uri());

        if (doMethodsMatch(route, ctx) && doHeadersMatch(route, ctx)) {
            log.debug("Forward request {}", ctx.request().uri());
            route.forward(ctx);
            return true;
        } else {
            return false;
        }
    }

    private boolean doMethodsMatch(Route route, RoutingContext ctx) {
        return route != null &&
                (route.getHook().getMethods().isEmpty() || route.getHook().getMethods().contains(ctx.request().method().name()));
    }

    private boolean doHeadersMatch(Route route, RoutingContext ctx) {
        if (route == null) {
            return false;
        }
        if (route.getHook().getHeadersFilterPattern() == null) {
            return true;
        }

        Pattern headersFilterPattern = route.getHook().getHeadersFilterPattern();
        log.debug("Looking for request headers with pattern {}", headersFilterPattern.pattern());
        return HttpHeaderUtil.hasMatchingHeader(ctx.request().headers(), headersFilterPattern);
    }

    private void installBodyHandler(final RoutingContext ctx, final List<Listener> listeners) {
        // Read the original request and queue a new one for every listener
        ctx.request().bodyHandler(buffer -> {
            // Create separate lists with filtered listeners
            List<Listener> beforeListener = getFilteredListeners(listeners, HookTriggerType.BEFORE);
            List<Listener> afterListener = getFilteredListeners(listeners, HookTriggerType.AFTER);

            // Create handlers for before/after - cases
            Handler<Void> afterHandler = installAfterHandler(ctx, buffer, afterListener);
            Handler<Void> beforeHandler = installBeforeHandler(ctx, buffer, beforeListener, afterHandler);

            // call the listeners (before)
            callListener(ctx, buffer, beforeListener, beforeHandler);
        });
    }

    /**
     * Calls the passed listeners and passes the given handler to the enqueued listener requests.
     *
     * @param ctx               original request context
     * @param buffer            buffer
     * @param filteredListeners all listeners which should be called
     * @param handler           the handler, which should handle the requests
     */
    private void callListener(RoutingContext ctx, final Buffer buffer, final List<Listener> filteredListeners, final Handler<Void> handler) {
        HttpServerRequest request = ctx.request();
        for (Listener listener : filteredListeners) {
            log.debug("Enqueue request matching {} {} with listener {}", request.method(), listener.getMonitoredUrl(), listener.getListener());

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
                log.debug(" > internal target: {}", targetUri);
            }
            // external
            else {
                targetUri = hookRootUri + LISTENER_HOOK_TARGET_PATH + listener.getListener() + path;
                log.debug(" > external target: {}", targetUri);
            }

            // Create a new multimap, copied from the original request,
            // so that the original request is not overridden with the new values.
            HeadersMultiMap queueHeaders = new HeadersMultiMap();
            queueHeaders.addAll(request.headers());

            // Apply the header manipulation chain - errors (unresolvable references) will just be WARN logged - but we still enqueue
            final HeaderFunctions.EvalScope evalScope = listener.getHook().getHeaderFunction().apply(queueHeaders);
            if (evalScope.getErrorMessage() != null) {
                log.warn("problem applying header manipulator chain {} in listener {}", evalScope.getErrorMessage(), listener.getListenerId());
            }

            if (ExpiryCheckHandler.getQueueExpireAfter(queueHeaders) == null && listener.getHook().getQueueExpireAfter() != -1) {
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
            queue = queueSplitter.convertToSubQueue(queue, request);

            QueueingStrategy queueingStrategy = listener.getHook().getQueueingStrategy();

            if (queueingStrategy instanceof DefaultQueueingStrategy) {
                requestQueue.enqueue(new HttpRequest(request.method(), targetUri, queueHeaders, buffer.getBytes()), queue, handler);
            } else if (queueingStrategy instanceof DiscardPayloadQueueingStrategy) {
                if (HttpRequestHeader.containsHeader(queueHeaders, CONTENT_LENGTH)) {
                    queueHeaders.set(CONTENT_LENGTH.getName(), "0");
                }
                requestQueue.enqueue(new HttpRequest(request.method(), targetUri, queueHeaders, null), queue, handler);
            } else if (queueingStrategy instanceof ReducedPropagationQueueingStrategy) {
                if (reducedPropagationManager != null) {
                    reducedPropagationManager.processIncomingRequest(request.method(), targetUri, queueHeaders, buffer,
                            queue, ((ReducedPropagationQueueingStrategy) queueingStrategy).getPropagationIntervalMs(), handler);
                } else {
                    log.error("ReducedPropagationQueueingStrategy without configured ReducedPropagationManager. " +
                            "Not going to handle (enqueue) anything!");
                }
            } else {
                log.error("QueueingStrategy '{}' is not handled. Could be an error, check the source code!",
                        queueingStrategy.getClass().getSimpleName());
            }
        }

        // if for e.g. the beforListeners are empty,
        // we have to ensure, that the original request
        // is executed. This way the after handler will
        // also be called properly.
        if (filteredListeners.isEmpty() && handler != null) {
            handler.handle(null);
        }
    }

    /**
     * This handler is called after the self request (original request) is performed
     * successfully.
     * The handler calls all listener (after), so this requests happen AFTER the original
     * request is performed.
     *
     * @param ctx           original request context
     * @param buffer        buffer
     * @param afterListener list of listeners which should be called after the original request
     * @return the after handler
     */
    private Handler<Void> installAfterHandler(final RoutingContext ctx, final Buffer buffer, final List<Listener> afterListener) {
        return event -> callListener(ctx, buffer, afterListener, null);
    }

    /**
     * This handler is called by the queueclient
     * for each listener (before).
     * The request  happens BEFORE the original request is
     * performed.
     *
     * @param ctx            original request context
     * @param buffer         buffer
     * @param beforeListener list of listeners which should be called before the original request
     * @param afterHandler   the handler for listeners which have to be called after the original request
     * @return the before handler
     */
    private Handler<Void> installBeforeHandler(final RoutingContext ctx, final Buffer buffer, final List<Listener> beforeListener, final Handler<Void> afterHandler) {
        return new Handler<>() {
            private AtomicInteger currentCount = new AtomicInteger(0);
            private boolean sent = false;

            @Override
            public void handle(Void event) {
                // If the last queued request is performed
                // the original request will be triggered.
                // Because this handler is called async. we
                // have to secure, that it is only executed
                // once.
                if ((currentCount.incrementAndGet() == beforeListener.size() || beforeListener.isEmpty()) && !sent) {
                    sent = true;

                    /*
                     * we should find exactly one or none route (first match rtl)
                     * routes will only be found for requests coming from
                     * enqueueing through the listener and only for external
                     * requests.
                     */
                    Route route = routeRepository.getRoute(ctx.request().uri());

                    if (doMethodsMatch(route, ctx) && doHeadersMatch(route, ctx)) {
                        log.debug("Forward request (consumed) {}", ctx.request().uri());
                        route.forward(ctx, buffer, afterHandler);
                    } else {
                        // mark the original request as hooked
                        ctx.request().headers().set(HOOKED_HEADER, "true");

                        /*
                         * self requests are only made for original
                         * requests which were consumed during the
                         * enqueueing process, therefore it is
                         * imperative to use isRequestAlreadyHooked(HttpServerRequest request)
                         * before calling the handle method of
                         * this class!
                         */
                        createSelfRequest(ctx.request(), buffer, afterHandler);
                    }
                }
            }
        };
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
        log.debug("handleRouteUnregistration > {}", request.uri());

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

            trackableEventPublish.publish(vertx, REMOVE_ROUTE_ADDRESS, request.uri());
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
        log.debug("handleRouteRegistration > {}", request.uri());

        request.bodyHandler(hookData -> {
            if (isHookJsonInvalid(request, hookData)) {
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
            DateTime expirationTime = ExpiryCheckHandler.getExpirationTime(expireAfter);

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
                    if (logHookConfigurationResourceChanges) {
                        RequestLogger.logRequest(vertx.eventBus(), request, status, buffer);
                    }
                    trackableEventPublish.publish(vertx, SAVE_ROUTE_ADDRESS, routeStorageUri);
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
        log.debug("handleListenerUnregistration > {}", request.uri());

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

            trackableEventPublish.publish(vertx, REMOVE_LISTENER_ADDRESS, request.uri());
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
        log.debug("handleListenerRegistration > {}", request.uri());

        request.bodyHandler(hookData -> {
            if (isListenerJsonInvalid(request, hookData)) {
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
            String destination = hook.getString(DESTINATION);
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
                log.debug("Hook {} expirationTime is {}.", request.uri(), expirationTime);
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
                    if (logHookConfigurationResourceChanges) {
                        RequestLogger.logRequest(vertx.eventBus(), request, status, buffer);
                    }
                    vertx.eventBus().publish(SAVE_LISTENER_ADDRESS, listenerStorageUri);
                    trackableEventPublish.publish(vertx, SAVE_LISTENER_ADDRESS, listenerStorageUri);
                } else {
                    request.response().setStatusCode(status);
                }
                request.response().end();
            });
        });
    }

    private boolean isListenerJsonInvalid(HttpServerRequest request, Buffer hookData) {
        if (isHookJsonInvalid(request, hookData)) {
            // No further checks required. hook definitively is invalid.
            return true;
        }

        final JsonObject hook;
        try {
            // Badly we need to parse that JSON one more time.
            hook = new JsonObject(hookData);
        } catch (DecodeException e) {
            log.error("Cannot decode JSON", e);
            badRequest(request, "Cannot decode JSON", e.getMessage());
            return true;
        }
        final JsonArray methods = hook.getJsonArray(METHODS);
        if (methods != null) {
            for (Object method : methods) {
                if (!QueueProcessor.httpMethodIsQueueable(HttpMethod.valueOf((String) method))) {
                    final String msg = "Listener registration request tries to hook for not allowed '" + method + "' method.";
                    log.error(msg);
                    badRequest(request, "Bad Request", msg + "\n");
                    return true;
                }
            }
        }
        return false;
    }

    public boolean isHookJsonInvalid(HttpServerRequest request, Buffer hookData) {
        try {
            JsonNode hook = OBJECT_MAPPER.readTree(hookData.getBytes());
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
     * @param request     - consumed request
     * @param requestBody - copy of request body
     */
    private void createSelfRequest(final HttpServerRequest request, final Buffer requestBody, final Handler<Void> afterHandler) {
        log.debug("Create self request for {}", request.uri());

        selfClient.request(request.method(), request.uri()).onComplete(asyncReqResult -> {
            if (asyncReqResult.failed()) {
                log.warn("Failed request to {}: {}", request.uri(), asyncReqResult.cause());
                return;
            }
            HttpClientRequest selfRequest = asyncReqResult.result();


            if (request.headers() != null && !request.headers().isEmpty()) {
                selfRequest.headers().setAll(request.headers());
            }

            selfRequest.exceptionHandler(exception -> log.warn("HookHandler HOOK_ERROR: Failed self request to {}: {}", request.uri(), exception.getMessage()));

            selfRequest.idleTimeout(120000); // avoids blocking other requests

            Handler<AsyncResult<HttpClientResponse>> asyncResultHandler = asyncResult -> {
                HttpClientResponse response = asyncResult.result();
                /*
                 * it shouldn't matter if the request is
                 * already consumed to write a response.
                 */

                HttpServerRequestUtil.prepareResponse(request, response);

                response.handler(data -> request.response().write(data));

                response.endHandler(v -> request.response().end());

                // if everything is fine, we call the after handler
                if ((response.statusCode() / 100) == STATUS_CODE_2XX) {
                    afterHandler.handle(null);
                }
            };

            if (requestBody != null) {
                selfRequest.send(requestBody, asyncResultHandler);
            } else {
                selfRequest.send(asyncResultHandler);
            }
        });
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
        return hooked != null && hooked.equals("true");
    }

    /**
     * Removes the route from the repository.
     *
     * @param requestUrl requestUrl
     */
    private void unregisterRoute(String requestUrl) {
        String routedUrl = getRoutedUrlSegment(requestUrl);

        log.debug("Unregister route {}", routedUrl);

        routeRepository.removeRoute(routedUrl);
        if(meterRegistry != null) {
            routesCount.set(routeRepository.getRoutes().size());
        }
        if(monitoringHandler != null) {
            monitoringHandler.updateRoutesCount(routeRepository.getRoutes().size());
        }
    }

    /**
     * Removes the listener and its route from the repository.
     *
     * @param requestUrl
     */
    private void unregisterListener(String requestUrl) {
        String listenerId = getUniqueListenerId(requestUrl);

        log.debug("Unregister listener {}", listenerId);

        routeRepository.removeRoute(hookRootUri + LISTENER_HOOK_TARGET_PATH + getListenerUrlSegment(requestUrl));
        listenerRepository.removeListener(listenerId);
        if(meterRegistry != null) {
            listenerCount.set(listenerRepository.size());
        }
        if(monitoringHandler != null) {
            monitoringHandler.updateListenerCount(listenerRepository.size());
        }
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
            log.trace("Request URL: {}", requestUrl);
        }

        // target = "http/colin/1234578" or destination url for internal forwarder (set later by if statement)
        String target = getListenerUrlSegment(requestUrl);

        // needed to identify listener
        String listenerId = getUniqueListenerId(requestUrl);

        if (log.isTraceEnabled()) {
            log.trace("Target (1st): {}", target);
        }

        // create and add a new Forwarder (or replace an already existing forwarder)
        JsonObject jsonHook = storageObject.getJsonObject(HOOK);
        JsonArray jsonMethods = jsonHook.getJsonArray(METHODS);


        HttpHook hook = new HttpHook(jsonHook.getString(DESTINATION));
        if (jsonMethods != null) {
            hook.setMethods(jsonMethods.getList());
        }

        String headersFilter = jsonHook.getString(HEADERS_FILTER);
        if (headersFilter != null) {
            try {
                Pattern headersFilterPattern = RegexpValidator.throwIfPatternInvalid(headersFilter);
                hook.setHeadersFilterPattern(headersFilterPattern);
            } catch (ValidationException e) {
                log.warn("Listener {} for target {} has an invalid headersFilter expression {} and will not be registered!",
                        listenerId, target, headersFilter);
                return;
            }
        }

        JsonObject jsonTranslateStatus = jsonHook.getJsonObject(TRANSLATE_STATUS);
        if (jsonTranslateStatus != null) {
            for (String pattern : jsonTranslateStatus.fieldNames()) {
                hook.addTranslateStatus(Pattern.compile(pattern), jsonTranslateStatus.getInteger(pattern));
            }
        }

        if (jsonHook.containsKey(FILTER)) {
            hook.setFilter(jsonHook.getString(FILTER));
        }

        if (jsonHook.getInteger(QUEUE_EXPIRE_AFTER) != null) {
            hook.setQueueExpireAfter(jsonHook.getInteger(QUEUE_EXPIRE_AFTER));
        }

        if (jsonHook.getString(HOOK_TRIGGER_TYPE) != null) {
            try {
                hook.setHookTriggerType(HookTriggerType.valueOf(jsonHook.getString(HOOK_TRIGGER_TYPE).toUpperCase()));
            } catch (IllegalArgumentException e) {
                log.warn("Listener " + listenerId + " for target " + target + " has an invalid trigger type " +
                        jsonHook.getString(HOOK_TRIGGER_TYPE) + " and will not be registered!", e);
                return;
            }
        }

        extractAndAddStaticHeadersToHook(jsonHook, hook);
        extractAndAddProxyOptionsToHook(jsonHook, hook);

        { // Set expiration time
            final String expirationTimeExpression = storageObject.getString(EXPIRATION_TIME);

            if (expirationTimeExpression == null) {
                log.debug("Register listener and route {} with infinite expiration.", target);
                hook.setExpirationTime(null);
            } else {
                DateTime expirationTime;
                try {
                    expirationTime = ExpiryCheckHandler.parseDateTime(expirationTimeExpression);
                } catch (RuntimeException e) {
                    log.warn("Listener " + listenerId + " for target " + target + " has an invalid expiration time " +
                            expirationTimeExpression + " and will not be registered!", e);
                    return;
                }
                log.debug("Register listener and route {} with expiration at {}", target, expirationTime);
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
            routeRepository.addRoute(urlPattern, createRoute(urlPattern, hook, requestUrl));

            if (log.isTraceEnabled()) {
                log.trace("external target, add route for urlPattern: {}", urlPattern);
            }
        }

        if (log.isTraceEnabled()) {
            log.trace("Target (2nd): {}", target);
        }

        // create and add a new listener (or update an already existing listener)
        listenerRepository.addListener(new Listener(listenerId, getMonitoredUrlSegment(requestUrl), target, hook));
        if(meterRegistry != null) {
            listenerCount.set(listenerRepository.size());
        }
        if(monitoringHandler != null) {
            monitoringHandler.updateListenerCount(listenerRepository.size());
        }
    }

    /**
     * Extract proxyOptions attribute from jsonHook and create a
     * appropriate property in the hook object.
     * <p>
     * This is the same concept as in gateleen-routing:
     * {@link org.swisspush.gateleen.routing.RuleFactory#setProxyOptions(Rule, JsonObject)}}
     */
    private void extractAndAddProxyOptionsToHook(final JsonObject jsonHook, final HttpHook hook) {
        JsonObject proxyOptions = jsonHook.getJsonObject("proxyOptions");
        if (proxyOptions != null) {
            hook.setProxyOptions(new ProxyOptions(proxyOptions));
        }
    }

    /**
     * Extract staticHeaders attribute from jsonHook and create a
     * appropriate list in the hook object.
     * <p>
     * This is the same concept as in gateleen-routing:
     * {@link org.swisspush.gateleen.routing.RuleFactory#setStaticHeaders(Rule, JsonObject)}}
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
            log.info("you use the deprecated \"staticHeaders\" syntax in your hook ({}). Please migrate to the more flexible \"headers\" syntax", jsonHook);
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

        log.debug("Register route to {}", routedUrl);

        // create and add a new Forwarder (or replace an already existing forwarder)
        JsonObject jsonHook = storageObject.getJsonObject(HOOK);
        JsonArray jsonMethods = jsonHook.getJsonArray(METHODS);

        HttpHook hook = new HttpHook(jsonHook.getString(DESTINATION));
        if (jsonMethods != null) {
            hook.setMethods(jsonMethods.getList());
        }

        String headersFilter = jsonHook.getString(HEADERS_FILTER);
        if (headersFilter != null) {
            try {
                Pattern headersFilterPattern = RegexpValidator.throwIfPatternInvalid(headersFilter);
                hook.setHeadersFilterPattern(headersFilterPattern);
            } catch (ValidationException e) {
                log.warn("Route {} has an invalid headersFilter expression {} and will not be registered!", routedUrl, headersFilter);
                return;
            }
        }

        JsonObject jsonTranslateStatus = jsonHook.getJsonObject(TRANSLATE_STATUS);
        if (jsonTranslateStatus != null) {
            for (String pattern : jsonTranslateStatus.fieldNames()) {
                hook.addTranslateStatus(Pattern.compile(pattern), jsonTranslateStatus.getInteger(pattern));
            }
        }

        if (jsonHook.getInteger(QUEUE_EXPIRE_AFTER) != null) {
            hook.setQueueExpireAfter(jsonHook.getInteger(QUEUE_EXPIRE_AFTER));
        }

        if (jsonHook.getBoolean(LISTABLE) != null) {
            hook.setListable(jsonHook.getBoolean(LISTABLE));
        } else {
            hook.setListable(listableRoutes);
        }

        if (jsonHook.getBoolean(COLLECTION) != null) {
            hook.setCollection(jsonHook.getBoolean(COLLECTION));
        }

        extractAndAddStaticHeadersToHook(jsonHook, hook);
        extractAndAddProxyOptionsToHook(jsonHook, hook);

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
                log.warn("Route {} has an invalid expiration time {} and will not be registered!", routedUrl, expirationTimeExpression);
                return;
            }
        } else {
            log.warn("Route {} has no expiration time and will not be registered!", routedUrl);
            return;
        }

        hook.setFullUrl(storageObject.getBoolean(FULL_URL, false));
        hook.setQueueingStrategy(QueueingStrategyFactory.buildQueueStrategy(storageObject));

        // Configure connection pool size
        Integer originalPoolSize = jsonHook.getInteger(HttpHook.CONNECTION_POOL_SIZE_PROPERTY_NAME);
        int appliedPoolSize;
        if (originalPoolSize != null) {
            if (routeMultiplier == 0) {
                log.error("Route multiplier is zero. Setting default value to one, Route: {}", routedUrl);
                routeMultiplier = 1;
            }
            appliedPoolSize = RuleFactory.evaluatePoolSize(originalPoolSize, routeMultiplier);
            log.debug("Original pool size is {}, applied size is {}", originalPoolSize, appliedPoolSize);
            hook.setConnectionPoolSize(appliedPoolSize);
        }

        hook.setMaxWaitQueueSize(jsonHook.getInteger(HttpHook.CONNECTION_MAX_WAIT_QUEUE_SIZE_PROPERTY_NAME));
        // Configure request timeout
        Integer timeout = jsonHook.getInteger(HttpHook.CONNECTION_TIMEOUT_SEC_PROPERTY_NAME);
        if (timeout != null) {
            hook.setTimeout(1000 * timeout);
        }

        boolean mustCreateNewRoute = true;

        Route existingRoute = routeRepository.getRoutes().get(routedUrl);
        if (existingRoute != null) {
            mustCreateNewRoute = mustCreateNewRouteForHook(existingRoute, hook);
        }
        if (mustCreateNewRoute) {
            routeRepository.addRoute(routedUrl, createRoute(routedUrl, hook, requestUrl));
        } else {
            // see comment in #mustCreateNewRouteForHook()
            existingRoute.getRule().setHeaderFunction(hook.getHeaderFunction());
            existingRoute.getHook().setExpirationTime(hook.getExpirationTime().orElse(null));
        }
        if(meterRegistry != null) {
            routesCount.set(routeRepository.getRoutes().size());
        }
        if(monitoringHandler != null) {
            monitoringHandler.updateRoutesCount(routeRepository.getRoutes().size());
        }
    }

    /**
     * check if an existing route must be thrown away because the new Hook does not match the config of the existing Route
     *
     * @param existingRoute
     * @param newHook
     * @return true if something is different between old existing Route and new hook
     */
    private boolean mustCreateNewRouteForHook(Route existingRoute, HttpHook newHook) {
        HttpHook oldHook = existingRoute.getHook();
        boolean same;
        same = Objects.equals(oldHook.getDestination(), newHook.getDestination());
        same &= Objects.equals(oldHook.getMethods(), newHook.getMethods());
        same &= Objects.equals(oldHook.getTranslateStatus(), newHook.getTranslateStatus());
        same &= oldHook.isCollection() == newHook.isCollection();
        same &= oldHook.isFullUrl() == newHook.isFullUrl();
        same &= oldHook.isListable() == newHook.isListable();
        same &= oldHook.isCollection() == newHook.isCollection();
        same &= oldHook.isCollection() == newHook.isCollection();
        same &= Objects.equals(oldHook.getConnectionPoolSize(), newHook.getConnectionPoolSize());
        same &= Objects.equals(oldHook.getMaxWaitQueueSize(), newHook.getMaxWaitQueueSize());
        same &= Objects.equals(oldHook.getTimeout(), newHook.getTimeout());
        same &= headersFilterPatternEquals(oldHook.getHeadersFilterPattern(), newHook.getHeadersFilterPattern());

        // queueingStrategy, filter, queueExpireAfter and hookTriggerType are not relevant for Route-Hooks
        // Though, headerFunction WOULD BE relevant - but we can't compare them for equality
        // so we simply set the new HeaderFunction to the exising Rule
        return !same;
    }

    private boolean headersFilterPatternEquals(Pattern headersFilterPatternLeft, Pattern headersFilterPatternRight) {
        if (headersFilterPatternLeft != null && headersFilterPatternRight != null) {
            return Objects.equals(headersFilterPatternLeft.pattern(), headersFilterPatternRight.pattern());
        }

        return headersFilterPatternLeft == null && headersFilterPatternRight == null;
    }

    /**
     * Creates a new dynamic routing for the given hook.
     *
     * @param urlPattern urlPattern
     * @param hook       hook
     * @param hookDisplayText text used for display only like in API
     * @return Route
     */
    private Route createRoute(String urlPattern, HttpHook hook, String hookDisplayText) {
        Route route = new Route(vertx, userProfileStorage, loggingResourceManager, logAppenderRepository, monitoringHandler,
                userProfilePath, hook, urlPattern, selfClient, hookDisplayText);
        route.setMeterRegistry(meterRegistry);
        return route;
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
        return requestUrl.substring(pos + HOOKS_LISTENERS_URI_PART.length());
    }

    /**
     * @param request Request to extract the value from. This instance gets manipulated
     *                internally during call.
     * @return Expiration time or empty if infinite.
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

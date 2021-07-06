package org.swisspush.gateleen.routing;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.*;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonObject;
import io.vertx.core.shareddata.LocalMap;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.configuration.ConfigurationResourceManager;
import org.swisspush.gateleen.core.configuration.ConfigurationResourceObserver;
import org.swisspush.gateleen.core.http.RequestLoggerFactory;
import org.swisspush.gateleen.core.logging.LoggableResource;
import org.swisspush.gateleen.core.logging.RequestLogger;
import org.swisspush.gateleen.core.refresh.Refreshable;
import org.swisspush.gateleen.core.storage.ResourceStorage;
import org.swisspush.gateleen.core.util.*;
import org.swisspush.gateleen.logging.LoggingResourceManager;
import org.swisspush.gateleen.monitoring.MonitoringHandler;
import org.swisspush.gateleen.validation.ValidationException;

import java.net.HttpCookie;
import java.util.*;

import static org.swisspush.gateleen.core.util.HttpServerRequestUtil.increaseRequestHops;
import static org.swisspush.gateleen.core.util.HttpServerRequestUtil.isRequestHopsLimitExceeded;
import static org.swisspush.gateleen.routing.Router.DefaultRouteType.*;

/**
 * @author https://github.com/lbovet [Laurent Bovet]
 */
public class Router implements Refreshable, LoggableResource, ConfigurationResourceObserver {

    /**
     * How long to let the http clients live before closing them after a re-configuration
     */
    private static final int GRACE_PERIOD = 30000;
    public static final String ROUTER_STATE_MAP = "router_state_map";
    public static final String ROUTER_BROKEN_KEY = "router_broken";
    public static final String REQUEST_HOPS_LIMIT_PROPERTY = "request.hops.limit";
    private final String rulesUri;
    private final String userProfileUri;
    private final String serverUri;
    private io.vertx.ext.web.Router router;
    private final LoggingResourceManager loggingResourceManager;
    private final MonitoringHandler monitoringHandler;
    private final Logger log = LoggerFactory.getLogger(Router.class);
    private final Logger cleanupLogger = LoggerFactory.getLogger(Router.class.getName() + "Cleanup");
    private final Vertx vertx;
    private final Set<HttpClient> httpClients = new HashSet<>();
    private final HttpClient selfClient;
    private final ResourceStorage storage;
    private final JsonObject info;
    private final Map<String, Object> properties;
    private final Handler<Void>[] doneHandlers;
    private final LocalMap<String, Object> sharedData;
    private boolean initialized = false;
    private final String routingRulesSchema;

    private boolean logRoutingRuleChanges = false;

    private String configResourceUri;
    private ConfigurationResourceManager configurationResourceManager;
    private Integer requestHopsLimit = null;
    private final Set<DefaultRouteType> defaultRouteTypes;

    public Router(Vertx vertx,
                  final ResourceStorage storage,
                  final Map<String, Object> properties,
                  LoggingResourceManager loggingResourceManager,
                  MonitoringHandler monitoringHandler,
                  HttpClient selfClient,
                  String serverPath,
                  String rulesPath,
                  String userProfilePath,
                  JsonObject info,
                  int storagePort,
                  Handler<Void>... doneHandlers) {
        this(vertx,
                storage,
                properties,
                loggingResourceManager,
                monitoringHandler,
                selfClient,
                serverPath,
                rulesPath,
                userProfilePath,
                info,
                storagePort,
                all(),
                doneHandlers);
    }

    public Router(Vertx vertx,
                  final ResourceStorage storage,
                  final Map<String, Object> properties,
                  LoggingResourceManager loggingResourceManager,
                  MonitoringHandler monitoringHandler,
                  HttpClient selfClient,
                  String serverPath,
                  String rulesPath,
                  String userProfilePath,
                  JsonObject info,
                  int storagePort,
                  Set<DefaultRouteType> defaultRouteTypes,
                  Handler<Void>... doneHandlers) {
        this.storage = storage;
        this.properties = properties;
        this.loggingResourceManager = loggingResourceManager;
        this.monitoringHandler = monitoringHandler;
        this.selfClient = selfClient;
        this.vertx = vertx;
        this.sharedData = vertx.sharedData().getLocalMap(ROUTER_STATE_MAP);
        this.rulesUri = rulesPath;
        this.userProfileUri = userProfilePath;
        this.serverUri = serverPath;
        this.info = info;
        this.defaultRouteTypes = defaultRouteTypes;
        this.doneHandlers = doneHandlers;

        routingRulesSchema = ResourcesUtils.loadResource("gateleen_routing_schema_routing_rules", true);

        final JsonObject initialRules = new JsonObject()
                .put("/(.*)", new JsonObject()
                        .put("name", "resource_storage")
                        .put("url", "http://localhost:" + storagePort + "/$1"));

        storage.get(rulesPath, buffer -> {
            try {
                if (buffer != null) {
                    try {
                        log.info("Applying rules");
                        updateRouting(buffer);
                    } catch (ValidationException e) {
                        log.error("Could not reconfigure routing", e);
                        updateRouting(initialRules);
                        setRoutingBrokenMessage(e);
                    }
                } else {
                    log.warn("No rules in storage, using initial routing");
                    updateRouting(initialRules);
                }
            } catch (ValidationException e) {
                log.error("Could not reconfigure routing", e);
                setRoutingBrokenMessage(e);
            }
        });

        // Receive update notifications
        vertx.eventBus().consumer(Address.RULE_UPDATE_ADDRESS, (Handler<Message<Boolean>>) event -> storage.get(rulesUri, buffer -> {
            if (buffer != null) {
                try {
                    log.info("Applying rules");
                    updateRouting(buffer);
                } catch (ValidationException e) {
                    log.error("Could not reconfigure routing", e);
                }
            } else {
                log.warn("Could not get URL '{}' (getting rules).", (rulesUri == null ? "<null>" : rulesUri));
            }
        }));
    }

    public enum DefaultRouteType {
        SIMULATOR, INFO, DEBUG;

        public static Set<DefaultRouteType> all() {
            return new HashSet<>(Arrays.asList(values()));
        }
    }

    public void route(final HttpServerRequest request) {
        // Intercept rule configuration
        if (request.uri().equals(rulesUri) && HttpMethod.PUT == request.method()) {
            request.bodyHandler(buffer -> {
                try {
                    new RuleFactory(properties, routingRulesSchema).parseRules(buffer);
                } catch (ValidationException validationException) {
                    log.error("Could not parse rules: {}", validationException.toString());
                    ResponseStatusCodeLogUtil.info(request, StatusCode.BAD_REQUEST, Router.class);
                    request.response().setStatusCode(StatusCode.BAD_REQUEST.getStatusCode());
                    request.response().setStatusMessage(StatusCode.BAD_REQUEST.getStatusMessage() + " " + validationException.getMessage());
                    if (validationException.getValidationDetails() != null) {
                        request.response().headers().add("content-type", "application/json");
                        request.response().end(validationException.getValidationDetails().encode());
                    } else {
                        request.response().end(validationException.getMessage());
                    }
                    return;
                }
                storage.put(rulesUri, buffer, status -> {
                    if (status == StatusCode.OK.getStatusCode()) {
                        if (logRoutingRuleChanges) {
                            RequestLogger.logRequest(vertx.eventBus(), request, StatusCode.OK.getStatusCode(), buffer);
                        }
                        vertx.eventBus().publish(Address.RULE_UPDATE_ADDRESS, true);
                        resetRouterBrokenState();
                    } else {
                        request.response().setStatusCode(status);
                    }
                    ResponseStatusCodeLogUtil.info(request, StatusCode.fromCode(status), Router.class);
                    request.response().end();
                });
            });
        } else {
            String routingBrokenMessage = getRoutingBrokenMessage();
            boolean isRoutingBroken = routingBrokenMessage != null;
            if (isRoutingBroken) {
                if (request.uri().equals(rulesUri) && HttpMethod.GET == request.method()) {
                    storage.get(rulesUri, buffer -> {
                        ResponseStatusCodeLogUtil.info(request, StatusCode.OK, Router.class);
                        request.response().setStatusCode(StatusCode.OK.getStatusCode());
                        request.response().setStatusMessage(StatusCode.OK.getStatusMessage());
                        request.response().end(buffer);
                    });
                } else {
                    ResponseStatusCodeLogUtil.info(request, StatusCode.INTERNAL_SERVER_ERROR, Router.class);
                    request.response().setStatusCode(StatusCode.INTERNAL_SERVER_ERROR.getStatusCode());
                    request.response().setStatusMessage(StatusCode.INTERNAL_SERVER_ERROR.getStatusMessage());
                    request.response().end(ErrorPageCreator.createRoutingBrokenHTMLErrorPage(routingBrokenMessage, rulesUri, rulesUri));
                }
            } else {
                if (router == null) {
                    ResponseStatusCodeLogUtil.info(request, StatusCode.SERVICE_UNAVAILABLE, Router.class);
                    request.response().setStatusCode(StatusCode.SERVICE_UNAVAILABLE.getStatusCode());
                    request.response().setStatusMessage("Server not yet ready");
                    request.response().end(request.response().getStatusMessage());
                    return;
                }
                if (requestHopsLimit == null) {
                    router.handle(request);
                    return;
                }
                increaseRequestHops(request);
                if (!isRequestHopsLimitExceeded(request, requestHopsLimit)) {
                    router.handle(request);
                } else {
                    String errorMessage = "Request hops limit of '" + requestHopsLimit + "' has been exceeded. " +
                            "Check the routing rules for looping configurations";
                    RequestLoggerFactory.getLogger(Router.class, request).error(errorMessage);
                    ResponseStatusCodeLogUtil.info(request, StatusCode.INTERNAL_SERVER_ERROR, Router.class);
                    request.response().setStatusCode(StatusCode.INTERNAL_SERVER_ERROR.getStatusCode());
                    request.response().setStatusMessage("Request hops limit exceeded");
                    request.response().putHeader(HttpHeaders.CONTENT_LENGTH, String.valueOf(errorMessage.length()));
                    request.response().end(errorMessage);
                }
            }
        }
    }

    public boolean isRoutingBroken() {
        return getRoutingBrokenMessage() != null;
    }

    public String getRoutingBrokenMessage() {
        return (String) getRouterStateMap().get(ROUTER_BROKEN_KEY);
    }

    private void setRoutingBrokenMessage(ValidationException exception) {
        StringBuilder msgBuilder = new StringBuilder(exception.getMessage());
        if (exception.getValidationDetails() != null) {
            msgBuilder.append(": ").append(exception.getValidationDetails().toString());
        }

        String message = msgBuilder.toString();
        if (StringUtils.isEmpty(message)) {
            message = "No Message provided!";
        }
        getRouterStateMap().put(ROUTER_BROKEN_KEY, message);
        log.error("routing is broken. message: {}", message);
    }

    private void resetRouterBrokenState() {
        if (getRouterStateMap().containsKey(ROUTER_BROKEN_KEY)) {
            log.info("reset router broken state. Routing is not broken anymore");
        }
        getRouterStateMap().remove(ROUTER_BROKEN_KEY);
    }

    private LocalMap<String, Object> getRouterStateMap() {
        return sharedData;
    }

    private void createForwarders(List<Rule> rules, io.vertx.ext.web.Router newRouter, Set<HttpClient> newClients) {
        for (Rule rule : rules) {
            /*
             * in case of a null - routing
             * the host field of the rule
             * is null.
             */
            Handler<RoutingContext> forwarder;
            if (rule.getPath() == null) {
                forwarder = new NullForwarder(rule, loggingResourceManager, monitoringHandler, vertx.eventBus());
            } else if (rule.getStorage() != null) {
                forwarder = new StorageForwarder(vertx.eventBus(), rule, loggingResourceManager, monitoringHandler);
            } else if (rule.getScheme().equals("local")) {
                forwarder = new Forwarder(vertx, selfClient, rule, this.storage, loggingResourceManager, monitoringHandler, userProfileUri);
            } else {
                HttpClient client = vertx.createHttpClient(rule.buildHttpClientOptions());
                client = new DeferCloseHttpClient(vertx, client); // <- Decorate for better close-handling
                forwarder = new Forwarder(vertx, client, rule, this.storage, loggingResourceManager, monitoringHandler, userProfileUri);
                newClients.add(client);
            }

            if (rule.getMethods() == null) {
                log.info("Installing {} forwarder for all methods: {}", rule.getScheme().toUpperCase(), rule.getUrlPattern());
                newRouter.routeWithRegex(rule.getUrlPattern()).handler(forwarder);
            } else {
                installMethodForwarder(newRouter, rule, forwarder);
            }
        }
    }

    private void installMethodForwarder(io.vertx.ext.web.Router newRouter, Rule rule, Handler<RoutingContext> forwarder) {
        for (String method : rule.getMethods()) {
            log.info("Installing {} forwarder for methods {} to {}", rule.getScheme().toUpperCase(), method, rule.getUrlPattern());
            switch (method) {
                case "GET":
                    newRouter.getWithRegex(rule.getUrlPattern()).handler(forwarder);
                    break;
                case "PUT":
                    newRouter.putWithRegex(rule.getUrlPattern()).handler(forwarder);
                    break;
                case "POST":
                    newRouter.postWithRegex(rule.getUrlPattern()).handler(forwarder);
                    break;
                case "DELETE":
                    newRouter.deleteWithRegex(rule.getUrlPattern()).handler(forwarder);
                    break;
            }
        }
    }

    private void cleanup() {
        final HashSet<HttpClient> clientsToClose = new HashSet<>(httpClients);
        log.debug("setTimeout({}ms) to close {} clients later", GRACE_PERIOD, clientsToClose.size());
        vertx.setTimer(GRACE_PERIOD, event -> {
            cleanupLogger.debug("GRACE_PERIOD of {} expired. Cleaning up {} clients", GRACE_PERIOD, clientsToClose.size());
            for (HttpClient client : clientsToClose) {
                client.close();
            }
        });
    }

    private void updateRouting(JsonObject rules) throws ValidationException {
        updateRouting(new RuleFactory(properties, routingRulesSchema).createRules(rules));
    }

    private void updateRouting(Buffer buffer) throws ValidationException {
        List<Rule> rules = new RuleFactory(properties, routingRulesSchema).parseRules(buffer);
        updateRouting(rules);
    }

    private void updateRouting(List<Rule> rules) {

        io.vertx.ext.web.Router newRouter = io.vertx.ext.web.Router.router(vertx);

        if (defaultRouteTypes.contains(SIMULATOR)) {
            newRouter.put(serverUri + "/simulator/.*").handler(ctx -> ctx.request().bodyHandler(buffer -> {
                try {
                    final JsonObject obj = new JsonObject(buffer.toString());
                    log.debug("Simulator got {} {}", obj.getLong("delay"), obj.getLong("size"));
                    vertx.setTimer(obj.getLong("delay"), event -> {
                        try {
                            char[] body = new char[obj.getInteger("size")];
                            ctx.response().end(new String(body));
                            log.debug("Simulator sent response");
                        } catch (Exception e) {
                            log.error("Simulator error {}", e.getMessage());
                            ctx.response().end();
                        }
                    });
                } catch (Exception e) {
                    log.error("Simulator error {}", e.getMessage());
                    ctx.response().end();
                }
            }));
        }

        if (defaultRouteTypes.contains(INFO)) {
            newRouter.get(serverUri + "/info").handler(ctx -> {
                if (HttpMethod.GET == ctx.request().method()) {
                    ctx.response().headers().set("Content-Type", "application/json");
                    ctx.response().end(info.toString());
                } else {
                    ResponseStatusCodeLogUtil.info(ctx.request(), StatusCode.METHOD_NOT_ALLOWED, Router.class);
                    ctx.response().setStatusCode(StatusCode.METHOD_NOT_ALLOWED.getStatusCode());
                    ctx.response().setStatusMessage(StatusCode.METHOD_NOT_ALLOWED.getStatusMessage());
                    ctx.response().end();
                }
            });
        }

        if (defaultRouteTypes.contains(DEBUG)) {
            newRouter.getWithRegex("/[^/]+/debug").handler(ctx -> {
                ctx.response().headers().set("Content-Type", "text/plain");
                StringBuilder body = new StringBuilder();
                body.append("* Headers *\n\n");
                SortedSet<String> keys = new TreeSet<>(ctx.request().headers().names());
                for (String key : keys) {
                    String value = ctx.request().headers().get(key);
                    if ("cookie".equals(key)) {
                        body.append("cookie:\n");
                        for (HttpCookie cookie : HttpCookie.parse(value)) {
                            body.append("    ");
                            body.append(cookie.toString());
                        }
                    }
                    body.append(key).append(": ").append(value).append("\n");
                }

                body.append("\n");
                body.append("* System Properties *\n\n");

                Set<Object> sorted = new TreeSet<>(System.getProperties().keySet());

                for (Object key : sorted) {
                    body.append(key).append(": ").append(System.getProperty((String) key)).append("\n");
                }

                ctx.response().end(body.toString());
            });
        }

        Set<HttpClient> newClients = new HashSet<>();

        createForwarders(rules, newRouter, newClients);

        router = newRouter;
        cleanup();
        httpClients.clear();
        httpClients.addAll(newClients);

        // the first time the update is performed, the
        // router is initialized and the doneHandlers
        // are called.
        if (!initialized) {
            initialized = true;
            for (Handler<Void> doneHandler : doneHandlers) {
                doneHandler.handle(null);
            }
        }
    }

    @Override
    public void refresh() {
        vertx.eventBus().publish(Address.RULE_UPDATE_ADDRESS, true);
        resetRouterBrokenState();
    }

    @Override
    public void enableResourceLogging(boolean resourceLoggingEnabled) {
        this.logRoutingRuleChanges = resourceLoggingEnabled;
    }

    @Override
    public void resourceChanged(String resourceUri, Buffer resource) {
        if (configResourceUri != null && configResourceUri.equals(resourceUri)) {
            log.info("Got notified about configuration resource update for {}", resourceUri);
            try {
                JsonObject obj = new JsonObject(resource);
                Integer requestHopsLimitValue = obj.getInteger(REQUEST_HOPS_LIMIT_PROPERTY);
                if (requestHopsLimitValue != null) {
                    log.info("Got value '{}' for property '{}'. Request hop validation is now activated",
                            requestHopsLimitValue, REQUEST_HOPS_LIMIT_PROPERTY);
                    requestHopsLimit = requestHopsLimitValue;
                } else {
                    log.warn("No value for property '{}' found. Request hop validation will not be activated", REQUEST_HOPS_LIMIT_PROPERTY);
                    requestHopsLimit = null;
                }
            } catch (DecodeException ex) {
                log.warn("Unable to decode configuration resource for {}. Reason: {}", resourceUri, ex.getMessage());
                requestHopsLimit = null;
            }
        }
    }

    @Override
    public void resourceRemoved(String resourceUri) {
        if (configResourceUri != null && configResourceUri.equals(resourceUri)) {
            log.info("Configuration resource {} was removed. Request hop validation is disabled", resourceUri);
            requestHopsLimit = null;
        }
    }

    /**
     * Enables the configuration for the routing.
     *
     * @param configurationResourceManager the configurationResourceManager
     * @param configResourceUri            the uri of the configuration resource
     */
    public void enableRoutingConfiguration(ConfigurationResourceManager configurationResourceManager, String configResourceUri) {
        this.configurationResourceManager = configurationResourceManager;
        this.configResourceUri = configResourceUri;
        initializeConfigurationResourceManagement();
    }

    private void initializeConfigurationResourceManagement() {
        if (configurationResourceManager != null && StringUtils.isNotEmptyTrimmed(configResourceUri)) {
            log.info("Register resource and observer for config resource uri {}", configResourceUri);
            String schema = ResourcesUtils.loadResource("gateleen_routing_schema_config", true);
            configurationResourceManager.registerResource(configResourceUri, schema);
            configurationResourceManager.registerObserver(this, configResourceUri);
        } else {
            log.info("No configuration resource manager and/or no configuration resource uri defined. Not using this feature in this case");
        }
    }
}

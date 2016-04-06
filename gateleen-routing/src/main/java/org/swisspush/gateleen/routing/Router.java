package org.swisspush.gateleen.routing;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import io.vertx.core.shareddata.LocalMap;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.monitoring.MonitoringHandler;
import org.swisspush.gateleen.core.storage.ResourceStorage;
import org.swisspush.gateleen.core.util.Address;
import org.swisspush.gateleen.core.util.ResourcesUtils;
import org.swisspush.gateleen.core.util.StatusCode;
import org.swisspush.gateleen.core.util.StringUtils;
import org.swisspush.gateleen.logging.LoggingResourceManager;
import org.swisspush.gateleen.validation.ValidationException;

import java.net.HttpCookie;
import java.util.*;

/**
 * @author https://github.com/lbovet [Laurent Bovet]
 */
public class Router {

    /**
     * How long to let the http clients live before closing them after a re-configuration
     */
    private static final int GRACE_PERIOD = 30000;
    public static final String ROUTER_STATE_MAP = "router_state_map";
    public static final String ROUTER_BROKEN_KEY = "router_broken";
    private String rulesUri;
    private String userProfileUri;
    private String serverUri;
    private io.vertx.ext.web.Router router;
    private LoggingResourceManager loggingResourceManager;
    private MonitoringHandler monitoringHandler;
    private Logger log = LoggerFactory.getLogger(Router.class);
    private Vertx vertx;
    private Set<HttpClient> httpClients = new HashSet<>();
    private HttpClient selfClient;
    private ResourceStorage storage;
    private JsonObject info;
    private final Map<String, Object> properties;
    private Handler<Void> doneHandlers[];
    private LocalMap<String, Object> sharedData;

    private String routingRulesSchema;

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
            Handler<Void>... doneHandlers) {
        this(vertx,
                vertx.sharedData().<String, Object> getLocalMap(ROUTER_STATE_MAP),
                storage,
                properties,
                loggingResourceManager,
                monitoringHandler,
                selfClient,
                serverPath,
                rulesPath,
                userProfilePath,
                info,
                doneHandlers);
    }

    public Router(Vertx vertx,
            LocalMap<String, Object> sharedData,
            final ResourceStorage storage,
            final Map<String, Object> properties,
            LoggingResourceManager loggingResourceManager,
            MonitoringHandler monitoringHandler,
            HttpClient selfClient,
            String serverPath,
            String rulesPath,
            String userProfilePath,
            JsonObject info,
            Handler<Void>... doneHandlers) {
        this.storage = storage;
        this.properties = properties;
        this.loggingResourceManager = loggingResourceManager;
        this.monitoringHandler = monitoringHandler;
        this.selfClient = selfClient;
        this.vertx = vertx;
        this.sharedData = sharedData;
        this.rulesUri = rulesPath;
        this.userProfileUri = userProfilePath;
        this.serverUri = serverPath;
        this.info = info;
        this.doneHandlers = doneHandlers;

        routingRulesSchema = ResourcesUtils.loadResource("gateleen_routing_schema_routing_rules", true);

        final JsonObject initialRules = new JsonObject().put("/(.*)", new JsonObject().put("url", "http://localhost:8989/$1"));

        storage.get(rulesPath, buffer -> {
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
                log.warn("Could not get URL '" + (rulesUri == null ? "<null>" : rulesUri) + "' (getting rules).");
            }
        }));
    }

    public void route(final HttpServerRequest request) {
        // Intercept rule configuration
        if (request.uri().equals(rulesUri) && HttpMethod.PUT == request.method()) {
            request.bodyHandler(buffer -> {
                try {
                    new RuleFactory(properties, routingRulesSchema).parseRules(buffer);
                } catch (ValidationException validationException) {
                    log.error("Could not parse rules: " + validationException.toString());
                    request.response().setStatusCode(StatusCode.BAD_REQUEST.getStatusCode());
                    request.response().setStatusMessage(StatusCode.BAD_REQUEST.getStatusMessage() + " " + validationException.getMessage());
                    if(validationException.getValidationDetails() != null){
                        request.response().headers().add("content-type", "application/json");
                        request.response().end(validationException.getValidationDetails().encode());
                    } else {
                        request.response().end(validationException.getMessage());
                    }
                    return;
                }
                storage.put(rulesUri, buffer, status -> {
                    if (status == StatusCode.OK.getStatusCode()) {
                        vertx.eventBus().publish(Address.RULE_UPDATE_ADDRESS, true);
                        resetRouterBrokenState();
                    } else {
                        request.response().setStatusCode(status);
                    }
                    request.response().end();
                });
            });
        } else {
            String routingBrokenMessage = getRoutingBrokenMessage();
            boolean isRoutingBroken = routingBrokenMessage != null;
            if (isRoutingBroken) {
                if (request.uri().equals(rulesUri) && HttpMethod.GET == request.method()) {
                    storage.get(rulesUri, buffer -> {
                        request.response().setStatusCode(StatusCode.OK.getStatusCode());
                        request.response().setStatusMessage(StatusCode.OK.getStatusMessage());
                        request.response().end(buffer);
                    });
                } else {
                    request.response().setStatusCode(StatusCode.INTERNAL_SERVER_ERROR.getStatusCode());
                    request.response().setStatusMessage(StatusCode.INTERNAL_SERVER_ERROR.getStatusMessage());
                    request.response().end(ErrorPageCreator.createRoutingBrokenHTMLErrorPage(routingBrokenMessage, rulesUri, rulesUri));
                }
            } else {
                if (router!= null) {
                    router.accept(request);
                } else {
                    request.response().setStatusCode(StatusCode.SERVICE_UNAVAILABLE.getStatusCode());
                    request.response().setStatusMessage("Server not yet ready");
                    request.response().end(request.response().getStatusMessage());
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
        if(exception.getValidationDetails() != null){
            msgBuilder.append(": ").append(exception.getValidationDetails().toString());
        }

        String message = msgBuilder.toString();
        if (StringUtils.isEmpty(message)) {
            message = "No Message provided!";
        }
        getRouterStateMap().put(ROUTER_BROKEN_KEY, message);
        log.error("routing is broken. message: " + message);
    }

    private void resetRouterBrokenState() {
        if (getRouterStateMap().keySet().contains(ROUTER_BROKEN_KEY)) {
            log.info("reset router broken state. Routing is not broken anymore");
        }
        getRouterStateMap().remove(ROUTER_BROKEN_KEY);
    }

    private LocalMap<String, Object> getRouterStateMap() {
        return sharedData;
    }

    private HttpClientOptions buildHttpClientOptions(Rule rule){
        HttpClientOptions options = new HttpClientOptions()
                .setDefaultHost(rule.getHost())
                .setDefaultPort(rule.getPort())
                .setMaxPoolSize(rule.getPoolSize())
                .setConnectTimeout(rule.getTimeout())
                .setKeepAlive(rule.isKeepAlive())
                .setPipelining(false);

        if (rule.getScheme().equals("https")) {
            options.setSsl(true).setVerifyHost(false).setTrustAll(true);
        }
        return options;
    }

    private void createForwarders(List<Rule> rules, io.vertx.ext.web.Router newRouter, Set<HttpClient> newClients) {
        for (Rule rule : rules) {
            HttpClient client = vertx.createHttpClient(buildHttpClientOptions(rule));
            /*
             * in case of a null - routing
             * the host field of the rule
             * is null.
             */
            Handler<RoutingContext> forwarder;
            if (rule.getPath() == null) {
                forwarder = new NullForwarder(rule, loggingResourceManager);
            } else if (rule.getStorage() != null) {
                forwarder = new StorageForwarder(vertx.eventBus(), rule, loggingResourceManager, monitoringHandler);
            } else if (rule.getScheme().equals("local")) {
                forwarder = new Forwarder(vertx, selfClient, rule, this.storage, loggingResourceManager, monitoringHandler, userProfileUri);
            } else {
                forwarder = new Forwarder(vertx, client, rule, this.storage, loggingResourceManager, monitoringHandler, userProfileUri);
            }

            if (rule.getMethods() == null) {
                log.info("Installing " + rule.getScheme().toUpperCase() + " forwarder for all methods: " + rule.getUrlPattern());
                newRouter.routeWithRegex(rule.getUrlPattern()).handler(forwarder);
            } else {
                installMethodForwarder(newRouter, rule, forwarder);
            }
            newClients.add(client);
        }
    }

    private void installMethodForwarder(io.vertx.ext.web.Router newRouter, Rule rule, Handler<RoutingContext> forwarder) {
        for (String method : rule.getMethods()) {
            log.info("Installing " + rule.getScheme().toUpperCase() + " forwarder for methods " + method + " to " + rule.getUrlPattern());
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
        vertx.setTimer(GRACE_PERIOD, event -> {
            if (clientsToClose.size() > 0) {
                log.debug("Cleaning up {} clients", clientsToClose.size());
            }
            for (HttpClient client : clientsToClose) {
                client.close();
            }
        });
    }

    private void updateRouting(JsonObject rules) {
        updateRouting(new RuleFactory(properties, routingRulesSchema).createRules(rules));
    }

    private void updateRouting(Buffer buffer) throws ValidationException {
        List<Rule> rules = new RuleFactory(properties, routingRulesSchema).parseRules(buffer);
        updateRouting(rules);
    }

    private void updateRouting(List<Rule> rules) {

        io.vertx.ext.web.Router newRouter = io.vertx.ext.web.Router.router(vertx);

        newRouter.put(serverUri + "/simulator/.*").handler(ctx -> ctx.request().bodyHandler(buffer -> {
            try {
                final JsonObject obj = new JsonObject(buffer.toString());
                log.debug("Simulator got " + obj.getLong("delay") + " " + obj.getLong("size"));
                vertx.setTimer(obj.getLong("delay"), event -> {
                    try {
                        char[] body = new char[obj.getInteger("size")];
                        ctx.response().end(new String(body));
                        log.debug("Simulator sent response");
                    } catch (Exception e) {
                        log.error("Simulator error " + e.getMessage());
                        ctx.response().end();
                    }
                });
            } catch (Exception e) {
                log.error("Simulator error " + e.getMessage());
                ctx.response().end();
            }
        }));

        newRouter.get(serverUri + "/info").handler(ctx -> {
            if (HttpMethod.GET == ctx.request().method()) {
                ctx.response().headers().set("Content-Type", "application/json");
                ctx.response().end(info.toString());
            } else {
                ctx.response().setStatusCode(StatusCode.METHOD_NOT_ALLOWED.getStatusCode());
                ctx.response().setStatusMessage(StatusCode.METHOD_NOT_ALLOWED.getStatusMessage());
                ctx.response().end();
            }
        });

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

        Set<HttpClient> newClients = new HashSet<>();

        createForwarders(rules, newRouter, newClients);

        router = newRouter;
        cleanup();
        httpClients.clear();
        httpClients.addAll(newClients);

        for (Handler<Void> doneHandler : doneHandlers) {
            doneHandler.handle(null);
        }
    }
}

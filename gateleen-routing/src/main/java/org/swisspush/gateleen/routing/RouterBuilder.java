package org.swisspush.gateleen.routing;

import io.micrometer.core.instrument.MeterRegistry;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.configuration.ConfigurationResourceManager;
import org.swisspush.gateleen.core.exception.GateleenExceptionFactory;
import org.swisspush.gateleen.core.http.HttpClientFactory;
import org.swisspush.gateleen.core.storage.ResourceStorage;
import org.swisspush.gateleen.logging.LogAppenderRepository;
import org.swisspush.gateleen.logging.LoggingResourceManager;
import org.swisspush.gateleen.monitoring.MonitoringHandler;
import org.swisspush.gateleen.routing.auth.OAuthProvider;

import java.util.*;

import static org.swisspush.gateleen.routing.Router.DefaultRouteType.INFO;
import static org.swisspush.gateleen.routing.Router.DefaultRouteType.all;


/**
 * Convenience builder to instantiate {@link Router}s easier.
 */
public class RouterBuilder {

    private static final Handler<Void>[] EMPTY_HANDLER_VOID_ARR = new Handler[0];
    private static final Logger logger = LoggerFactory.getLogger(RouterBuilder.class);
    private boolean isBuilt;

    // Config for the Router we're going to build.
    private Vertx vertx;
    private JsonObject initialRules;
    private ResourceStorage storage;
    private Map<String, Object> properties;
    private LoggingResourceManager loggingResourceManager;
    private LogAppenderRepository logAppenderRepository;
    private MonitoringHandler monitoringHandler;
    private MeterRegistry meterRegistry;
    private HttpClient selfClient;
    private String serverPath;
    private String rulesPath;
    private String userProfilePath;
    private JsonObject info;
    private int storagePort = -1;
    private Set<Router.DefaultRouteType> defaultRouteTypes;
    private boolean resourceLoggingEnabled;
    private boolean isRoutingConfiguration;
    private ConfigurationResourceManager configurationResourceManager;
    private String configurationResourcePath;
    private ArrayList<Handler<Void>> doneHandlers;
    private HttpClientFactory httpClientFactory;
    private int routeMultiplier = Router.DEFAULT_ROUTER_MULTIPLIER;

    private OAuthProvider oAuthProvider;
    private GateleenExceptionFactory exceptionFactory;

    RouterBuilder() {
        // PackagePrivate, as clients should use "Router.builder()" and not this class here directly.
    }

    /**
     * Instantiates a {@link Router} and returns it based on this builders state.
     */
    public Router build() {
        throwIfInvalid();

        if (defaultRouteTypes == null) {
            defaultRouteTypes = all();
        }

        if (this.exceptionFactory == null) {
            this.exceptionFactory = GateleenExceptionFactory.newGateleenThriftyExceptionFactory();
        }

        Handler<Void>[] doneHandlersArray;
        if (doneHandlers == null || doneHandlers.isEmpty()) {
            logger.debug("No doneHandlers specified.");
            doneHandlersArray = EMPTY_HANDLER_VOID_ARR;
        } else {
            logger.debug("Use {} doneHandlers.", doneHandlers.size());
            doneHandlersArray = doneHandlers.toArray(EMPTY_HANDLER_VOID_ARR);
        }

        if (httpClientFactory == null) {
            // Implicitly use the factory from vertx. This way we stay backward compatible.
            logger.debug("No httpClientFactory specified. Use factory from vertx");
            httpClientFactory = HttpClientFactory.of(vertx);
        } else {
            logger.debug("Use custom httpClientFactory.");
        }

        ensureNotBuilt();
        isBuilt = true;
        Router router = new Router(vertx,
                initialRules,
                storage,
                properties,
                loggingResourceManager,
                logAppenderRepository,
                monitoringHandler,
                meterRegistry,
                selfClient,
                serverPath,
                rulesPath,
                userProfilePath,
                info,
                storagePort,
                defaultRouteTypes,
                httpClientFactory,
                routeMultiplier,
                oAuthProvider,
                exceptionFactory,
                doneHandlersArray
        );
        if (resourceLoggingEnabled) {
            router.enableResourceLogging(true);
        }
        if (isRoutingConfiguration) {
            router.enableRoutingConfiguration(configurationResourceManager, configurationResourcePath);
        }
        return router;
    }

    private void ensureNotBuilt() {
        if (isBuilt) throw new IllegalStateException("Instance already created.");
    }

    private void throwIfInvalid() {
        Objects.requireNonNull(vertx, "vertx");
        Objects.requireNonNull(storage, "storage");
        if (storagePort < 0 || storagePort > 0xFFFF) {
            throw new IllegalArgumentException("storagePort " + storagePort);
        }
        Objects.requireNonNull(selfClient, "selfClient");
        Objects.requireNonNull(serverPath, "serverPath");
        if (defaultRouteTypes == null || defaultRouteTypes.contains(INFO)) {
            Objects.requireNonNull(info, "info");
        }
    }


    public RouterBuilder withVertx(Vertx vertx) {
        ensureNotBuilt();
        this.vertx = vertx;
        return this;
    }

    public RouterBuilder withInitialRules(JsonObject initialRules) {
        ensureNotBuilt();
        this.initialRules = initialRules;
        return this;
    }

    public RouterBuilder withStorage(ResourceStorage storage) {
        ensureNotBuilt();
        this.storage = storage;
        return this;
    }

    public RouterBuilder withProperties(Map<String, Object> properties) {
        ensureNotBuilt();
        this.properties = properties;
        return this;
    }

    public RouterBuilder setProperty(String key, Object value) {
        ensureNotBuilt();
        if (this.properties == null) {
            this.properties = new LinkedHashMap<>();
        }
        this.properties.put(key, value);
        return this;
    }

    public RouterBuilder withLoggingResourceManager(LoggingResourceManager loggingResourceManager) {
        ensureNotBuilt();
        this.loggingResourceManager = loggingResourceManager;
        return this;
    }

    public RouterBuilder withLogAppenderRepository(LogAppenderRepository logAppenderRepository) {
        ensureNotBuilt();
        this.logAppenderRepository = logAppenderRepository;
        return this;
    }

    public RouterBuilder withMonitoringHandler(MonitoringHandler monitoringHandler) {
        ensureNotBuilt();
        this.monitoringHandler = monitoringHandler;
        return this;
    }

    public RouterBuilder withMeterRegistry(MeterRegistry meterRegistry) {
        ensureNotBuilt();
        this.meterRegistry = meterRegistry;
        return this;
    }

    public RouterBuilder withSelfClient(HttpClient selfClient) {
        ensureNotBuilt();
        this.selfClient = selfClient;
        return this;
    }

    public RouterBuilder withServerPath(String serverPath) {
        ensureNotBuilt();
        this.serverPath = serverPath;
        return this;
    }

    public RouterBuilder withRulesPath(String rulesPath) {
        ensureNotBuilt();
        this.rulesPath = rulesPath;
        return this;
    }

    public RouterBuilder withUserProfilePath(String userProfilePath) {
        ensureNotBuilt();
        this.userProfilePath = userProfilePath;
        return this;
    }

    public RouterBuilder withInfo(JsonObject info) {
        ensureNotBuilt();
        this.info = info;
        return this;
    }

    public RouterBuilder withStoragePort(int storagePort) {
        ensureNotBuilt();
        this.storagePort = storagePort;
        return this;
    }

    public RouterBuilder withDefaultRouteTypes(Set<Router.DefaultRouteType> defaultRouteTypes) {
        ensureNotBuilt();
        this.defaultRouteTypes = defaultRouteTypes;
        return this;
    }

    public RouterBuilder addDefaultRouteType(Router.DefaultRouteType defaultRouteType) {
        ensureNotBuilt();
        if (defaultRouteTypes == null) {
            defaultRouteTypes = new LinkedHashSet<>();
        }
        defaultRouteTypes.add(defaultRouteType);
        return this;
    }

    public RouterBuilder withResourceLogging(boolean enabled) {
        ensureNotBuilt();
        this.resourceLoggingEnabled = enabled;
        return this;
    }

    public RouterBuilder withRoutingConfiguration(ConfigurationResourceManager configurationResourceManager, String configurationResourcePath) {
        ensureNotBuilt();
        this.isRoutingConfiguration = true;
        this.configurationResourceManager = configurationResourceManager;
        this.configurationResourcePath = configurationResourcePath;
        return this;
    }

    public RouterBuilder withHttpClientFactory(HttpClientFactory httpClientFactory) {
        ensureNotBuilt();
        this.httpClientFactory = httpClientFactory;
        return this;
    }

    public RouterBuilder withOAuthProvider(OAuthProvider oAuthProvider) {
        ensureNotBuilt();
        this.oAuthProvider = oAuthProvider;
        return this;
    }

    public RouterBuilder withDoneHandlers(List<Handler<Void>> doneHandlers) {
        ensureNotBuilt();
        this.doneHandlers = new ArrayList<>(doneHandlers);
        return this;
    }

    public RouterBuilder addDoneHandler(Handler<Void> doneHandler) {
        ensureNotBuilt();
        if (this.doneHandlers == null) {
            this.doneHandlers = new ArrayList<>();
        }
        this.doneHandlers.add(doneHandler);
        return this;
    }

    public RouterBuilder withRouteMultiplier(int routeMultiplier) {
        ensureNotBuilt();
        this.routeMultiplier = routeMultiplier;
        return this;
    }

    public RouterBuilder withExceptionFactory(GateleenExceptionFactory exceptionFactory) {
        this.exceptionFactory = exceptionFactory;
        return this;
    }
}

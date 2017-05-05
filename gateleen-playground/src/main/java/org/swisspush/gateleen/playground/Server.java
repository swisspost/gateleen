package org.swisspush.gateleen.playground;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.redis.RedisClient;
import io.vertx.redis.RedisOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePropertySource;
import org.swisspush.gateleen.core.configuration.ConfigurationResourceManager;
import org.swisspush.gateleen.delegate.DelegateHandler;
import org.swisspush.gateleen.core.cors.CORSHandler;
import org.swisspush.gateleen.core.event.EventBusHandler;
import org.swisspush.gateleen.core.http.LocalHttpClient;
import org.swisspush.gateleen.core.resource.CopyResourceHandler;
import org.swisspush.gateleen.core.storage.EventBusResourceStorage;
import org.swisspush.gateleen.core.storage.ResourceStorage;
import org.swisspush.gateleen.core.util.Address;
import org.swisspush.gateleen.delta.DeltaHandler;
import org.swisspush.gateleen.expansion.ExpansionHandler;
import org.swisspush.gateleen.expansion.ZipExtractHandler;
import org.swisspush.gateleen.hook.HookHandler;
import org.swisspush.gateleen.hook.reducedpropagation.ReducedPropagationManager;
import org.swisspush.gateleen.hook.reducedpropagation.impl.RedisReducedPropagationStorage;
import org.swisspush.gateleen.logging.LogController;
import org.swisspush.gateleen.logging.LoggingResourceManager;
import org.swisspush.gateleen.logging.RequestLoggingConsumer;
import org.swisspush.gateleen.monitoring.CustomRedisMonitor;
import org.swisspush.gateleen.monitoring.MonitoringHandler;
import org.swisspush.gateleen.monitoring.ResetMetricsController;
import org.swisspush.gateleen.qos.QoSHandler;
import org.swisspush.gateleen.queue.queuing.QueueBrowser;
import org.swisspush.gateleen.queue.queuing.QueueClient;
import org.swisspush.gateleen.queue.queuing.QueueProcessor;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.*;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.api.QueueCircuitBreakerHttpRequestHandler;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.configuration.QueueCircuitBreakerConfigurationResourceManager;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.impl.QueueCircuitBreakerImpl;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.impl.RedisQueueCircuitBreakerStorage;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.util.QueueCircuitBreakerRulePatternToCircuitMapping;
import org.swisspush.gateleen.routing.Router;
import org.swisspush.gateleen.routing.RuleProvider;
import org.swisspush.gateleen.runconfig.RunConfig;
import org.swisspush.gateleen.scheduler.SchedulerResourceManager;
import org.swisspush.gateleen.security.authorization.Authorizer;
import org.swisspush.gateleen.user.RoleProfileHandler;
import org.swisspush.gateleen.user.UserProfileHandler;
import org.swisspush.gateleen.validation.ValidationHandler;
import org.swisspush.gateleen.validation.ValidationResourceManager;

import java.io.IOException;
import java.util.Map;

/**
 * Playground server to try Gateleen at home.
 *
 * @author https://github.com/lbovet [Laurent Bovet]
 */
public class Server extends AbstractVerticle {

    public static final String PREFIX = RunConfig.SERVER_NAME + ".";
    public static final String ROOT = "/playground";
    public static final String SERVER_ROOT = ROOT + "/server";
    public static final String RULES_ROOT = SERVER_ROOT + "/admin/v1/routing/rules";

    public static final String ROLE_PATTERN = "^z-playground[-_](.*)$";

    public static final String JMX_DOMAIN = "org.swisspush.gateleen";
    private HttpServer mainServer;

    private Authorizer authorizer;
    private Router router;
    private LoggingResourceManager loggingResourceManager;
    private RequestLoggingConsumer requestLoggingConsumer;
    private ConfigurationResourceManager configurationResourceManager;
    private ValidationResourceManager validationResourceManager;
    private SchedulerResourceManager schedulerResourceManager;
    private QueueCircuitBreakerConfigurationResourceManager queueCircuitBreakerConfigurationResourceManager;
    private MonitoringHandler monitoringHandler;

    private EventBusHandler eventBusHandler;

    private int defaultRedisPort = 6379;
    private int mainPort = 7012;
    private int circuitBreakerPort = 7013;

    private RedisClient redisClient;
    private ResourceStorage storage;
    private UserProfileHandler userProfileHandler;
    private RoleProfileHandler roleProfileHandler;
    private CORSHandler corsHandler;
    private ExpansionHandler expansionHandler;
    private DeltaHandler deltaHandler;
    private CopyResourceHandler copyResourceHandler;
    private ValidationHandler validationHandler;
    private QoSHandler qosHandler;
    private HookHandler hookHandler;
    private ReducedPropagationManager reducedPropagationManager;
    private ZipExtractHandler zipExtractHandler;
    private DelegateHandler delegateHandler;

    private Logger log = LoggerFactory.getLogger(Server.class);

    public static void main(String[] args) {
        Vertx.vertx().deployVerticle("org.swisspush.gateleen.playground.Server", event -> {
            LoggerFactory.getLogger(Server.class).info("[_] Gateleen - http://localhost:7012/gateleen/");
        });
    }

    @Override
    public void start() {
        final LocalHttpClient selfClient = new LocalHttpClient(vertx);
        final HttpClient selfClientExpansionHandler = selfClient;
        final JsonObject info = new JsonObject();
        final Map<String, Object> props = RunConfig.buildRedisProps("localhost", defaultRedisPort);

        /*
         * Just for demonstration purposes. In real-life use a request header name to group the requests
         * and set it as vm option like -Dorg.swisspush.request.rule.property=MY_HEADER_NAME
         */
        System.setProperty(MonitoringHandler.REQUEST_PER_RULE_PROPERTY, "x-appid");

        try {
            String externalConfig = System.getProperty("org.swisspush.config.dir") + "/config.properties";
            Resource externalConfigResource = new FileSystemResource(externalConfig);
            if (externalConfigResource.exists()) {
                log.info("Merging external config " + externalConfig);
                props.putAll(RunConfig.subMap(new ResourcePropertySource(externalConfigResource).getSource(), "redis."));
            } else {
                log.info("No external config found under " + externalConfig);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        String redisHost = (String) props.get("redis.host");
        Integer redisPort = (Integer) props.get("redis.port");

        props.put(ExpansionHandler.MAX_EXPANSION_LEVEL_HARD_PROPERTY, "100");
        props.put(ExpansionHandler.MAX_EXPANSION_LEVEL_SOFT_PROPERTY, "50");

        RunConfig.deployModules(vertx, Server.class, props, success -> {
            if (success) {
                redisClient = RedisClient.create(vertx, new RedisOptions().setHost(redisHost).setPort(redisPort));
                new CustomRedisMonitor(vertx, redisClient, "main", "rest-storage", 10).start();
                storage = new EventBusResourceStorage(vertx.eventBus(), Address.storageAddress() + "-main");
                corsHandler = new CORSHandler();
                deltaHandler = new DeltaHandler(redisClient, selfClient);
                expansionHandler = new ExpansionHandler(vertx, storage, selfClientExpansionHandler, props, ROOT, RULES_ROOT);
                copyResourceHandler = new CopyResourceHandler(selfClient, SERVER_ROOT + "/v1/copy");
                monitoringHandler = new MonitoringHandler(vertx, storage, PREFIX, SERVER_ROOT + "/monitoring/rpr");
                qosHandler = new QoSHandler(vertx, storage, SERVER_ROOT + "/admin/v1/qos", props, PREFIX);
                qosHandler.enableResourceLogging(true);
                configurationResourceManager = new ConfigurationResourceManager(vertx, storage);
                configurationResourceManager.enableResourceLogging(true);
                String eventBusConfigurationResource = SERVER_ROOT + "/admin/v1/hookconfig";
                eventBusHandler = new EventBusHandler(vertx, SERVER_ROOT + "/event/v1/", SERVER_ROOT + "/event/v1/sock/*", "event-", "channels/([^/]+).*", configurationResourceManager, eventBusConfigurationResource);
                eventBusHandler.setEventbusBridgePingInterval(RunConfig.EVENTBUS_BRIDGE_PING_INTERVAL);
                loggingResourceManager = new LoggingResourceManager(vertx, storage, SERVER_ROOT + "/admin/v1/logging");
                loggingResourceManager.enableResourceLogging(true);

                requestLoggingConsumer = new RequestLoggingConsumer(vertx, loggingResourceManager);

                userProfileHandler = new UserProfileHandler(vertx, storage, RunConfig.buildUserProfileConfiguration());
                userProfileHandler.enableResourceLogging(true);
                roleProfileHandler = new RoleProfileHandler(vertx, storage, SERVER_ROOT + "/roles/v1/([^/]+)/profile");
                roleProfileHandler.enableResourceLogging(true);

                QueueClient queueClient = new QueueClient(vertx, monitoringHandler);
                reducedPropagationManager = new ReducedPropagationManager(vertx, new RedisReducedPropagationStorage(redisClient), queueClient);
                reducedPropagationManager.startExpiredQueueProcessing(5000);
                hookHandler = new HookHandler(vertx, selfClient, storage, loggingResourceManager, monitoringHandler,
                        SERVER_ROOT + "/users/v1/%s/profile", ROOT + "/server/hooks/v1/", queueClient, false, reducedPropagationManager);
                hookHandler.enableResourceLogging(true);

                authorizer = new Authorizer(vertx, storage, SERVER_ROOT + "/security/v1/", ROLE_PATTERN);
                authorizer.enableResourceLogging(true);
                validationResourceManager = new ValidationResourceManager(vertx, storage, SERVER_ROOT + "/admin/v1/validation");
                validationResourceManager.enableResourceLogging(true);
                validationHandler = new ValidationHandler(validationResourceManager, storage, selfClient, ROOT + "/schemas/apis/");
                schedulerResourceManager = new SchedulerResourceManager(vertx, redisClient, storage, monitoringHandler, SERVER_ROOT + "/admin/v1/schedulers");
                schedulerResourceManager.enableResourceLogging(true);
                zipExtractHandler = new ZipExtractHandler(selfClient);
                delegateHandler = new DelegateHandler(vertx, selfClient, storage, monitoringHandler, SERVER_ROOT + "/delegate/v1/delegates/", props);
                delegateHandler.enableResourceLogging(true);
                router = new Router(vertx, storage, props, loggingResourceManager, monitoringHandler, selfClient, SERVER_ROOT, SERVER_ROOT + "/admin/v1/routing/rules", SERVER_ROOT + "/users/v1/%s/profile", info,
                        (Handler<Void>) aVoid -> {
                            hookHandler.init();
                            delegateHandler.init();
                        });
                router.enableResourceLogging(true);
                String routerConfigurationResource = SERVER_ROOT + "/admin/v1/routing/config";
                router.enableRoutingConfiguration(configurationResourceManager, routerConfigurationResource);

                RuleProvider ruleProvider = new RuleProvider(vertx, RULES_ROOT, storage, props);
                QueueCircuitBreakerRulePatternToCircuitMapping rulePatternToCircuitMapping = new QueueCircuitBreakerRulePatternToCircuitMapping();

                queueCircuitBreakerConfigurationResourceManager = new QueueCircuitBreakerConfigurationResourceManager(vertx, storage, SERVER_ROOT + "/admin/v1/circuitbreaker");
                queueCircuitBreakerConfigurationResourceManager.enableResourceLogging(true);
                QueueCircuitBreakerStorage queueCircuitBreakerStorage = new RedisQueueCircuitBreakerStorage(redisClient);
                QueueCircuitBreakerHttpRequestHandler requestHandler = new QueueCircuitBreakerHttpRequestHandler(vertx, queueCircuitBreakerStorage,
                        SERVER_ROOT + "/queuecircuitbreaker/circuit");

                QueueCircuitBreaker queueCircuitBreaker = new QueueCircuitBreakerImpl(vertx, queueCircuitBreakerStorage,
                        ruleProvider, rulePatternToCircuitMapping, queueCircuitBreakerConfigurationResourceManager, requestHandler, circuitBreakerPort);

                new QueueProcessor(vertx, selfClient, monitoringHandler, queueCircuitBreaker);
                final QueueBrowser queueBrowser = new QueueBrowser(vertx, SERVER_ROOT + "/queuing", Address.redisquesAddress(), monitoringHandler);

                LogController logController = new LogController();
                logController.registerLogConfiguratorMBean(JMX_DOMAIN);

                ResetMetricsController resetMetricsController = new ResetMetricsController(vertx);
                resetMetricsController.registerResetMetricsControlMBean(JMX_DOMAIN, PREFIX);

                RunConfig runConfig = RunConfig.with()
                        .authorizer(authorizer)
                        .validationResourceManager(validationResourceManager)
                        .validationHandler(validationHandler)
                        .corsHandler(corsHandler)
                        .deltaHandler(deltaHandler)
                        .expansionHandler(expansionHandler)
                        .hookHandler(hookHandler)
                        .qosHandler(qosHandler)
                        .copyResourceHandler(copyResourceHandler)
                        .eventBusHandler(eventBusHandler)
                        .roleProfileHandler(roleProfileHandler)
                        .userProfileHandler(userProfileHandler)
                        .loggingResourceManager(loggingResourceManager)
                        .configurationResourceManager(configurationResourceManager)
                        .queueCircuitBreakerConfigurationResourceManager(queueCircuitBreakerConfigurationResourceManager)
                        .schedulerResourceManager(schedulerResourceManager)
                        .zipExtractHandler(zipExtractHandler)
                        .delegateHandler(delegateHandler)
                        .build(vertx, redisClient, Server.class, router, monitoringHandler, queueBrowser);
                Handler<RoutingContext> routingContextHandlerrNew = runConfig.buildRoutingContextHandler();
                selfClient.setRoutingContexttHandler(routingContextHandlerrNew);

                HttpServerOptions options = new HttpServerOptions();
                // till vertx2 100-continues was performed automatically (per default),
                // since vertx3 it is off per default.
                options.setHandle100ContinueAutomatically(true);

                mainServer = vertx.createHttpServer(options);
                io.vertx.ext.web.Router vertxRouter = io.vertx.ext.web.Router.router(vertx);
                eventBusHandler.install(vertxRouter);
                vertxRouter.route().handler(routingContextHandlerrNew);
                mainServer.requestHandler(vertxRouter::accept);
                mainServer.listen(mainPort);
            }
        });
    }
}

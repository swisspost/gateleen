package org.swisspush.gateleen.playground;

import io.vertx.core.*;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.tracing.TracingPolicy;
import io.vertx.ext.web.RoutingContext;
import io.vertx.redis.client.PoolOptions;
import io.vertx.redis.client.RedisAPI;
import io.vertx.redis.client.RedisStandaloneConnectOptions;
import io.vertx.redis.client.impl.RedisClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePropertySource;
import org.swisspush.gateleen.cache.CacheHandler;
import org.swisspush.gateleen.cache.fetch.CacheDataFetcher;
import org.swisspush.gateleen.cache.fetch.DefaultCacheDataFetcher;
import org.swisspush.gateleen.cache.storage.CacheStorage;
import org.swisspush.gateleen.cache.storage.RedisCacheStorage;
import org.swisspush.gateleen.core.configuration.ConfigurationResourceManager;
import org.swisspush.gateleen.core.cors.CORSHandler;
import org.swisspush.gateleen.core.event.EventBusHandler;
import org.swisspush.gateleen.core.http.ClientRequestCreator;
import org.swisspush.gateleen.core.http.LocalHttpClient;
import org.swisspush.gateleen.core.lock.Lock;
import org.swisspush.gateleen.core.lock.impl.RedisBasedLock;
import org.swisspush.gateleen.core.redis.RedisProvider;
import org.swisspush.gateleen.core.resource.CopyResourceHandler;
import org.swisspush.gateleen.core.storage.EventBusResourceStorage;
import org.swisspush.gateleen.core.storage.ResourceStorage;
import org.swisspush.gateleen.core.util.Address;
import org.swisspush.gateleen.delegate.DelegateHandler;
import org.swisspush.gateleen.delta.DeltaHandler;
import org.swisspush.gateleen.expansion.ExpansionHandler;
import org.swisspush.gateleen.expansion.ZipExtractHandler;
import org.swisspush.gateleen.hook.HookHandler;
import org.swisspush.gateleen.hook.reducedpropagation.ReducedPropagationManager;
import org.swisspush.gateleen.hook.reducedpropagation.impl.RedisReducedPropagationStorage;
import org.swisspush.gateleen.kafka.KafkaHandler;
import org.swisspush.gateleen.kafka.KafkaMessageSender;
import org.swisspush.gateleen.kafka.KafkaMessageValidator;
import org.swisspush.gateleen.kafka.KafkaProducerRepository;
import org.swisspush.gateleen.logging.DefaultLogAppenderRepository;
import org.swisspush.gateleen.logging.LogAppenderRepository;
import org.swisspush.gateleen.logging.LogController;
import org.swisspush.gateleen.logging.LoggingResourceManager;
import org.swisspush.gateleen.monitoring.CustomRedisMonitor;
import org.swisspush.gateleen.monitoring.MonitoringHandler;
import org.swisspush.gateleen.monitoring.ResetMetricsController;
import org.swisspush.gateleen.qos.QoSHandler;
import org.swisspush.gateleen.queue.queuing.QueueBrowser;
import org.swisspush.gateleen.queue.queuing.QueueClient;
import org.swisspush.gateleen.queue.queuing.QueueProcessor;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.QueueCircuitBreaker;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.QueueCircuitBreakerStorage;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.api.QueueCircuitBreakerHttpRequestHandler;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.configuration.QueueCircuitBreakerConfigurationResourceManager;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.impl.QueueCircuitBreakerImpl;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.impl.RedisQueueCircuitBreakerStorage;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.util.QueueCircuitBreakerRulePatternToCircuitMapping;
import org.swisspush.gateleen.queue.queuing.splitter.QueueSplitter;
import org.swisspush.gateleen.queue.queuing.splitter.QueueSplitterImpl;
import org.swisspush.gateleen.routing.CustomHttpResponseHandler;
import org.swisspush.gateleen.routing.DeferCloseHttpClient;
import org.swisspush.gateleen.routing.Router;
import org.swisspush.gateleen.routing.RuleProvider;
import org.swisspush.gateleen.routing.auth.DefaultOAuthProvider;
import org.swisspush.gateleen.runconfig.RunConfig;
import org.swisspush.gateleen.scheduler.SchedulerResourceManager;
import org.swisspush.gateleen.security.PatternHolder;
import org.swisspush.gateleen.security.authorization.Authorizer;
import org.swisspush.gateleen.security.content.ContentTypeConstraintHandler;
import org.swisspush.gateleen.security.content.ContentTypeConstraintRepository;
import org.swisspush.gateleen.user.RoleProfileHandler;
import org.swisspush.gateleen.user.UserProfileHandler;
import org.swisspush.gateleen.validation.*;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;

import static org.swisspush.gateleen.core.exception.GateleenExceptionFactory.newGateleenWastefulExceptionFactory;

/**
 * Playground server to try Gateleen at home.
 *
 * @author https://github.com/lbovet [Laurent Bovet]
 */
public class Server extends AbstractVerticle {

    private Logger log = LoggerFactory.getLogger(Server.class);

    private static final String PREFIX = RunConfig.SERVER_NAME + ".";
    private static final String ROOT = "/playground";
    private static final String SERVER_ROOT = ROOT + "/server";
    private static final String RULES_ROOT = SERVER_ROOT + "/admin/v1/routing/rules";
    private static final String RETURN_HTTP_STATUS_ROOT = SERVER_ROOT + "/return-with-status-code";
    private static final String ROLE_PATTERN = "^z-playground[-_](.*)$";
    private static final String ROLE_PREFIX  = "z-playground-";

    private static final String JMX_DOMAIN = "org.swisspush.gateleen";

    /*
     * Ports
     */
    private int defaultRedisPort = 6379;
    private int mainPort = 7012;
    private int circuitBreakerPort = 7013;
    private int storagePort = 8989;

    private HttpServer mainServer;
    private RedisClient redisClient;
    private RedisAPI redisApi;
    private ResourceStorage storage;
    private CacheStorage cacheStorage;
    private CacheDataFetcher cacheDataFetcher;
    private Authorizer authorizer;
    private Router router;

    /*
     * Managers
     */
    private LoggingResourceManager loggingResourceManager;
    private LogAppenderRepository logAppenderRepository;
    private ConfigurationResourceManager configurationResourceManager;
    private ValidationResourceManager validationResourceManager;
    private ValidationSchemaProvider validationSchemaProvider;
    private Validator validator;
    private SchedulerResourceManager schedulerResourceManager;
    private QueueCircuitBreakerConfigurationResourceManager queueCircuitBreakerConfigurationResourceManager;
    private ReducedPropagationManager reducedPropagationManager;

    /*
     * Handlers
     */
    private MonitoringHandler monitoringHandler;
    private EventBusHandler eventBusHandler;
    private UserProfileHandler userProfileHandler;
    private RoleProfileHandler roleProfileHandler;
    private CORSHandler corsHandler;
    private ExpansionHandler expansionHandler;
    private DeltaHandler deltaHandler;
    private CopyResourceHandler copyResourceHandler;
    private ValidationHandler validationHandler;
    private QoSHandler qosHandler;
    private HookHandler hookHandler;
    private ZipExtractHandler zipExtractHandler;
    private DelegateHandler delegateHandler;
    private KafkaHandler kafkaHandler;
    private CustomHttpResponseHandler customHttpResponseHandler;
    private ContentTypeConstraintHandler contentTypeConstraintHandler;
    private CacheHandler cacheHandler;

    private QueueSplitter queueSplitter;

    public static void main(String[] args) {
        Vertx.vertx().deployVerticle("org.swisspush.gateleen.playground.Server", event ->
                LoggerFactory.getLogger(Server.class).info("[_] Gateleen - http://localhost:7012/gateleen/")
        );
    }

    @Override
    public void start() {
        final LocalHttpClient selfClient = new LocalHttpClient(vertx);
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
                log.info("Merging external config {}", externalConfig);
                props.putAll(RunConfig.subMap(new ResourcePropertySource(externalConfigResource).getSource(), "redis."));
            } else {
                log.info("No external config found under {}", externalConfig);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        String redisHost = (String) props.get("redis.host");
        Integer redisPort = (Integer) props.get("redis.port");
        boolean redisEnableTls = props.get("redis.enableTls") != null ? (Boolean) props.get("redis.enableTls") : false;

        props.put(ExpansionHandler.MAX_EXPANSION_LEVEL_HARD_PROPERTY, "100");
        props.put(ExpansionHandler.MAX_EXPANSION_LEVEL_SOFT_PROPERTY, "50");

        RunConfig.deployModules(vertx, Server.class, props, success -> {
            if (success) {
                String protocol = redisEnableTls ? "rediss://" : "redis://";
                redisClient = new RedisClient(vertx, new NetClientOptions(), new PoolOptions(), new RedisStandaloneConnectOptions().setConnectionString(protocol + redisHost + ":" + redisPort), TracingPolicy.IGNORE);
                redisApi = RedisAPI.api(redisClient);
                RedisProvider redisProvider = () -> Future.succeededFuture(redisApi);

                new CustomRedisMonitor(vertx, redisProvider, "main", "rest-storage", 10).start();
                storage = new EventBusResourceStorage(vertx.eventBus(), Address.storageAddress() + "-main");
                corsHandler = new CORSHandler();

                RuleProvider ruleProvider = new RuleProvider(vertx, RULES_ROOT, storage, props);

                deltaHandler = new DeltaHandler(redisProvider, selfClient, ruleProvider, true);
                expansionHandler = new ExpansionHandler(ruleProvider, selfClient, props, ROOT);
                copyResourceHandler = new CopyResourceHandler(selfClient, SERVER_ROOT + "/v1/copy");
                monitoringHandler = new MonitoringHandler(vertx, storage, PREFIX, SERVER_ROOT + "/monitoring/rpr");

                Lock lock = new RedisBasedLock(redisProvider, newGateleenWastefulExceptionFactory());

                cacheStorage = new RedisCacheStorage(vertx, lock, redisProvider, 20 * 1000);
                cacheDataFetcher = new DefaultCacheDataFetcher(new ClientRequestCreator(selfClient));
                cacheHandler = new CacheHandler(cacheDataFetcher, cacheStorage, SERVER_ROOT + "/cache");

                qosHandler = new QoSHandler(vertx, storage, SERVER_ROOT + "/admin/v1/qos", props, PREFIX);
                qosHandler.enableResourceLogging(true);

                configurationResourceManager = new ConfigurationResourceManager(vertx, storage);
                configurationResourceManager.enableResourceLogging(true);

                eventBusHandler = new EventBusHandler(vertx, SERVER_ROOT + "/event/v1/",
                        SERVER_ROOT + "/event/v1/sock/", "event/channels/",
                        "channels/([^/]+).*", configurationResourceManager, SERVER_ROOT + "/admin/v1/hookconfig");
                eventBusHandler.setEventbusBridgePingInterval(RunConfig.EVENTBUS_BRIDGE_PING_INTERVAL);

                logAppenderRepository = new DefaultLogAppenderRepository(vertx);
                loggingResourceManager = new LoggingResourceManager(vertx, storage, SERVER_ROOT + "/admin/v1/logging");
                loggingResourceManager.enableResourceLogging(true);


                ContentTypeConstraintRepository repository = new ContentTypeConstraintRepository();
                contentTypeConstraintHandler = new ContentTypeConstraintHandler(configurationResourceManager, repository,
                        SERVER_ROOT + "/admin/v1/contentTypeConstraints",
                        Arrays.asList(
                                new PatternHolder("application/json"),
                                new PatternHolder("application/x-www-form-urlencoded"),
                                new PatternHolder("multipart/form-data")
                        ));
                contentTypeConstraintHandler.initialize();

                userProfileHandler = new UserProfileHandler(vertx, storage, RunConfig.buildUserProfileConfiguration());
                userProfileHandler.enableResourceLogging(true);

                roleProfileHandler = new RoleProfileHandler(vertx, storage, SERVER_ROOT + "/roles/v1/([^/]+)/profile");
                roleProfileHandler.enableResourceLogging(true);

                QueueClient queueClient = new QueueClient(vertx, monitoringHandler);
                reducedPropagationManager = new ReducedPropagationManager(vertx, new RedisReducedPropagationStorage(redisProvider),
                        queueClient, lock);
                reducedPropagationManager.startExpiredQueueProcessing(5000);

                queueSplitter = new QueueSplitterImpl(configurationResourceManager, SERVER_ROOT + "/admin/v1/queueSplitters");
                queueSplitter.initialize();

                hookHandler = new HookHandler(vertx, selfClient, storage, loggingResourceManager, logAppenderRepository,
                        monitoringHandler,SERVER_ROOT + "/users/v1/%s/profile",
                        SERVER_ROOT + "/hooks/v1/", queueClient,false, reducedPropagationManager, null, storage, Router.DEFAULT_ROUTER_MULTIPLIER, queueSplitter);
                hookHandler.enableResourceLogging(true);

                authorizer = new Authorizer(vertx, storage, SERVER_ROOT + "/security/v1/", ROLE_PATTERN, ROLE_PREFIX, props);
                authorizer.enableResourceLogging(true);

                validationResourceManager = new ValidationResourceManager(vertx, storage, SERVER_ROOT + "/admin/v1/validation");
                validationResourceManager.enableResourceLogging(true);
                validationSchemaProvider = new DefaultValidationSchemaProvider(vertx, new ClientRequestCreator(selfClient), Duration.ofSeconds(30));
                validator = new Validator(storage, ROOT + "/schemas/apis/", validationSchemaProvider);
                validationHandler = new ValidationHandler(validationResourceManager, selfClient, validator);

                KafkaProducerRepository kafkaProducerRepository = new KafkaProducerRepository(vertx);
                KafkaMessageSender kafkaMessageSender = new KafkaMessageSender();
                KafkaMessageValidator messageValidator = new KafkaMessageValidator(validationResourceManager, validator);
                kafkaHandler = new KafkaHandler(configurationResourceManager, messageValidator, kafkaProducerRepository, kafkaMessageSender,
                        SERVER_ROOT + "/admin/v1/kafka/topicsConfig",SERVER_ROOT + "/streaming/");
                kafkaHandler.initialize();

                schedulerResourceManager = new SchedulerResourceManager(vertx, redisProvider, storage, monitoringHandler,
                        SERVER_ROOT + "/admin/v1/schedulers");
                schedulerResourceManager.enableResourceLogging(true);

                zipExtractHandler = new ZipExtractHandler(selfClient);

                delegateHandler = new DelegateHandler(vertx, selfClient, storage, monitoringHandler,
                        SERVER_ROOT + "/admin/v1/delegates/", props, null);
                delegateHandler.enableResourceLogging(true);

                customHttpResponseHandler = new CustomHttpResponseHandler(RETURN_HTTP_STATUS_ROOT);

                router = Router.builder()
                        .withServerPath(SERVER_ROOT)
                        .withRulesPath(SERVER_ROOT + "/admin/v1/routing/rules")
                        .withUserProfilePath(SERVER_ROOT + "/users/v1/%s/profile")
                        .withVertx(vertx)
                        .withSelfClient(selfClient)
                        .withStorage(storage).withStoragePort(storagePort)
                        .withInfo(info)
                        .withMonitoringHandler(monitoringHandler)
                        .withLoggingResourceManager(loggingResourceManager)
                        .withLogAppenderRepository(logAppenderRepository)
                        .withResourceLogging(true)
                        .withRoutingConfiguration(configurationResourceManager, SERVER_ROOT + "/admin/v1/routing/config")
                        .withHttpClientFactory(this::createHttpClientForRouter)
                        .withOAuthProvider(new DefaultOAuthProvider(vertx))
                        .addDoneHandler(aVoid -> {
                            hookHandler.init();
                            delegateHandler.init();
                        })
                        .build();

                QueueCircuitBreakerRulePatternToCircuitMapping rulePatternToCircuitMapping = new QueueCircuitBreakerRulePatternToCircuitMapping();

                queueCircuitBreakerConfigurationResourceManager = new QueueCircuitBreakerConfigurationResourceManager(vertx,
                        storage, SERVER_ROOT + "/admin/v1/circuitbreaker");
                queueCircuitBreakerConfigurationResourceManager.enableResourceLogging(true);
                QueueCircuitBreakerStorage queueCircuitBreakerStorage = new RedisQueueCircuitBreakerStorage(redisProvider);
                QueueCircuitBreakerHttpRequestHandler requestHandler = new QueueCircuitBreakerHttpRequestHandler(vertx, queueCircuitBreakerStorage,
                        SERVER_ROOT + "/queuecircuitbreaker/circuit");

                QueueCircuitBreaker queueCircuitBreaker = new QueueCircuitBreakerImpl(vertx, lock,
                        Address.redisquesAddress(), queueCircuitBreakerStorage, ruleProvider, rulePatternToCircuitMapping,
                        queueCircuitBreakerConfigurationResourceManager, requestHandler, circuitBreakerPort);

                new QueueProcessor(vertx, selfClient, monitoringHandler, queueCircuitBreaker);
                final QueueBrowser queueBrowser = new QueueBrowser(vertx, SERVER_ROOT + "/queuing", Address.redisquesAddress(),
                        monitoringHandler);

                LogController logController = new LogController();
                logController.registerLogConfiguratorMBean(JMX_DOMAIN);

                ResetMetricsController resetMetricsController = new ResetMetricsController(vertx);
                resetMetricsController.registerResetMetricsControlMBean(JMX_DOMAIN, PREFIX);

                RunConfig runConfig = RunConfig.with()
                        .authorizer(authorizer)
                        .validationResourceManager(validationResourceManager)
                        .validationHandler(validationHandler)
                        .cacheHandler(cacheHandler)
                        .corsHandler(corsHandler)
                        .deltaHandler(deltaHandler)
                        .expansionHandler(expansionHandler)
                        .hookHandler(hookHandler)
                        .qosHandler(qosHandler)
                        .copyResourceHandler(copyResourceHandler)
                        .eventBusHandler(eventBusHandler)
                        .kafkaHandler(kafkaHandler)
                        .roleProfileHandler(roleProfileHandler)
                        .userProfileHandler(userProfileHandler)
                        .loggingResourceManager(loggingResourceManager)
                        .configurationResourceManager(configurationResourceManager)
                        .queueCircuitBreakerConfigurationResourceManager(queueCircuitBreakerConfigurationResourceManager)
                        .queueSplitter(queueSplitter)
                        .schedulerResourceManager(schedulerResourceManager)
                        .zipExtractHandler(zipExtractHandler)
                        .delegateHandler(delegateHandler)
                        .customHttpResponseHandler(customHttpResponseHandler)
                        .contentTypeConstraintHandler(contentTypeConstraintHandler)
                        .build(vertx, redisProvider, Server.class, router, monitoringHandler, queueBrowser);
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
                mainServer.requestHandler(vertxRouter);
                mainServer.listen(mainPort);
            }
        });
    }

    private HttpClient createHttpClientForRouter(HttpClientOptions opts) {
        // Setup an original vertx http client.
        HttpClient client = vertx.createHttpClient(opts);
        // But decorate it for advanced close handling
        client = new DeferCloseHttpClient(vertx, client);
        return client;
    }

}

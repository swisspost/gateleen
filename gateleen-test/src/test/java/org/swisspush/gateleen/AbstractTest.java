package org.swisspush.gateleen;

import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.parsing.Parser;
import io.restassured.specification.RequestSpecification;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.tracing.TracingPolicy;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import io.vertx.ext.web.RoutingContext;
import io.vertx.redis.client.PoolOptions;
import io.vertx.redis.client.RedisAPI;
import io.vertx.redis.client.RedisStandaloneConnectOptions;
import io.vertx.redis.client.impl.RedisClient;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.runner.RunWith;
import org.swisspush.gateleen.cache.CacheHandler;
import org.swisspush.gateleen.cache.fetch.DefaultCacheDataFetcher;
import org.swisspush.gateleen.cache.storage.RedisCacheStorage;
import org.swisspush.gateleen.core.configuration.ConfigurationResourceManager;
import org.swisspush.gateleen.core.cors.CORSHandler;
import org.swisspush.gateleen.core.event.EventBusHandler;
import org.swisspush.gateleen.core.exception.GateleenExceptionFactory;
import org.swisspush.gateleen.core.http.ClientRequestCreator;
import org.swisspush.gateleen.core.http.LocalHttpClient;
import org.swisspush.gateleen.core.lock.Lock;
import org.swisspush.gateleen.core.lock.impl.RedisBasedLock;
import org.swisspush.gateleen.core.property.PropertyHandler;
import org.swisspush.gateleen.core.redis.RedisByNameProvider;
import org.swisspush.gateleen.core.redis.RedisProvider;
import org.swisspush.gateleen.core.resource.CopyResourceHandler;
import org.swisspush.gateleen.core.storage.EventBusResourceStorage;
import org.swisspush.gateleen.core.storage.ResourceStorage;
import org.swisspush.gateleen.core.util.Address;
import org.swisspush.gateleen.core.util.RoleExtractor;
import org.swisspush.gateleen.delegate.DelegateHandler;
import org.swisspush.gateleen.delta.DeltaHandler;
import org.swisspush.gateleen.expansion.ExpansionHandler;
import org.swisspush.gateleen.expansion.ZipExtractHandler;
import org.swisspush.gateleen.hook.HookHandler;
import org.swisspush.gateleen.hook.reducedpropagation.ReducedPropagationManager;
import org.swisspush.gateleen.hook.reducedpropagation.impl.RedisReducedPropagationStorage;
import org.swisspush.gateleen.logging.DefaultLogAppenderRepository;
import org.swisspush.gateleen.logging.LogAppenderRepository;
import org.swisspush.gateleen.logging.LogController;
import org.swisspush.gateleen.logging.LoggingResourceManager;
import org.swisspush.gateleen.merge.MergeHandler;
import org.swisspush.gateleen.monitoring.CustomRedisMonitor;
import org.swisspush.gateleen.monitoring.MonitoringHandler;
import org.swisspush.gateleen.monitoring.ResetMetricsController;
import org.swisspush.gateleen.packing.PackingHandler;
import org.swisspush.gateleen.packing.validation.PackingValidatorImpl;
import org.swisspush.gateleen.qos.QoSHandler;
import org.swisspush.gateleen.queue.queuing.QueueClient;
import org.swisspush.gateleen.queue.queuing.QueueProcessor;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.QueueCircuitBreaker;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.QueueCircuitBreakerStorage;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.api.QueueCircuitBreakerHttpRequestHandler;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.configuration.QueueCircuitBreakerConfigurationResourceManager;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.impl.QueueCircuitBreakerImpl;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.impl.RedisQueueCircuitBreakerStorage;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.util.QueueCircuitBreakerRulePatternToCircuitMapping;
import org.swisspush.gateleen.routing.CustomHttpResponseHandler;
import org.swisspush.gateleen.routing.Router;
import org.swisspush.gateleen.routing.RuleProvider;
import org.swisspush.gateleen.runconfig.RunConfig;
import org.swisspush.gateleen.scheduler.SchedulerResourceManager;
import org.swisspush.gateleen.user.RoleProfileHandler;
import org.swisspush.gateleen.user.UserProfileHandler;
import redis.clients.jedis.Jedis;

import javax.management.*;
import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.swisspush.gateleen.core.exception.GateleenExceptionFactory.newGateleenWastefulExceptionFactory;

/**
 * TestVerticle all Gateleen tests. <br />
 *
 * @author https://github.com/ljucam [Mario Ljuca]
 */
@RunWith(VertxUnitRunner.class)
public abstract class AbstractTest {
    protected static final String SERVER_NAME = "gateleen";
    protected static final String PREFIX = SERVER_NAME + ".";
    private static final String JMX_DOMAIN = "org.swisspush.gateleen";

    public static final String ROOT = "/playground";
    public static final String SERVER_ROOT = ROOT + "/server";
    private static final String RULES_ROOT = SERVER_ROOT + "/admin/v1/routing/rules";
    private static final String DELEGATE_ROOT = ROOT + "/server/delegate/v1/delegates/";
    private static final String RETURN_HTTP_STATUS_ROOT = SERVER_ROOT + "/return-with-status-code";
    public static final int MAIN_PORT = 3332;
    protected static final int REDIS_PORT = 6379;
    protected static final int STORAGE_PORT = 8989;
    private static final int CIRCUIT_BREAKER_REST_API_PORT = 7014;
    protected static final int REDISQUES_API_PORT = 7015;

    /**
     * Basis configuration for RestAssured
     */
    private static RequestSpecification REQUEST_SPECIFICATION = new RequestSpecBuilder()
            .addHeader("content-type", "application/json")
            .setPort(MAIN_PORT)
            .setBasePath(ROOT)
            .build();

    protected static Vertx vertx;
    protected static QoSHandler qosHandler;
    protected static PropertyHandler propertyHandler;
    protected static Jedis jedis;
    private static HttpServer mainServer;
    private static final GateleenExceptionFactory exceptionFactory = newGateleenWastefulExceptionFactory();
    protected final static Map<String, Object> props = new HashMap<>();
    protected static SchedulerResourceManager schedulerResourceManager;
    protected static HookHandler hookHandler;
    protected static CacheHandler cacheHandler;
    protected static PackingHandler packingHandler;
    protected static CustomHttpResponseHandler customHttpResponseHandler;

    /**
     * Starts redis before the test classes are instantiated.
     */
    @BeforeClass
    public static void setupBeforeClass(TestContext context) {
        Async async = context.async();
        vertx = Vertx.vertx();

        jedis = new Jedis("localhost", REDIS_PORT, 10000);
        jedis.flushAll();

        final JsonObject info = new JsonObject();
        final LocalHttpClient selfClient = new LocalHttpClient(vertx, exceptionFactory);
        props.putAll(RunConfig.buildRedisProps("localhost", REDIS_PORT));

        String redisHost = (String) props.get("redis.host");
        Integer redisPort = (Integer) props.get("redis.port");
        boolean redisEnableTls = props.get("redis.enableTls") != null ? (Boolean) props.get("redis.enableTls") : false;

        props.put(ExpansionHandler.MAX_EXPANSION_LEVEL_HARD_PROPERTY, "100");
        props.put(ExpansionHandler.MAX_EXPANSION_LEVEL_SOFT_PROPERTY, "4");

        RunConfig.deployModules(vertx, AbstractTest.class, props, success -> {
            if (success) {
                String protocol = redisEnableTls ? "rediss://" : "redis://";
                RedisClient redisClient = new RedisClient(vertx, new NetClientOptions(), new PoolOptions(), new RedisStandaloneConnectOptions().setConnectionString(protocol + redisHost + ":" + redisPort), TracingPolicy.IGNORE);
                RedisAPI redisAPI = RedisAPI.api(redisClient);
                RedisProvider redisProvider = () -> Future.succeededFuture(redisAPI);
                RedisByNameProvider redisByNameProvider = storageName -> Future.succeededFuture(redisAPI);

                ResourceStorage storage = new EventBusResourceStorage(vertx.eventBus(), Address.storageAddress() + "-main", exceptionFactory);
                MonitoringHandler monitoringHandler = new MonitoringHandler(vertx, storage, PREFIX);
                ConfigurationResourceManager configurationResourceManager = new ConfigurationResourceManager(vertx, storage, exceptionFactory);

                String eventBusConfigurationResource = SERVER_ROOT + "/admin/v1/hookconfig";
                EventBusHandler eventBusHandler = new EventBusHandler(vertx, SERVER_ROOT + "/event/v1/",
                        SERVER_ROOT + "/event/v1/sock/", "event-",
                        "channels/([^/]+).*", configurationResourceManager, eventBusConfigurationResource);

                eventBusHandler.setEventbusBridgePingInterval(RunConfig.EVENTBUS_BRIDGE_PING_INTERVAL);

                LogAppenderRepository logAppenderRepository = new DefaultLogAppenderRepository(vertx);
                LoggingResourceManager loggingResourceManager = new LoggingResourceManager(vertx, storage,
                        SERVER_ROOT + "/admin/v1/logging");
                UserProfileHandler userProfileHandler = new UserProfileHandler(vertx, storage, RunConfig.buildUserProfileConfiguration());
                RoleProfileHandler roleProfileHandler = new RoleProfileHandler(vertx, storage, SERVER_ROOT + "/roles/v1/([^/]+)/profile");
                qosHandler = new QoSHandler(vertx, storage, SERVER_ROOT + "/admin/v1/qos", props, PREFIX);

                Lock lock = new RedisBasedLock(redisProvider, newGateleenWastefulExceptionFactory());

                QueueClient queueClient = new QueueClient(vertx, monitoringHandler);
                ReducedPropagationManager reducedPropagationManager = new ReducedPropagationManager(vertx,
                        new RedisReducedPropagationStorage(redisProvider, exceptionFactory), queueClient, lock, exceptionFactory);
                reducedPropagationManager.startExpiredQueueProcessing(1000);
                hookHandler = new HookHandler(vertx, selfClient, storage, loggingResourceManager, logAppenderRepository, monitoringHandler,
                        SERVER_ROOT + "/users/v1/%s/profile", ROOT + "/server/hooks/v1/",
                        queueClient, false, reducedPropagationManager);
                propertyHandler = new PropertyHandler(ROOT, props);
                schedulerResourceManager = new SchedulerResourceManager(vertx, redisProvider, storage, monitoringHandler,
                        SERVER_ROOT + "/admin/v1/schedulers");
                ResetMetricsController resetMetricsController = new ResetMetricsController(vertx);
                resetMetricsController.registerResetMetricsControlMBean(JMX_DOMAIN, PREFIX);
                LogController logController = new LogController();
                logController.registerLogConfiguratorMBean(JMX_DOMAIN);
                ZipExtractHandler zipExtractHandler = new ZipExtractHandler(selfClient);
                DelegateHandler delegateHandler = new DelegateHandler(vertx, selfClient, storage, DELEGATE_ROOT,
                        props, null);
                MergeHandler mergeHandler = new MergeHandler(selfClient);

                cacheHandler = new CacheHandler(
                        new DefaultCacheDataFetcher(new ClientRequestCreator(selfClient)),
                        new RedisCacheStorage(vertx, lock, redisProvider, exceptionFactory, 60000),
                        SERVER_ROOT + "/cache");

                packingHandler = new PackingHandler(vertx, "packed-", Address.redisquesAddress(),
                        RoleExtractor.groupHeader, new PackingValidatorImpl(), exceptionFactory);

                customHttpResponseHandler = new CustomHttpResponseHandler(RETURN_HTTP_STATUS_ROOT);

                // ------
                RuleProvider ruleProvider = new RuleProvider(vertx, RULES_ROOT, storage, props);
                QueueCircuitBreakerRulePatternToCircuitMapping rulePatternToCircuitMapping =
                        new QueueCircuitBreakerRulePatternToCircuitMapping();

                QueueCircuitBreakerConfigurationResourceManager queueCircuitBreakerConfigurationResourceManager =
                        new QueueCircuitBreakerConfigurationResourceManager(vertx, storage, SERVER_ROOT + "/admin/v1/circuitbreaker");
                QueueCircuitBreakerStorage queueCircuitBreakerStorage = new RedisQueueCircuitBreakerStorage(redisProvider, exceptionFactory);
                QueueCircuitBreakerHttpRequestHandler requestHandler = new QueueCircuitBreakerHttpRequestHandler(vertx,
                        queueCircuitBreakerStorage,SERVER_ROOT + "/queuecircuitbreaker/circuit");

                QueueCircuitBreaker queueCircuitBreaker = new QueueCircuitBreakerImpl(vertx, lock,
                        Address.redisquesAddress(), queueCircuitBreakerStorage, ruleProvider, exceptionFactory,
                        rulePatternToCircuitMapping, queueCircuitBreakerConfigurationResourceManager, requestHandler,
                        CIRCUIT_BREAKER_REST_API_PORT);

                new QueueProcessor(vertx, selfClient, monitoringHandler, queueCircuitBreaker);

                new CustomRedisMonitor(vertx, redisProvider, "main", "rest-storage", 10).start();
                Router router = Router.builder()
                        .withVertx(vertx)
                        .withStorage(storage)
                        .withProperties(props)
                        .withLoggingResourceManager(loggingResourceManager)
                        .withMonitoringHandler(monitoringHandler)
                        .withSelfClient(selfClient)
                        .withServerPath(SERVER_ROOT)
                        .withRulesPath(SERVER_ROOT + "/admin/v1/routing/rules")
                        .withUserProfilePath(SERVER_ROOT + "/users/v1/%s/profile")
                        .withInfo(info)
                        .withStoragePort(STORAGE_PORT)
                        .withRoutingConfiguration(configurationResourceManager, SERVER_ROOT + "/admin/v1/routing/config")
                        .withDoneHandlers(List.of(doneHandler -> {
                            System.out.println("Router initialized!");
                            hookHandler.init();
                            delegateHandler.init();
                        }))
                        .build();

                System.setProperty("org.swisspush.gateleen.addcorsheaders", "true");

                RunConfig runConfig =
                        RunConfig.with()
                                .cacheHandler(cacheHandler)
                                .packingHandler(packingHandler)
                                .corsHandler(new CORSHandler())
                                .deltaHandler(new DeltaHandler(vertx, redisByNameProvider, selfClient, ruleProvider, loggingResourceManager, logAppenderRepository))
                                .expansionHandler(new ExpansionHandler(vertx, storage, selfClient, props, ROOT, RULES_ROOT))
                                .hookHandler(hookHandler)
                                .qosHandler(qosHandler)
                                .copyResourceHandler(new CopyResourceHandler(selfClient, exceptionFactory, SERVER_ROOT + "/v1/copy"))
                                .eventBusHandler(eventBusHandler)
                                .roleProfileHandler(roleProfileHandler)
                                .userProfileHandler(userProfileHandler)
                                .loggingResourceManager(loggingResourceManager)
                                .configurationResourceManager(configurationResourceManager)
                                .queueCircuitBreakerConfigurationResourceManager(queueCircuitBreakerConfigurationResourceManager)
                                .schedulerResourceManager(schedulerResourceManager)
                                .propertyHandler(propertyHandler)
                                .zipExtractHandler(zipExtractHandler)
                                .delegateHandler(delegateHandler)
                                .mergeHandler(mergeHandler)
                                .customHttpResponseHandler(customHttpResponseHandler)
                                .build(vertx, redisProvider, AbstractTest.class, router, monitoringHandler);
                Handler<RoutingContext> routingContextHandlerrNew = runConfig.buildRoutingContextHandler();
                selfClient.setRoutingContexttHandler(routingContextHandlerrNew);
                mainServer = vertx.createHttpServer();
                io.vertx.ext.web.Router vertxRouter = io.vertx.ext.web.Router.router(vertx);
                eventBusHandler.install(vertxRouter);
                vertxRouter.route().handler(routingContextHandlerrNew);
                mainServer.requestHandler(vertxRouter);
                mainServer.listen(MAIN_PORT, event -> {
                    if (event.succeeded()) {
                        async.complete();
                    } else {
                        context.fail("Server not listening on port " + MAIN_PORT);
                    }
                });
            }
        });
        async.awaitSuccess();
    }

    /**
     * Stopps redis after the test are finished.
     */
    @AfterClass
    public static void tearDownAfterClass(TestContext context) throws MalformedObjectNameException, InstanceNotFoundException, MBeanRegistrationException {
        Async async = context.async();
        jedis.close();
        mainServer.close();
        removeRegisteredMBean();
        vertx.close(event -> async.complete());
        async.awaitSuccess();
    }

    private static void removeRegisteredMBean() throws MalformedObjectNameException, MBeanRegistrationException, InstanceNotFoundException {
        MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
        if (mbs.getMBeanCount() == 0) {
            return;
        }
        Set<ObjectName> beanNameList = mbs.queryNames(null, null);
        for (ObjectName beanName : beanNameList) {
            if (beanName.toString().startsWith("metrics:name=gateleen") || beanName.toString().startsWith("metrics:name=redis.main")) {
                if (mbs.isRegistered(beanName)) {
                    mbs.unregisterMBean(beanName);
                }
            }
        }

    }

    @Before
    public void setUpRestAssured() {
        initRestAssured();
    }

    @After
    public void tearDown() {
        jedis.flushAll();
    }

    /**
     * Initialitzes RestAssured. <br />
     * Can be overwritten by test classes, if
     * needed.
     */
    protected void initRestAssured() {
        RestAssured.port = MAIN_PORT;
        RestAssured.registerParser("application/json; charset=utf-8", Parser.JSON);
        RestAssured.defaultParser = Parser.JSON;

        RestAssured.requestSpecification = REQUEST_SPECIFICATION;
        RestAssured.requestSpecification.baseUri("http://localhost:" + MAIN_PORT);
        RestAssured.requestSpecification.basePath(ROOT);
    }
}

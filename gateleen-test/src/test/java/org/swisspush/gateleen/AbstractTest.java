package org.swisspush.gateleen;

import com.jayway.restassured.RestAssured;
import com.jayway.restassured.builder.RequestSpecBuilder;
import com.jayway.restassured.parsing.Parser;
import com.jayway.restassured.specification.RequestSpecification;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import io.vertx.ext.web.RoutingContext;
import io.vertx.redis.RedisClient;
import io.vertx.redis.RedisOptions;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.runner.RunWith;
import org.swisspush.gateleen.core.configuration.ConfigurationResourceManager;
import org.swisspush.gateleen.core.cors.CORSHandler;
import org.swisspush.gateleen.core.event.EventBusHandler;
import org.swisspush.gateleen.core.http.LocalHttpClient;
import org.swisspush.gateleen.core.lock.Lock;
import org.swisspush.gateleen.core.lock.impl.RedisBasedLock;
import org.swisspush.gateleen.core.property.PropertyHandler;
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
import org.swisspush.gateleen.logging.LogController;
import org.swisspush.gateleen.logging.LoggingResourceManager;
import org.swisspush.gateleen.merge.MergeHandler;
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
import org.swisspush.gateleen.routing.Router;
import org.swisspush.gateleen.routing.RuleProvider;
import org.swisspush.gateleen.runconfig.RunConfig;
import org.swisspush.gateleen.scheduler.SchedulerResourceManager;
import org.swisspush.gateleen.user.RoleProfileHandler;
import org.swisspush.gateleen.user.UserProfileHandler;
import redis.clients.jedis.Jedis;

import javax.management.*;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    public static final int MAIN_PORT = 3332;
    protected static final int REDIS_PORT = 6379;
    private static final int CIRCUIT_BREAKER_REST_API_PORT = 7014;

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
    protected final static Map<String, Object> props = new HashMap<>();
    protected static SchedulerResourceManager schedulerResourceManager;
    protected static HookHandler hookHandler;

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
        final LocalHttpClient selfClient = new LocalHttpClient(vertx);
        props.putAll(RunConfig.buildRedisProps("localhost", REDIS_PORT));

        String redisHost = (String) props.get("redis.host");
        Integer redisPort = (Integer) props.get("redis.port");

        props.put(ExpansionHandler.MAX_EXPANSION_LEVEL_HARD_PROPERTY, "100");
        props.put(ExpansionHandler.MAX_EXPANSION_LEVEL_SOFT_PROPERTY, "4");

        RunConfig.deployModules(vertx, AbstractTest.class, props, success -> {
            if (success) {
                RedisClient redisClient = RedisClient.create(vertx, new RedisOptions().setHost(redisHost).setPort(redisPort));
                ResourceStorage storage = new EventBusResourceStorage(vertx.eventBus(), Address.storageAddress() + "-main");
                MonitoringHandler monitoringHandler = new MonitoringHandler(vertx, storage, PREFIX);
                ConfigurationResourceManager configurationResourceManager = new ConfigurationResourceManager(vertx, storage);

                String eventBusConfigurationResource = SERVER_ROOT + "/admin/v1/hookconfig";
                EventBusHandler eventBusHandler = new EventBusHandler(vertx, SERVER_ROOT + "/event/v1/", SERVER_ROOT + "/event/v1/sock/*", "event-", "channels/([^/]+).*", configurationResourceManager, eventBusConfigurationResource);

                eventBusHandler.setEventbusBridgePingInterval(RunConfig.EVENTBUS_BRIDGE_PING_INTERVAL);

                LoggingResourceManager loggingResourceManager = new LoggingResourceManager(vertx, storage, SERVER_ROOT + "/admin/v1/logging");
                UserProfileHandler userProfileHandler = new UserProfileHandler(vertx, storage, RunConfig.buildUserProfileConfiguration());
                RoleProfileHandler roleProfileHandler = new RoleProfileHandler(vertx, storage, SERVER_ROOT + "/roles/v1/([^/]+)/profile");
                qosHandler = new QoSHandler(vertx, storage, SERVER_ROOT + "/admin/v1/qos", props, PREFIX);

                Lock lock = new RedisBasedLock(redisClient);

                QueueClient queueClient = new QueueClient(vertx, monitoringHandler);
                ReducedPropagationManager reducedPropagationManager = new ReducedPropagationManager(vertx, new RedisReducedPropagationStorage(redisClient), queueClient, lock);
                reducedPropagationManager.startExpiredQueueProcessing(1000);
                hookHandler = new HookHandler(vertx, selfClient, storage, loggingResourceManager, monitoringHandler,
                        SERVER_ROOT + "/users/v1/%s/profile", ROOT + "/server/hooks/v1/", queueClient, false, reducedPropagationManager);
                propertyHandler = new PropertyHandler(ROOT, props);
                schedulerResourceManager = new SchedulerResourceManager(vertx, redisClient, storage, monitoringHandler, SERVER_ROOT + "/admin/v1/schedulers");
                ResetMetricsController resetMetricsController = new ResetMetricsController(vertx);
                resetMetricsController.registerResetMetricsControlMBean(JMX_DOMAIN, PREFIX);
                LogController logController = new LogController();
                logController.registerLogConfiguratorMBean(JMX_DOMAIN);
                ZipExtractHandler zipExtractHandler = new ZipExtractHandler(selfClient);
                DelegateHandler delegateHandler = new DelegateHandler(vertx, selfClient, storage, monitoringHandler, DELEGATE_ROOT, props, null);
                MergeHandler mergeHandler = new MergeHandler(selfClient);

                // ------
                RuleProvider ruleProvider = new RuleProvider(vertx, RULES_ROOT, storage, props);
                QueueCircuitBreakerRulePatternToCircuitMapping rulePatternToCircuitMapping = new QueueCircuitBreakerRulePatternToCircuitMapping();

                QueueCircuitBreakerConfigurationResourceManager queueCircuitBreakerConfigurationResourceManager = new QueueCircuitBreakerConfigurationResourceManager(vertx, storage, SERVER_ROOT + "/admin/v1/circuitbreaker");
                QueueCircuitBreakerStorage queueCircuitBreakerStorage = new RedisQueueCircuitBreakerStorage(redisClient);
                QueueCircuitBreakerHttpRequestHandler requestHandler = new QueueCircuitBreakerHttpRequestHandler(vertx, queueCircuitBreakerStorage,
                        SERVER_ROOT + "/queuecircuitbreaker/circuit");

                QueueCircuitBreaker queueCircuitBreaker = new QueueCircuitBreakerImpl(vertx, lock,
                        Address.redisquesAddress(), queueCircuitBreakerStorage, ruleProvider, rulePatternToCircuitMapping,
                        queueCircuitBreakerConfigurationResourceManager, requestHandler, CIRCUIT_BREAKER_REST_API_PORT);

                new QueueProcessor(vertx, selfClient, monitoringHandler, queueCircuitBreaker);
                final QueueBrowser queueBrowser = new QueueBrowser(vertx, SERVER_ROOT + "/queuing", Address.redisquesAddress(), monitoringHandler);

                new CustomRedisMonitor(vertx, redisClient, "main", "rest-storage", 10).start();
                Router router = new Router(vertx, storage, props, loggingResourceManager, monitoringHandler, selfClient, SERVER_ROOT, SERVER_ROOT + "/admin/v1/routing/rules", SERVER_ROOT + "/users/v1/%s/profile", info,
                        (Handler<Void>) aVoid -> {
                            System.out.println("Router initialized!");
                            hookHandler.init();
                            delegateHandler.init();
                        }
                );
                router.enableRoutingConfiguration(configurationResourceManager, SERVER_ROOT + "/admin/v1/routing/config");

                System.setProperty("org.swisspush.gateleen.addcorsheaders", "true");

                RunConfig runConfig =
                        RunConfig.with()
                                .corsHandler(new CORSHandler())
                                .deltaHandler(new DeltaHandler(redisClient, selfClient))
                                .expansionHandler(new ExpansionHandler(vertx, storage, selfClient, props, ROOT, RULES_ROOT))
                                .hookHandler(hookHandler)
                                .qosHandler(qosHandler)
                                .copyResourceHandler(new CopyResourceHandler(selfClient, SERVER_ROOT + "/v1/copy"))
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
                                .build(vertx, redisClient, AbstractTest.class, router, monitoringHandler, queueBrowser);
                Handler<RoutingContext> routingContextHandlerrNew = runConfig.buildRoutingContextHandler();
                selfClient.setRoutingContexttHandler(routingContextHandlerrNew);
                mainServer = vertx.createHttpServer();
                io.vertx.ext.web.Router vertxRouter = io.vertx.ext.web.Router.router(vertx);
                eventBusHandler.install(vertxRouter);
                vertxRouter.route().handler(routingContextHandlerrNew);
                mainServer.requestHandler(vertxRouter::accept);
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
        List<String> beanNameList = new ArrayList<>();
        beanNameList.add("metrics:name=gateleen.requests.pending.count");
        beanNameList.add("metrics:name=gateleen.requests.localhost");
        beanNameList.add("metrics:name=gateleen.queues.active.count");
        beanNameList.add("metrics:name=redis.main.server.redis_git_sha1");
        beanNameList.add("metrics:name=redis.main.server.redis_git_dirty");
        beanNameList.add("metrics:name=redis.main.server.arch_bits");
        beanNameList.add("metrics:name=redis.main.server.process_id");
        beanNameList.add("metrics:name=redis.main.server.tcp_port");
        beanNameList.add("metrics:name=redis.main.server.uptime_in_seconds");
        beanNameList.add("metrics:name=redis.main.server.uptime_in_days");
        beanNameList.add("metrics:name=redis.main.server.hz");
        beanNameList.add("metrics:name=redis.main.server.lru_clock");
        beanNameList.add("metrics:name=redis.main.clients.connected_clients");
        beanNameList.add("metrics:name=redis.main.clients.client_longest_output_list");
        beanNameList.add("metrics:name=redis.main.clients.client_biggest_input_buf");
        beanNameList.add("metrics:name=redis.main.clients.blocked_clients");
        beanNameList.add("metrics:name=redis.main.memory.used_memory");
        beanNameList.add("metrics:name=redis.main.memory.used_memory_rss");
        beanNameList.add("metrics:name=redis.main.memory.used_memory_peak");
        beanNameList.add("metrics:name=redis.main.memory.total_system_memory");
        beanNameList.add("metrics:name=redis.main.memory.used_memory_lua");
        beanNameList.add("metrics:name=redis.main.memory.maxmemory");
        beanNameList.add("metrics:name=redis.main.persistence.loading");
        beanNameList.add("metrics:name=redis.main.persistence.rdb_changes_since_last_save");
        beanNameList.add("metrics:name=redis.main.persistence.rdb_bgsave_in_progress");
        beanNameList.add("metrics:name=redis.main.persistence.rdb_last_save_time");
        beanNameList.add("metrics:name=redis.main.persistence.rdb_last_bgsave_time_sec");
        beanNameList.add("metrics:name=redis.main.persistence.rdb_current_bgsave_time_sec");
        beanNameList.add("metrics:name=redis.main.persistence.aof_enabled");
        beanNameList.add("metrics:name=redis.main.persistence.aof_rewrite_in_progress");
        beanNameList.add("metrics:name=redis.main.persistence.aof_rewrite_scheduled");
        beanNameList.add("metrics:name=redis.main.persistence.aof_last_rewrite_time_sec");
        beanNameList.add("metrics:name=redis.main.persistence.aof_current_rewrite_time_sec");
        beanNameList.add("metrics:name=redis.main.persistence.aof_current_size");
        beanNameList.add("metrics:name=redis.main.persistence.aof_base_size");
        beanNameList.add("metrics:name=redis.main.persistence.aof_pending_rewrite");
        beanNameList.add("metrics:name=redis.main.persistence.aof_buffer_length");
        beanNameList.add("metrics:name=redis.main.persistence.aof_rewrite_buffer_length");
        beanNameList.add("metrics:name=redis.main.persistence.aof_pending_bio_fsync");
        beanNameList.add("metrics:name=redis.main.persistence.aof_delayed_fsync");
        beanNameList.add("metrics:name=redis.main.stats.total_connections_received");
        beanNameList.add("metrics:name=redis.main.stats.total_commands_processed");
        beanNameList.add("metrics:name=redis.main.stats.instantaneous_ops_per_sec");
        beanNameList.add("metrics:name=redis.main.stats.total_net_input_bytes");
        beanNameList.add("metrics:name=redis.main.stats.total_net_output_bytes");
        beanNameList.add("metrics:name=redis.main.stats.rejected_connections");
        beanNameList.add("metrics:name=redis.main.stats.sync_full");
        beanNameList.add("metrics:name=redis.main.stats.sync_partial_ok");
        beanNameList.add("metrics:name=redis.main.stats.sync_partial_err");
        beanNameList.add("metrics:name=redis.main.stats.expired_keys");
        beanNameList.add("metrics:name=redis.main.stats.evicted_keys");
        beanNameList.add("metrics:name=redis.main.stats.keyspace_hits");
        beanNameList.add("metrics:name=redis.main.stats.keyspace_misses");
        beanNameList.add("metrics:name=redis.main.stats.pubsub_channels");
        beanNameList.add("metrics:name=redis.main.stats.pubsub_patterns");
        beanNameList.add("metrics:name=redis.main.stats.latest_fork_usec");
        beanNameList.add("metrics:name=redis.main.stats.migrate_cached_sockets");
        beanNameList.add("metrics:name=redis.main.replication.connected_slaves");
        beanNameList.add("metrics:name=redis.main.replication.master_repl_offset");
        beanNameList.add("metrics:name=redis.main.replication.repl_backlog_active");
        beanNameList.add("metrics:name=redis.main.replication.repl_backlog_size");
        beanNameList.add("metrics:name=redis.main.replication.repl_backlog_first_byte_offset");
        beanNameList.add("metrics:name=redis.main.replication.repl_backlog_histlen");
        beanNameList.add("metrics:name=redis.main.cpu.used_cpu_sys");
        beanNameList.add("metrics:name=redis.main.cpu.used_cpu_user");
        beanNameList.add("metrics:name=redis.main.cpu.used_cpu_sys_children");
        beanNameList.add("metrics:name=redis.main.cpu.used_cpu_user_children");
        beanNameList.add("metrics:name=redis.main.cluster.cluster_enabled");
        beanNameList.add("metrics:name=redis.main.keyspace.db0.keys");
        beanNameList.add("metrics:name=redis.main.keyspace.db0.expires");
        beanNameList.add("metrics:name=redis.main.keyspace.db0.avg_ttl");
        beanNameList.add("metrics:name=redis.main.expirable");
        beanNameList.add("metrics:name=gateleen.queues.enqueue");
        beanNameList.add("metrics:name=gateleen.queues.last.size");
        beanNameList.add("metrics:name=gateleen.queues.dequeue");
        for (String beanName : beanNameList) {
            ObjectName beanNameObject = new ObjectName(beanName);
            if (mbs.isRegistered(beanNameObject)) {
                mbs.unregisterMBean(beanNameObject);
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

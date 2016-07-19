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
import org.swisspush.gateleen.core.cors.CORSHandler;
import org.swisspush.gateleen.core.event.EventBusHandler;
import org.swisspush.gateleen.core.http.LocalHttpClient;
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
import org.swisspush.gateleen.logging.LogController;
import org.swisspush.gateleen.logging.LoggingResourceManager;
import org.swisspush.gateleen.monitoring.CustomRedisMonitor;
import org.swisspush.gateleen.monitoring.MonitoringHandler;
import org.swisspush.gateleen.monitoring.ResetMetricsController;
import org.swisspush.gateleen.qos.QoSHandler;
import org.swisspush.gateleen.queue.queuing.QueueBrowser;
import org.swisspush.gateleen.queue.queuing.QueueProcessor;
import org.swisspush.gateleen.routing.Router;
import org.swisspush.gateleen.runconfig.RunConfig;
import org.swisspush.gateleen.scheduler.SchedulerResourceManager;
import org.swisspush.gateleen.user.RoleProfileHandler;
import org.swisspush.gateleen.user.UserProfileHandler;
import redis.clients.jedis.Jedis;

import java.util.HashMap;
import java.util.Map;

/**
 * TestVerticle all Gateleen tests. <br />
 *
 * @author https://github.com/ljucam [Mario Ljuca]
 */
@RunWith(VertxUnitRunner.class)
public abstract class AbstractTest {
    public static final String SERVER_NAME = "gateleen";
    public static final String PREFIX = SERVER_NAME + ".";
    public static final String JMX_DOMAIN = "org.swisspush.gateleen";

    public static final String ROOT = "/playground";
    public static final String SERVER_ROOT = ROOT + "/server";
    public static final String RULES_ROOT = SERVER_ROOT + "/admin/v1/routing/rules";
    public static final String DELEGATE_ROOT = ROOT + "/server/delegate/v1/delegates/";
    public static final int MAIN_PORT = 3332;
    public static final int REDIS_PORT = 6379;

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

        jedis = new Jedis("localhost", REDIS_PORT);
        jedis.flushAll();

        final JsonObject info = new JsonObject();
        final LocalHttpClient selfClient = new LocalHttpClient(vertx);
        final HttpClient selfClientExpansionHandler = selfClient;
        props.putAll(RunConfig.buildRedisProps("localhost", REDIS_PORT));

        String redisHost = (String) props.get("redis.host");
        Integer redisPort = (Integer) props.get("redis.port");

        RunConfig.deployModules(vertx, AbstractTest.class, props, success -> {
            if(success){
                RedisClient redisClient = RedisClient.create(vertx, new RedisOptions().setHost(redisHost).setPort(redisPort));
                ResourceStorage storage = new EventBusResourceStorage(vertx.eventBus(), Address.storageAddress() + "-main");
                MonitoringHandler monitoringHandler = new MonitoringHandler(vertx, storage, PREFIX);
                EventBusHandler eventBusHandler = new EventBusHandler(vertx, SERVER_ROOT + "/push/v1/", SERVER_ROOT + "/push/v1/sock", "push-", "devices/([^/]+).*");
                eventBusHandler.setEventbusBridgePingInterval(RunConfig.EVENTBUS_BRIDGE_PING_INTERVAL);
                LoggingResourceManager loggingResourceManager = new LoggingResourceManager(vertx, storage, SERVER_ROOT + "/admin/v1/logging");
                UserProfileHandler userProfileHandler = new UserProfileHandler(vertx, storage, loggingResourceManager, RunConfig.buildUserProfileConfiguration());
                RoleProfileHandler roleProfileHandler = new RoleProfileHandler(vertx, storage, SERVER_ROOT + "/roles/v1/([^/]+)/profile");
                qosHandler = new QoSHandler(vertx, storage, SERVER_ROOT + "/admin/v1/qos", props, PREFIX);
                hookHandler = new HookHandler(vertx, selfClient, storage, loggingResourceManager, monitoringHandler, SERVER_ROOT + "/users/v1/%s/profile", ROOT + "/server/hooks/v1/");
                propertyHandler = new PropertyHandler(ROOT, props);
                schedulerResourceManager = new SchedulerResourceManager(vertx, redisClient, storage, monitoringHandler, SERVER_ROOT + "/admin/v1/schedulers");
                ResetMetricsController resetMetricsController = new ResetMetricsController(vertx);
                resetMetricsController.registerResetMetricsControlMBean(JMX_DOMAIN, PREFIX);
                LogController logController = new LogController();
                logController.registerLogConfiguratorMBean(JMX_DOMAIN);
                ZipExtractHandler zipExtractHandler = new ZipExtractHandler(selfClient);
                DelegateHandler delegateHandler = new DelegateHandler(vertx, selfClient, storage, monitoringHandler, DELEGATE_ROOT, props);

                // ------

                new QueueProcessor(vertx, selfClient, monitoringHandler);
                final QueueBrowser queueBrowser = new QueueBrowser(vertx, SERVER_ROOT + "/queuing", Address.redisquesAddress(), monitoringHandler);

                new CustomRedisMonitor(vertx, redisClient, "main", "rest-storage", 10).start();
                Router router = new Router(vertx, storage, props, loggingResourceManager, monitoringHandler, selfClient, SERVER_ROOT, SERVER_ROOT + "/admin/v1/routing/rules", SERVER_ROOT + "/users/v1/%s/profile", info,
                        (Handler<Void>) aVoid -> {
                            hookHandler.init();
                            delegateHandler.init();
                        }
                );

                System.setProperty("org.swisspush.gateleen.addcorsheaders", "true");

                RunConfig runConfig =
                        RunConfig.with()
                                .corsHandler(new CORSHandler())
                                .deltaHandler(new DeltaHandler(redisClient, selfClient))
                                .expansionHandler(new ExpansionHandler(vertx, storage, selfClientExpansionHandler, props, ROOT, RULES_ROOT))
                                .hookHandler(hookHandler)
                                .qosHandler(qosHandler)
                                .copyResourceHandler(new CopyResourceHandler(selfClient, SERVER_ROOT + "/v1/copy"))
                                .eventBusHandler(eventBusHandler)
                                .roleProfileHandler(roleProfileHandler)
                                .userProfileHandler(userProfileHandler)
                                .loggingResourceManager(loggingResourceManager)
                                .schedulerResourceManager(schedulerResourceManager)
                                .propertyHandler(propertyHandler)
                                .zipExtractHandler(zipExtractHandler)
                                .delegateHandler(delegateHandler)
                                .build(vertx, redisClient, AbstractTest.class, router, monitoringHandler, queueBrowser);
                Handler<RoutingContext> routingContextHandlerrNew = runConfig.buildRoutingContextHandler();
                selfClient.setRoutingContexttHandler(routingContextHandlerrNew);
                mainServer = vertx.createHttpServer();
                io.vertx.ext.web.Router vertxRouter = io.vertx.ext.web.Router.router(vertx);
                eventBusHandler.install(vertxRouter);
                vertxRouter.route().handler(routingContextHandlerrNew);
                mainServer.requestHandler(vertxRouter::accept);
                mainServer.listen(MAIN_PORT, event -> {
                    if(event.succeeded()){
                        async.complete();
                    } else {
                        context.fail("Server not listening on port " + MAIN_PORT);
                    }
                });
            }
        });
    }

    /**
     * Stopps redis after the test are finished.
     */
    @AfterClass
    public static void tearDownAfterClass(TestContext context) {
        Async async = context.async();
        jedis.close();
        mainServer.close();
        vertx.close(event -> async.complete());
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

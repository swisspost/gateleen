package org.swisspush.gateleen.integrationtest;

import org.swisspush.gateleen.logging.logging.LogController;
import org.swisspush.gateleen.core.control.ResetMetricsController;
import org.swisspush.gateleen.core.cors.CORSHandler;
import org.swisspush.gateleen.core.event.EventBusHandler;
import org.swisspush.gateleen.core.http.LocalHttpClient;
import org.swisspush.gateleen.logging.logging.LoggingResourceManager;
import org.swisspush.gateleen.core.monitoring.CustomRedisMonitor;
import org.swisspush.gateleen.core.monitoring.MonitoringHandler;
import org.swisspush.gateleen.core.resource.CopyResourceHandler;
import org.swisspush.gateleen.core.storage.EventBusResourceStorage;
import org.swisspush.gateleen.core.storage.ResourceStorage;
import org.swisspush.gateleen.core.util.Address;
import org.swisspush.gateleen.routing.routing.Router;
import org.swisspush.gateleen.runconfig.RunConfig;
import org.swisspush.gateleen.hook.HookHandler;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.redis.RedisClient;
import io.vertx.redis.RedisOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePropertySource;
import org.swisspush.gateleen.delta.delta.DeltaHandler;
import org.swisspush.gateleen.expansion.expansion.ExpansionHandler;
import org.swisspush.gateleen.qos.QoSHandler;
import org.swisspush.gateleen.queue.queuing.QueueBrowser;
import org.swisspush.gateleen.queue.queuing.QueueProcessor;
import org.swisspush.gateleen.scheduler.scheduler.SchedulerResourceManager;
import org.swisspush.gateleen.security.authorization.Authorizer;
import org.swisspush.gateleen.user.user.RoleProfileHandler;
import org.swisspush.gateleen.user.user.UserProfileHandler;
import org.swisspush.gateleen.validation.validation.ValidationHandler;
import org.swisspush.gateleen.validation.validation.ValidationResourceManager;

import java.io.IOException;
import java.util.Map;

/**
 * Verticle to start a standalone instance of gateleen
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class IntegrationTestVerticle extends AbstractVerticle {

    public static final String PREFIX = RunConfig.SERVER_NAME + ".";
    public static final String ROOT = "/gateleen";
    public static final String SERVER_ROOT = ROOT + "/server";
    public static final String RULES_ROOT = SERVER_ROOT + "/admin/v1/routing/rules";

    public static final String ROLE_PATTERN = "^z-gateleen[-_](.*)$";

    public static final String JMX_DOMAIN = "org.swisspush.gateleen";
    private HttpServer mainServer;

    private Authorizer authorizer;
    private Router router;
    private LoggingResourceManager loggingResourceManager;
    private ValidationResourceManager validationResourceManager;
    private SchedulerResourceManager schedulerResourceManager;
    private MonitoringHandler monitoringHandler;

    private EventBusHandler eventBusHandler;

    private int defaultRedisPort = 6379;
    private int mainPort = 7012;

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

    private Logger log = LoggerFactory.getLogger(IntegrationTestVerticle.class);

    @Override
    public void start() {
        final LocalHttpClient selfClient = new LocalHttpClient(vertx);
        final HttpClient selfClientExpansionHandler = selfClient;
        final JsonObject info = new JsonObject();
        final Map<String, Object> props = RunConfig.buildRedisProps("localhost", defaultRedisPort);

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

        redisClient = RedisClient.create(vertx, new RedisOptions().setHost(redisHost).setPort(redisPort));
        storage = new EventBusResourceStorage(vertx.eventBus(), Address.storageAddress() + "-main");
        corsHandler = new CORSHandler();
        deltaHandler = new DeltaHandler(redisClient, selfClient);
        expansionHandler = new ExpansionHandler(vertx, storage, selfClientExpansionHandler, props, ROOT, RULES_ROOT);
        copyResourceHandler = new CopyResourceHandler(selfClient, SERVER_ROOT + "/v1/copy");
        monitoringHandler = new MonitoringHandler(vertx, redisClient, PREFIX);
        qosHandler = new QoSHandler(vertx, storage, SERVER_ROOT + "/admin/v1/qos", props, PREFIX);
        eventBusHandler = new EventBusHandler(vertx, SERVER_ROOT + "/push/v1/", SERVER_ROOT + "/push/v1/sock", "push-", "devices/([^/]+).*");
        eventBusHandler.setEventbusBridgePingInterval(RunConfig.EVENTBUS_BRIDGE_PING_INTERVAL);
        loggingResourceManager = new LoggingResourceManager(vertx, storage, SERVER_ROOT + "/admin/v1/logging");
        userProfileHandler = new UserProfileHandler(vertx, storage, loggingResourceManager, RunConfig.buildUserProfileConfiguration());
        roleProfileHandler = new RoleProfileHandler(vertx, storage, SERVER_ROOT + "/roles/v1/([^/]*)/profile");
        hookHandler = new HookHandler(vertx, selfClient, storage, loggingResourceManager, monitoringHandler, SERVER_ROOT + "/users/v1/%s/profile", ROOT + "/server/hooks/v1/");
        authorizer = new Authorizer(vertx, storage, SERVER_ROOT + "/security/v1/", ROLE_PATTERN);
        validationResourceManager = new ValidationResourceManager(vertx, storage, SERVER_ROOT + "/admin/v1/validation");
        validationHandler = new ValidationHandler(validationResourceManager, storage, selfClient, ROOT + "/schemas/apis/");
        schedulerResourceManager = new SchedulerResourceManager(vertx, redisClient, storage, monitoringHandler, SERVER_ROOT + "/admin/v1/schedulers");

        new QueueProcessor(vertx, selfClient, monitoringHandler);
        final QueueBrowser queueBrowser = new QueueBrowser(vertx, SERVER_ROOT + "/queuing", Address.redisquesAddress(), monitoringHandler);

        LogController logController = new LogController();
        logController.registerLogConfiguratorMBean(JMX_DOMAIN);

        ResetMetricsController resetMetricsController = new ResetMetricsController(vertx);
        resetMetricsController.registerResetMetricsControlMBean(JMX_DOMAIN, PREFIX);

        RunConfig.deployModules(vertx, IntegrationTestVerticle.class, props, new Handler<Boolean>() {
            @Override
            public void handle(Boolean success) {
                if (success) {
                    new CustomRedisMonitor(vertx, redisClient, "main", "rest-storage", 10).start();
                    router = new Router(vertx, storage, props, loggingResourceManager, monitoringHandler, selfClient, SERVER_ROOT, SERVER_ROOT + "/admin/v1/routing/rules", SERVER_ROOT + "/users/v1/%s/profile", info,
                            (Handler<Void>) aVoid -> hookHandler.init());

                    RunConfig runConfig =
                            RunConfig.with()
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
                                    .schedulerResourceManager(schedulerResourceManager)
                                    .build(vertx, redisClient, IntegrationTestVerticle.class, router, monitoringHandler, queueBrowser);
                    Handler<RoutingContext> routingContextHandlerrNew = runConfig.buildRoutingContextHandler();
                    selfClient.setRoutingContexttHandler(routingContextHandlerrNew);
                    mainServer = vertx.createHttpServer();
                    io.vertx.ext.web.Router vertxRouter = io.vertx.ext.web.Router.router(vertx);
                    eventBusHandler.install(vertxRouter);
                    vertxRouter.route().handler(routingContextHandlerrNew);
                    mainServer.requestHandler(vertxRouter::accept);
                    mainServer.listen(mainPort);
                }
            }
        });
    }
}

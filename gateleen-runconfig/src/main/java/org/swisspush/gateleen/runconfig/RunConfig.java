package org.swisspush.gateleen.runconfig;

import io.vertx.core.DeploymentOptions;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.apache.commons.lang.ArrayUtils;
import org.apache.logging.log4j.core.config.Configurator;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.Instant;
import org.joda.time.format.ISODateTimeFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.cache.CacheHandler;
import org.swisspush.gateleen.core.configuration.ConfigurationResourceManager;
import org.swisspush.gateleen.core.cors.CORSHandler;
import org.swisspush.gateleen.core.event.EventBusHandler;
import org.swisspush.gateleen.core.property.PropertyHandler;
import org.swisspush.gateleen.core.redis.RedisProvider;
import org.swisspush.gateleen.core.resource.CopyResourceHandler;
import org.swisspush.gateleen.core.util.Address;
import org.swisspush.gateleen.core.util.ResponseStatusCodeLogUtil;
import org.swisspush.gateleen.core.util.StatusCode;
import org.swisspush.gateleen.delegate.DelegateHandler;
import org.swisspush.gateleen.delta.DeltaHandler;
import org.swisspush.gateleen.expansion.ExpansionHandler;
import org.swisspush.gateleen.expansion.ZipExtractHandler;
import org.swisspush.gateleen.hook.HookHandler;
import org.swisspush.gateleen.kafka.KafkaHandler;
import org.swisspush.gateleen.logging.LoggingResourceManager;
import org.swisspush.gateleen.merge.MergeHandler;
import org.swisspush.gateleen.monitoring.MonitoringHandler;
import org.swisspush.gateleen.packing.PackingHandler;
import org.swisspush.gateleen.qos.QoSHandler;
import org.swisspush.gateleen.queue.queuing.QueuingHandler;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.configuration.QueueCircuitBreakerConfigurationResourceManager;
import org.swisspush.gateleen.queue.queuing.splitter.QueueSplitter;
import org.swisspush.gateleen.routing.CustomHttpResponseHandler;
import org.swisspush.gateleen.routing.Router;
import org.swisspush.gateleen.scheduler.SchedulerResourceManager;
import org.swisspush.gateleen.security.authorization.Authorizer;
import org.swisspush.gateleen.security.content.ContentTypeConstraintHandler;
import org.swisspush.gateleen.user.RoleProfileHandler;
import org.swisspush.gateleen.user.UserProfileConfiguration;
import org.swisspush.gateleen.user.UserProfileHandler;
import org.swisspush.gateleen.validation.ValidationHandler;
import org.swisspush.gateleen.validation.ValidationResourceManager;
import org.swisspush.redisques.util.RedisquesConfiguration;
import org.swisspush.reststorage.util.ModuleConfiguration;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Helper class to configure verticles.
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class RunConfig {

    public static final String SERVER_TIMESTAMP_HEADER = "X-Server-Timestamp";
    public static final String SERVER_ARRIVAL_TIMESTAMP_HEADER = "X-Server-Arrival-Timestamp";
    public static final long EVENTBUS_BRIDGE_PING_INTERVAL = 10 * 60 * 1000L;
    public static final String ROOT = "/playground";
    public static final String SERVER_NAME = "gateleen";
    public static final String SERVER_ROOT = ROOT + "/server";
    public static final String ROLE_PATTERN = "^z-gateleen[-_](.*)$";
    public static final String[] PROFILE_PROPERTIES_PROVIDED_BY_THE_PROXY = new String[]{"username", "personalNumber", "fullname", "mail", "department", "lang"};
    public static final String[] PROFILE_PROPERTIES_PROVIDED_BY_THE_CLIENT = new String[]{"tour", "zip", "context", "contextIsDefault", "passkeyChanged", "volumeBeep", "torchMode", "spn"};

    private org.joda.time.format.DateTimeFormatter dfISO8601;
    private org.joda.time.format.DateTimeFormatter isoDateTimeParser;
    private Logger log;
    private Logger requestLog;
    private Random random;
    private final Class verticleClass;

    private final Vertx vertx;
    private final RedisProvider redisProvider;
    private final Router router;
    private final PackingHandler packingHandler;
    private final CacheHandler cacheHandler;
    private final CORSHandler corsHandler;
    private final ContentTypeConstraintHandler contentTypeConstraintHandler;
    private final SchedulerResourceManager schedulerResourceManager;
    private final ValidationResourceManager validationResourceManager;
    private final LoggingResourceManager loggingResourceManager;
    private final ConfigurationResourceManager configurationResourceManager;
    private final QueueCircuitBreakerConfigurationResourceManager queueCircuitBreakerConfigurationResourceManager;
    private final QueueSplitter queueSplitter;
    private final EventBusHandler eventBusHandler;
    private final ValidationHandler validationHandler;
    private final HookHandler hookHandler;
    private final UserProfileHandler userProfileHandler;
    private final RoleProfileHandler roleProfileHandler;
    private final ExpansionHandler expansionHandler;
    private final DeltaHandler deltaHandler;
    private final MonitoringHandler monitoringHandler;
    private final Authorizer authorizer;
    private final CopyResourceHandler copyResourceHandler;
    private final QoSHandler qosHandler;
    private final PropertyHandler propertyHandler;
    private final ZipExtractHandler zipExtractHandler;
    private final DelegateHandler delegateHandler;
    private final MergeHandler mergeHandler;
    private final KafkaHandler kafkaHandler;
    private final CustomHttpResponseHandler customHttpResponseHandler;

    public RunConfig(Vertx vertx, RedisProvider redisProvider, Class verticleClass, Router router, MonitoringHandler monitoringHandler,
                     CORSHandler corsHandler, SchedulerResourceManager schedulerResourceManager,
                     ValidationResourceManager validationResourceManager, LoggingResourceManager loggingResourceManager,
                     ConfigurationResourceManager configurationResourceManager,
                     QueueCircuitBreakerConfigurationResourceManager queueCircuitBreakerConfigurationResourceManager,
                     QueueSplitter queueSplitter, EventBusHandler eventBusHandler, ValidationHandler validationHandler, HookHandler hookHandler,
                     UserProfileHandler userProfileHandler, RoleProfileHandler roleProfileHandler, ExpansionHandler expansionHandler,
                     DeltaHandler deltaHandler, Authorizer authorizer, CopyResourceHandler copyResourceHandler,
                     QoSHandler qosHandler, PropertyHandler propertyHandler, ZipExtractHandler zipExtractHandler,
                     DelegateHandler delegateHandler, MergeHandler mergeHandler, KafkaHandler kafkaHandler,
                     CustomHttpResponseHandler customHttpResponseHandler, ContentTypeConstraintHandler contentTypeConstraintHandler,
                     CacheHandler cacheHandler, PackingHandler packingHandler) {
        this.vertx = vertx;
        this.redisProvider = redisProvider;
        this.verticleClass = verticleClass;
        this.router = router;
        this.monitoringHandler = monitoringHandler;
        this.corsHandler = corsHandler;
        this.schedulerResourceManager = schedulerResourceManager;
        this.validationResourceManager = validationResourceManager;
        this.loggingResourceManager = loggingResourceManager;
        this.configurationResourceManager = configurationResourceManager;
        this.queueCircuitBreakerConfigurationResourceManager = queueCircuitBreakerConfigurationResourceManager;
        this.queueSplitter = queueSplitter;
        this.eventBusHandler = eventBusHandler;
        this.validationHandler = validationHandler;
        this.hookHandler = hookHandler;
        this.userProfileHandler = userProfileHandler;
        this.roleProfileHandler = roleProfileHandler;
        this.expansionHandler = expansionHandler;
        this.deltaHandler = deltaHandler;
        this.authorizer = authorizer;
        this.copyResourceHandler = copyResourceHandler;
        this.qosHandler = qosHandler;
        this.propertyHandler = propertyHandler;
        this.zipExtractHandler = zipExtractHandler;
        this.delegateHandler = delegateHandler;
        this.mergeHandler = mergeHandler;
        this.kafkaHandler = kafkaHandler;
        this.customHttpResponseHandler = customHttpResponseHandler;
        this.contentTypeConstraintHandler = contentTypeConstraintHandler;
        this.cacheHandler = cacheHandler;
        this.packingHandler = packingHandler;
        init();
    }

    private RunConfig(RunConfigBuilder builder) {
        this(builder.vertx,
                builder.redisProvider,
                builder.verticleClass,
                builder.router,
                builder.monitoringHandler,
                builder.corsHandler,
                builder.schedulerResourceManager,
                builder.validationResourceManager,
                builder.loggingResourceManager,
                builder.configurationResourceManager,
                builder.queueCircuitBreakerConfigurationResourceManager,
                builder.queueSplitter,
                builder.eventBusHandler,
                builder.validationHandler,
                builder.hookHandler,
                builder.userProfileHandler,
                builder.roleProfileHandler,
                builder.expansionHandler,
                builder.deltaHandler,
                builder.authorizer,
                builder.copyResourceHandler,
                builder.qosHandler,
                builder.propertyHandler,
                builder.zipExtractHandler,
                builder.delegateHandler,
                builder.mergeHandler,
                builder.kafkaHandler,
                builder.customHttpResponseHandler,
                builder.contentTypeConstraintHandler,
                builder.cacheHandler,
                builder.packingHandler
        );
    }

    private void init() {
        random = new Random(System.currentTimeMillis());
        log = LoggerFactory.getLogger(verticleClass);
        requestLog = LoggerFactory.getLogger("Request");
        dfISO8601 = ISODateTimeFormat.dateTime().withZone(DateTimeZone.forID("Europe/Zurich"));
        isoDateTimeParser = ISODateTimeFormat.dateTimeParser();
        String conf = System.getProperty("log4jConfigFile");
        if (conf == null) {
            // use default
            conf = "classpath:" + SERVER_NAME + "/config/logging/log4j2.xml";
        }

        log.info("{} starting with log configuration {}", SERVER_NAME, conf);
        Configurator.initialize("", conf);
    }

    public static RunConfigBuilder with() {
        return new RunConfigBuilder();
    }

    /**
     * RunConfigBuilder class for simplyfied configuration of the run configuration.
     */
    public static class RunConfigBuilder {
        private Vertx vertx;
        private RedisProvider redisProvider;
        private Class verticleClass;
        private Router router;
        private MonitoringHandler monitoringHandler;
        private CORSHandler corsHandler;
        private SchedulerResourceManager schedulerResourceManager;
        private ValidationResourceManager validationResourceManager;
        private LoggingResourceManager loggingResourceManager;
        private ConfigurationResourceManager configurationResourceManager;
        private QueueCircuitBreakerConfigurationResourceManager queueCircuitBreakerConfigurationResourceManager;
        private QueueSplitter queueSplitter;
        private EventBusHandler eventBusHandler;
        private KafkaHandler kafkaHandler;
        private CustomHttpResponseHandler customHttpResponseHandler;
        private ContentTypeConstraintHandler contentTypeConstraintHandler;
        private ValidationHandler validationHandler;
        private HookHandler hookHandler;
        private UserProfileHandler userProfileHandler;
        private RoleProfileHandler roleProfileHandler;
        private ExpansionHandler expansionHandler;
        private DeltaHandler deltaHandler;
        private Authorizer authorizer;
        private CopyResourceHandler copyResourceHandler;
        private QoSHandler qosHandler;
        public PropertyHandler propertyHandler;
        private ZipExtractHandler zipExtractHandler;
        private DelegateHandler delegateHandler;
        private MergeHandler mergeHandler;
        private CacheHandler cacheHandler;
        private PackingHandler packingHandler;

        public RunConfigBuilder() {
        }

        public RunConfigBuilder corsHandler(CORSHandler corsHandler) {
            this.corsHandler = corsHandler;
            return this;
        }

        public RunConfigBuilder schedulerResourceManager(SchedulerResourceManager schedulerResourceManager) {
            this.schedulerResourceManager = schedulerResourceManager;
            return this;
        }

        public RunConfigBuilder validationResourceManager(ValidationResourceManager validationResourceManager) {
            this.validationResourceManager = validationResourceManager;
            return this;
        }

        public RunConfigBuilder loggingResourceManager(LoggingResourceManager loggingResourceManager) {
            this.loggingResourceManager = loggingResourceManager;
            return this;
        }

        public RunConfigBuilder configurationResourceManager(ConfigurationResourceManager configurationResourceManager) {
            this.configurationResourceManager = configurationResourceManager;
            return this;
        }

        public RunConfigBuilder queueCircuitBreakerConfigurationResourceManager(QueueCircuitBreakerConfigurationResourceManager queueCircuitBreakerConfigurationResourceManager) {
            this.queueCircuitBreakerConfigurationResourceManager = queueCircuitBreakerConfigurationResourceManager;
            return this;
        }

        public RunConfigBuilder queueSplitter(QueueSplitter queueSplitter) {
            this.queueSplitter = queueSplitter;
            return this;
        }

        public RunConfigBuilder eventBusHandler(EventBusHandler eventBusHandler) {
            this.eventBusHandler = eventBusHandler;
            return this;
        }

        public RunConfigBuilder kafkaHandler(KafkaHandler kafkaHandler) {
            this.kafkaHandler = kafkaHandler;
            return this;
        }

        public RunConfigBuilder customHttpResponseHandler(CustomHttpResponseHandler customHttpResponseHandler) {
            this.customHttpResponseHandler = customHttpResponseHandler;
            return this;
        }

        public RunConfigBuilder contentTypeConstraintHandler(ContentTypeConstraintHandler contentTypeConstraintHandler) {
            this.contentTypeConstraintHandler = contentTypeConstraintHandler;
            return this;
        }

        public RunConfigBuilder validationHandler(ValidationHandler validationHandler) {
            this.validationHandler = validationHandler;
            return this;
        }

        public RunConfigBuilder hookHandler(HookHandler hookHandler) {
            this.hookHandler = hookHandler;
            return this;
        }

        public RunConfigBuilder userProfileHandler(UserProfileHandler userProfileHandler) {
            this.userProfileHandler = userProfileHandler;
            return this;
        }

        public RunConfigBuilder roleProfileHandler(RoleProfileHandler roleProfileHandler) {
            this.roleProfileHandler = roleProfileHandler;
            return this;
        }

        public RunConfigBuilder expansionHandler(ExpansionHandler expansionHandler) {
            this.expansionHandler = expansionHandler;
            return this;
        }

        public RunConfigBuilder deltaHandler(DeltaHandler deltaHandler) {
            this.deltaHandler = deltaHandler;
            return this;
        }

        public RunConfigBuilder authorizer(Authorizer authorizer) {
            this.authorizer = authorizer;
            return this;
        }

        public RunConfigBuilder copyResourceHandler(CopyResourceHandler copyResourceHandler) {
            this.copyResourceHandler = copyResourceHandler;
            return this;
        }

        public RunConfigBuilder qosHandler(QoSHandler qosHandler) {
            this.qosHandler = qosHandler;
            return this;
        }

        public RunConfigBuilder propertyHandler(PropertyHandler propertyHandler) {
            this.propertyHandler = propertyHandler;
            return this;
        }

        public RunConfigBuilder zipExtractHandler(ZipExtractHandler zipExtractHandler) {
            this.zipExtractHandler = zipExtractHandler;
            return this;
        }

        public RunConfigBuilder delegateHandler(DelegateHandler delegateHandler) {
            this.delegateHandler = delegateHandler;
            return this;
        }

        public RunConfigBuilder mergeHandler(MergeHandler mergeHandler) {
            this.mergeHandler = mergeHandler;
            return this;
        }

        public RunConfigBuilder cacheHandler(CacheHandler cacheHandler) {
            this.cacheHandler = cacheHandler;
            return this;
        }

        public RunConfigBuilder packingHandler(PackingHandler packingHandler) {
            this.packingHandler = packingHandler;
            return this;
        }

        public RunConfig build(Vertx vertx, RedisProvider redisProvider, Class verticleClass, Router router, MonitoringHandler monitoringHandler) {
            this.vertx = vertx;
            this.redisProvider = redisProvider;
            this.verticleClass = verticleClass;
            this.router = router;
            this.monitoringHandler = monitoringHandler;
            return new RunConfig(this);
        }
    }

    /**
     * Builds redis properties configuration.
     */
    public static Map<String, Object> buildRedisProps(String redisHost, int redisPort) {
        return buildRedisProps(redisHost, redisPort, false);
    }

    /**
     * Builds redis properties configuration.
     */
    public static Map<String, Object> buildRedisProps(String redisHost, int redisPort, boolean redisEnableTls) {
        final Map<String, Object> props = new HashMap<>();
        props.put("redis.host", redisHost);
        props.put("redis.port", redisPort);
        props.put("redis.enableTls", redisEnableTls);
        props.put("redis.encoding", "UTF-8");
        return props;
    }

    /**
     * Builds a standard mod redis configuration.
     */
    public static JsonObject buildModRedisConfig(String redisHost, int redisPort) {
        return buildModRedisConfig(redisHost, redisPort, false);
    }

    /**
     * Builds a standard mod redis configuration.
     */
    public static JsonObject buildModRedisConfig(String redisHost, int redisPort, boolean redisEnableTls) {
        JsonObject config = new JsonObject();
        config.put("host", redisHost);
        config.put("port", redisPort);
        config.put("enableTls", redisEnableTls);
        config.put("encoding", "UTF-8");
        return config;
    }

    /**
     * Builds a standard metrics configuration.
     */
    public static JsonObject buildMetricsConfig() {
        JsonObject metricsConfig = new JsonObject();
        metricsConfig.put("address", Address.monitoringAddress());
        return metricsConfig;
    }

    /**
     * Builds a standard redisques configuration.
     */
    public static JsonObject buildRedisquesConfig() {
        return RedisquesConfiguration.with()
                .address(Address.redisquesAddress())
                .processorAddress(Address.queueProcessorAddress())
                .httpRequestHandlerEnabled(true)
                .enableQueueNameDecoding(false)
                .httpRequestHandlerPort(7015)
                .redisReconnectAttempts(-1)
                .redisPoolRecycleTimeoutMs(-1)
                .build()
                .asJsonObject();
    }

    /**
     * Builds a standard storage configuration.
     */
    public static JsonObject buildStorageConfig() {
        return new ModuleConfiguration()
                .storageType(ModuleConfiguration.StorageType.redis)
                .storageAddress(Address.storageAddress() + "-main")
                .redisReconnectAttempts(-1)
                .redisPoolRecycleTimeoutMs(-1)
                .asJsonObject();
    }

    /**
     * Builds a standard UserProfileConfiguration.
     */
    public static UserProfileConfiguration buildUserProfileConfiguration() {
        String[] allAllowedProfileProperties = (String[]) ArrayUtils.addAll(PROFILE_PROPERTIES_PROVIDED_BY_THE_PROXY,
                PROFILE_PROPERTIES_PROVIDED_BY_THE_CLIENT);

        final String matchEverythingExceptUnknown = "(?!^unknown$).*";
        final String fallbackValue = "unknown";
        final UserProfileConfiguration.ProfileProperty departmentConfig = UserProfileConfiguration.ProfileProperty.with("x-rp-department", "department").setUpdateStrategy(UserProfileConfiguration.UpdateStrategy.UPDATE_ALWAYS).setValueToUseIfNoOtherValidValue(fallbackValue).validationRegex(matchEverythingExceptUnknown).setOptional(false).build();
        final UserProfileConfiguration.ProfileProperty mailConfig = UserProfileConfiguration.ProfileProperty.with("x-rp-mail", "mail").setUpdateStrategy(UserProfileConfiguration.UpdateStrategy.UPDATE_ALWAYS).setValueToUseIfNoOtherValidValue(fallbackValue).validationRegex(matchEverythingExceptUnknown).setOptional(false).build();
        final UserProfileConfiguration.ProfileProperty employeeIdConfig = UserProfileConfiguration.ProfileProperty.with("x-rp-employeeid", "personalNumber")
                .validationRegex("\\d*{8}").setUpdateStrategy(UserProfileConfiguration.UpdateStrategy.UPDATE_ALWAYS).setValueToUseIfNoOtherValidValue(fallbackValue).setOptional(false).build();
        final UserProfileConfiguration.ProfileProperty usernameConfig = UserProfileConfiguration.ProfileProperty.with("x-rp-usr", "username").setUpdateStrategy(UserProfileConfiguration.UpdateStrategy.UPDATE_ONLY_IF_PROFILE_VALUE_IS_INVALID).setOptional(false).build();
        final UserProfileConfiguration.ProfileProperty fullNameConfig = UserProfileConfiguration.ProfileProperty.with("x-rp-displayname", "fullName").setUpdateStrategy(UserProfileConfiguration.UpdateStrategy.UPDATE_ALWAYS).setOptional(false).build();

        return UserProfileConfiguration.create()
                .userProfileUriPattern(SERVER_ROOT + "/users/v1/([^/]+)/profile")
                .roleProfilesRoot(SERVER_ROOT + "/roles/v1/")
                .rolePattern(ROLE_PATTERN)
                .addAllowedProfileProperties(allAllowedProfileProperties)
                .addProfileProperty(departmentConfig)
                .addProfileProperty(mailConfig)
                .addProfileProperty(employeeIdConfig)
                .addProfileProperty(usernameConfig)
                .addProfileProperty(fullNameConfig)
                .build();
    }

    /**
     * Deploys the following modules in this order:
     * <ul>
     * <li>org.swisspush.redisques.RedisQues
     * <li>org.swisspush.reststorage.RestStorageMod
     * <li>org.swisspush.metrics.MetricsModule
     * </ul><p>
     * The handler is called with Boolean.TRUE when all modules have been deployed successfully. When any of the modules
     * could not be deployed correctly, the handler returns Boolean.FALSE.
     *
     * @param vertx
     * @param verticleClass
     * @param props
     * @param handler
     */
    public static void deployModules(final Vertx vertx, Class verticleClass, Map<String, Object> props, final Handler<Boolean> handler) {
        final Logger log = LoggerFactory.getLogger(verticleClass);
        String redisHost = (String) props.get("redis.host");
        Integer redisPort = (Integer) props.get("redis.port");
        boolean redisEnableTls = props.get("redis.enableTls") != null ? (Boolean) props.get("redis.enableTls") : false;
        log.info("deploying redis module with host: {}, port: {}, TLS: {}", redisHost, redisPort, redisEnableTls);

        // redisques module
        vertx.deployVerticle("org.swisspush.redisques.RedisQues", new DeploymentOptions().setConfig(RunConfig.buildRedisquesConfig()).setInstances(4), event -> {
            if (event.failed()) {
                log.error("Could not load redisques module", event.cause());
                handler.handle(false);
                return;
            }
            // rest storage module
            vertx.deployVerticle("org.swisspush.reststorage.RestStorageMod", new DeploymentOptions().setConfig(RunConfig.buildStorageConfig()).setInstances(4), event1 -> {
                if (event1.failed()) {
                    log.error("Could not load rest storage redis module", event1.cause());
                    handler.handle(false);
                    return;
                }

                // metrics module
                vertx.deployVerticle("org.swisspush.metrics.MetricsModule", new DeploymentOptions().setConfig(RunConfig.buildMetricsConfig()), event2 -> {
                    if (event2.failed()) {
                        log.error("Could not load metrics module", event2.cause());
                        handler.handle(false);
                        return;
                    }
                    handler.handle(true);
                });
            });
        });
    }

    /**
     * Builds a handler for {@link RoutingContext}s with a "default" behaviour.
     */
    public Handler<RoutingContext> buildRoutingContextHandler() {

        // add refreshables
        if (propertyHandler != null) {
            if (router != null) {
                propertyHandler.addRefreshable(router);
            }

            if (schedulerResourceManager != null) {
                propertyHandler.addRefreshable(schedulerResourceManager);
            }
        }

        return new Handler<>() {

            @Override
            public void handle(final RoutingContext ctx) {
                HttpServerRequest request = ctx.request();
                if (!request.headers().contains("x-rp-unique_id")) {
                    request.headers().set("x-rp-unique_id", new UUID(random.nextLong(), random.nextLong()).toString().replace("-", ""));
                }
                request.exceptionHandler(exception -> LoggerFactory.getLogger(verticleClass).trace("Exception in client", exception));
                logRequest(request);

                if (qosHandler != null && qosHandler.handle(request)) {
                    return;
                }

                if (corsHandler != null) {
                    corsHandler.handle(request);
                    if (corsHandler.isOptionsRequest(request)) {
                        return;
                    }
                }

                if (contentTypeConstraintHandler != null && contentTypeConstraintHandler.handle(request)) {
                    return;
                }

                if (authorizer != null) {
                    authorizer.authorize(request).onComplete(event -> {
                        if (event.succeeded() && event.result()) {
                            handleRequest(ctx);
                        } else if (event.failed()) {
                            ResponseStatusCodeLogUtil.info(request, StatusCode.INTERNAL_SERVER_ERROR, RunConfig.class);
                            request.response().setStatusCode(StatusCode.INTERNAL_SERVER_ERROR.getStatusCode());
                            request.response().setStatusMessage(StatusCode.INTERNAL_SERVER_ERROR.getStatusMessage());
                            request.response().end(event.cause().getMessage());
                        }
                    });
                } else {
                    handleRequest(ctx);
                }
            }

            private void handleRequest(final RoutingContext ctx) {
                HttpServerRequest request = ctx.request();
                if (request.path().equals(SERVER_ROOT + "/cleanup")) {
                    QueuingHandler.cleanup(vertx);
                    request.response().end();
                    return;
                }
                if (QueuingHandler.isQueued(request)) {
                    setISO8601Timestamps(request);
                    request.bodyHandler(new QueuingHandler(
                            vertx,
                            redisProvider,
                            request,
                            monitoringHandler,
                            queueSplitter));
                } else {
                    if (packingHandler != null && packingHandler.isPacked(request)) {
                        packingHandler.handle(request);
                        return;
                    }
                    if (cacheHandler != null && cacheHandler.handle(request)) {
                        return;
                    }
                    if (copyResourceHandler != null && copyResourceHandler.handle(request)) {
                        return;
                    }
                    if (hookHandler != null && hookHandler.handle(ctx)) {
                        return;
                    }
                    if (eventBusHandler != null && eventBusHandler.handle(request)) {
                        return;
                    }
                    if (kafkaHandler != null && kafkaHandler.handle(request)) {
                        return;
                    }
                    if (validationHandler != null && validationHandler.isToValidate(request)) {
                        validationHandler.handle(request);
                        return;
                    }
                    if (loggingResourceManager != null && loggingResourceManager.handleLoggingResource(request)) {
                        return;
                    }
                    if (configurationResourceManager != null && configurationResourceManager.handleConfigurationResource(request)) {
                        return;
                    }
                    if (validationResourceManager != null && validationResourceManager.handleValidationResource(request)) {
                        return;
                    }
                    if (schedulerResourceManager != null && schedulerResourceManager.handleSchedulerResource(request)) {
                        return;
                    }
                    if (queueCircuitBreakerConfigurationResourceManager != null &&
                            queueCircuitBreakerConfigurationResourceManager.handleConfigurationResource(request)) {
                        return;
                    }
                    if (propertyHandler != null && propertyHandler.handle(request)) {
                        return;
                    }
                    if (zipExtractHandler != null && zipExtractHandler.handle(request)) {
                        return;
                    }
                    if (delegateHandler != null && delegateHandler.handle(request)) {
                        return;
                    }
                    if (customHttpResponseHandler != null && customHttpResponseHandler.handle(request)) {
                        return;
                    }
                    if (userProfileHandler != null && userProfileHandler.isUserProfileRequest(request)) {
                        userProfileHandler.handle(request);
                    } else if (roleProfileHandler != null && roleProfileHandler.isRoleProfileRequest(request)) {
                        roleProfileHandler.handle(request);
                    } else if (expansionHandler != null && expansionHandler.isZipRequest(request)) {
                        expansionHandler.handleZipRecursion(request);
                    } else if (expansionHandler != null && expansionHandler.isExpansionRequest(request)) {
                        expansionHandler.handleExpansionRecursion(request);
                    } else if (deltaHandler != null && deltaHandler.isDeltaRequest(request)) {
                        setISO8601Timestamps(request);
                        deltaHandler.handle(request, router);
                    } else if (mergeHandler != null && mergeHandler.handle(request)) {
                        return;
                    } else {
                        setISO8601Timestamps(request);
                        router.route(request);
                    }
                }
            }
        };
    }

    public static Map<String, Object> subMap(Map<String, Object> map, String prefix) {
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                Object value = entry.getValue();
                try {
                    value = Integer.valueOf(value.toString());
                } catch (NumberFormatException e) {
                    // ignore
                }
                result.put(entry.getKey(), value);
            }
        }
        return result;
    }

    private void logRequest(HttpServerRequest request) {
        monitoringHandler.updateIncomingRequests(request);
        StringBuilder sb = new StringBuilder();
        String uid = request.headers().get("x-rp-unique_id");
        if (uid != null) {
            sb.append(uid);
            sb.append(" ");
        }

        sb.append(request.method());
        sb.append(" ");
        sb.append(request.uri());
        sb.append(" ");

        String user = request.headers().get("x-rp-usr");
        if (user != null) {
            sb.append("u=");
            sb.append(user);
            sb.append(" ");
        }

        String device = request.headers().get("x-rp-deviceid");
        if (device != null) {
            sb.append("d=");
            sb.append(device);
            sb.append(" ");
        }

        String app = request.headers().get("x-appid");
        if (app != null) {
            sb.append("a=");
            sb.append(app);
            sb.append(" ");
        }

        String queue = request.headers().get("x-queue");
        if (queue != null) {
            sb.append("q=");
            sb.append(queue);
            sb.append(" ");
        }

        String behalf = request.headers().get("x-on-behalf-of");
        if (behalf != null) {
            sb.append("b=");
            sb.append(behalf);
            sb.append(" ");
        }

        if (request.uri().endsWith("cleanup") || request.uri().contains("/jmx/")) {
            requestLog.debug(sb.toString());
        } else {
            requestLog.info(sb.toString());
        }
    }

    /**
     * <li>
     * <ul>
     * Set the server arrived timestamp if not already set, which is the timestamp when the request reached the server
     * </ul>
     * <ul>
     * Set the server timestamp, which is the timestamp, when the request left the server
     * </ul>
     * </li>
     *
     * @param request
     */
    private void setISO8601Timestamps(HttpServerRequest request) {
        String nowAsISO = dfISO8601.print(Instant.now());
        if (!request.headers().contains(SERVER_ARRIVAL_TIMESTAMP_HEADER)) {
            request.headers().set(SERVER_ARRIVAL_TIMESTAMP_HEADER, nowAsISO);
        }
        request.headers().set(SERVER_TIMESTAMP_HEADER, nowAsISO);
        localizeTimestamp(request, "x-submit-timestamp");
        localizeTimestamp(request, "x-client-timestamp");
    }

    /**
     * Transform a timestamp header to local time timestamp header it is UTC. If the header is absent or not parsable, do nothing.
     *
     * @param request The request containing the header.
     * @param header  The header name.
     */
    private void localizeTimestamp(HttpServerRequest request, String header) {
        String timestamp = request.headers().get(header);
        if (timestamp != null && timestamp.toUpperCase().endsWith("Z")) {
            try {
                DateTime dt = isoDateTimeParser.parseDateTime(timestamp);
                request.headers().set(header, dfISO8601.print(dt));
            } catch (IllegalArgumentException e) {
                log.warn("Could not parse {} : {}", header, timestamp);
            }
        }
    }
}

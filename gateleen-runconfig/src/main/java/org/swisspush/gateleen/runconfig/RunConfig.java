package org.swisspush.gateleen.runconfig;

import org.swisspush.gateleen.core.cors.CORSHandler;
import org.swisspush.gateleen.core.event.EventBusHandler;
import org.swisspush.gateleen.logging.logging.LoggingResourceManager;
import org.swisspush.gateleen.core.monitoring.MonitoringHandler;
import org.swisspush.gateleen.core.resource.CopyResourceHandler;
import org.swisspush.gateleen.core.util.Address;
import org.swisspush.gateleen.delta.delta.DeltaHandler;
import org.swisspush.gateleen.expansion.expansion.ExpansionHandler;
import org.swisspush.gateleen.hook.HookHandler;
import org.swisspush.gateleen.packing.packing.PackingHandler;
import org.swisspush.gateleen.queue.queuing.QueueBrowser;
import org.swisspush.gateleen.queue.queuing.QueuingHandler;
import org.swisspush.gateleen.routing.routing.Router;
import org.swisspush.gateleen.security.authorization.Authorizer;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.redis.RedisClient;
import org.apache.commons.lang.ArrayUtils;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.Instant;
import org.joda.time.format.ISODateTimeFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Log4jConfigurer;
import org.swisspush.gateleen.qos.QoSHandler;
import org.swisspush.gateleen.scheduler.scheduler.SchedulerResourceManager;
import org.swisspush.gateleen.user.user.RoleProfileHandler;
import org.swisspush.gateleen.user.user.UserProfileConfiguration;
import org.swisspush.gateleen.user.user.UserProfileHandler;
import org.swisspush.gateleen.validation.validation.ValidationHandler;
import org.swisspush.gateleen.validation.validation.ValidationResourceManager;

import java.io.FileNotFoundException;
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
    public static final String ROOT = "/test";
    public static final String SERVER_NAME = "gateleen";
    public static final String SERVER_ROOT = ROOT + "/server";
    public static final String ROLE_PATTERN = "^z-gateleen[-_](.*)$";
    public static final String[] PROFILE_PROPERTIES_PROVIDED_BY_THE_PROXY = new String[] { "username", "personalNumber", "fullname", "mail", "department", "lang" };
    public static final String[] PROFILE_PROPERTIES_PROVIDED_BY_THE_CLIENT = new String[] { "tour", "zip", "context", "contextIsDefault", "passkeyChanged", "volumeBeep", "torchMode", "spn" };

    private org.joda.time.format.DateTimeFormatter dfISO8601;
    private org.joda.time.format.DateTimeFormatter isoDateTimeParser;
    private Logger log;
    private Logger requestLog;
    private Random random;
    private final Class verticleClass;

    private Vertx vertx;
    private RedisClient redisClient;
    private Router router;
    private CORSHandler corsHandler;
    private SchedulerResourceManager schedulerResourceManager;
    private ValidationResourceManager validationResourceManager;
    private LoggingResourceManager loggingResourceManager;
    private EventBusHandler eventBusHandler;
    private ValidationHandler validationHandler;
    private HookHandler hookHandler;
    private UserProfileHandler userProfileHandler;
    private RoleProfileHandler roleProfileHandler;
    private ExpansionHandler expansionHandler;
    private DeltaHandler deltaHandler;
    private MonitoringHandler monitoringHandler;
    private QueueBrowser queueBrowser;
    private Authorizer authorizer;
    private CopyResourceHandler copyResourceHandler;
    private QoSHandler qosHandler;

    public RunConfig(Vertx vertx, RedisClient redisClient, Class verticleClass, Router router, MonitoringHandler monitoringHandler, QueueBrowser queueBrowser, CORSHandler corsHandler, SchedulerResourceManager schedulerResourceManager,
                     ValidationResourceManager validationResourceManager, LoggingResourceManager loggingResourceManager,
                     EventBusHandler eventBusHandler, ValidationHandler validationHandler, HookHandler hookHandler,
                     UserProfileHandler userProfileHandler, RoleProfileHandler roleProfileHandler, ExpansionHandler expansionHandler,
                     DeltaHandler deltaHandler, Authorizer authorizer, CopyResourceHandler copyResourceHandler, QoSHandler qosHandler){
        this.vertx = vertx;
        this.redisClient = redisClient;
        this.verticleClass = verticleClass;
        this.router = router;
        this.monitoringHandler = monitoringHandler;
        this.queueBrowser = queueBrowser;
        this.corsHandler = corsHandler;
        this.schedulerResourceManager = schedulerResourceManager;
        this.validationResourceManager = validationResourceManager;
        this.loggingResourceManager = loggingResourceManager;
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
        init();
    }

    private RunConfig(RunConfigBuilder builder){
        this(builder.vertx,
                builder.redisClient,
                builder.verticleClass,
                builder.router,
                builder.monitoringHandler,
                builder.queueBrowser,
                builder.corsHandler,
                builder.schedulerResourceManager,
                builder.validationResourceManager,
                builder.loggingResourceManager,
                builder.eventBusHandler,
                builder.validationHandler,
                builder.hookHandler,
                builder.userProfileHandler,
                builder.roleProfileHandler,
                builder.expansionHandler,
                builder.deltaHandler,
                builder.authorizer,
                builder.copyResourceHandler,
                builder.qosHandler
        );
    }

    private void init(){
        random = new Random(System.currentTimeMillis());
        log = LoggerFactory.getLogger(verticleClass);
        requestLog = LoggerFactory.getLogger("Request");
        dfISO8601 = ISODateTimeFormat.dateTime().withZone(DateTimeZone.forID("Europe/Zurich"));
        isoDateTimeParser = ISODateTimeFormat.dateTimeParser();

        String conf = "classpath:" + SERVER_NAME + "/config/logging/log4j.xml";
        log.info(SERVER_NAME + " starting with log configuration " + conf);
        try {
            Log4jConfigurer.initLogging(conf);
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    public static RunConfigBuilder with(){
        return new RunConfigBuilder();
    }

    /**
     * RunConfigBuilder class for simplyfied configuration of the run configuration.
     */
    public static class RunConfigBuilder {
        private Vertx vertx;
        private RedisClient redisClient;
        private Class verticleClass;
        private Router router;
        private MonitoringHandler monitoringHandler;
        private QueueBrowser queueBrowser;
        private CORSHandler corsHandler;
        private SchedulerResourceManager schedulerResourceManager;
        private ValidationResourceManager validationResourceManager;
        private LoggingResourceManager loggingResourceManager;
        private EventBusHandler eventBusHandler;
        private ValidationHandler validationHandler;
        private HookHandler hookHandler;
        private UserProfileHandler userProfileHandler;
        private RoleProfileHandler roleProfileHandler;
        private ExpansionHandler expansionHandler;
        private DeltaHandler deltaHandler;
        private Authorizer authorizer;
        private CopyResourceHandler copyResourceHandler;
        private QoSHandler qosHandler;

        public RunConfigBuilder(){}

        public RunConfigBuilder corsHandler(CORSHandler corsHandler){
            this.corsHandler = corsHandler;
            return this;
        }

        public RunConfigBuilder schedulerResourceManager(SchedulerResourceManager schedulerResourceManager){
            this.schedulerResourceManager = schedulerResourceManager;
            return this;
        }

        public RunConfigBuilder validationResourceManager(ValidationResourceManager validationResourceManager){
            this.validationResourceManager = validationResourceManager;
            return this;
        }

        public RunConfigBuilder loggingResourceManager(LoggingResourceManager loggingResourceManager){
            this.loggingResourceManager = loggingResourceManager;
            return this;
        }

        public RunConfigBuilder eventBusHandler(EventBusHandler eventBusHandler){
            this.eventBusHandler = eventBusHandler;
            return this;
        }

        public RunConfigBuilder validationHandler(ValidationHandler validationHandler){
            this.validationHandler = validationHandler;
            return this;
        }

        public RunConfigBuilder hookHandler(HookHandler hookHandler){
            this.hookHandler = hookHandler;
            return this;
        }

        public RunConfigBuilder userProfileHandler(UserProfileHandler userProfileHandler){
            this.userProfileHandler = userProfileHandler;
            return this;
        }

        public RunConfigBuilder roleProfileHandler(RoleProfileHandler roleProfileHandler){
            this.roleProfileHandler = roleProfileHandler;
            return this;
        }

        public RunConfigBuilder expansionHandler(ExpansionHandler expansionHandler){
            this.expansionHandler = expansionHandler;
            return this;
        }

        public RunConfigBuilder deltaHandler(DeltaHandler deltaHandler){
            this.deltaHandler = deltaHandler;
            return this;
        }

        public RunConfigBuilder authorizer(Authorizer authorizer){
            this.authorizer = authorizer;
            return this;
        }

        public RunConfigBuilder copyResourceHandler(CopyResourceHandler copyResourceHandler){
            this.copyResourceHandler = copyResourceHandler;
            return this;
        }

        public RunConfigBuilder qosHandler(QoSHandler qosHandler){
            this.qosHandler = qosHandler;
            return this;
        }

        public RunConfig build(Vertx vertx, RedisClient redisClient, Class verticleClass, Router router, MonitoringHandler monitoringHandler, QueueBrowser queueBrowser){
            this.vertx = vertx;
            this.redisClient = redisClient;
            this.verticleClass = verticleClass;
            this.router = router;
            this.monitoringHandler = monitoringHandler;
            this.queueBrowser = queueBrowser;
            return new RunConfig(this);
        }
    }

    /**
     * Builds redis properties configuration.
     */
    public static Map<String, Object> buildRedisProps(String redisHost, int redisPort){
        final Map<String, Object> props = new HashMap<>();
        props.put("redis.host", redisHost);
        props.put("redis.port", redisPort);
        props.put("redis.encoding", "UTF-8");
        return props;
    }

    /**
     * Builds a standard mod redis configuration.
     */
    public static JsonObject buildModRedisConfig(String redisHost, int redisPort){
        JsonObject config = new JsonObject();
        config.put("host", redisHost);
        config.put("port", redisPort);
        config.put("encoding", "UTF-8");
        return config;
    }

    /**
     * Builds a standard metrics configuration.
     */
    public static JsonObject buildMetricsConfig(){
        JsonObject metricsConfig = new JsonObject();
        metricsConfig.put("address", Address.monitoringAddress());
        return metricsConfig;
    }

    /**
     * Builds a standard redisques configuration.
     */
    public static JsonObject buildRedisquesConfig(){
        JsonObject redisquesConfig = new JsonObject();
        redisquesConfig.put("address", Address.redisquesAddress());
        redisquesConfig.put("processor-address", Address.queueProcessorAddress());
        return redisquesConfig;
    }

    /**
     * Builds a standard storage configuration.
     */
    public static JsonObject buildStorageConfig(){
        JsonObject storageConfig = new JsonObject();
        storageConfig.put("storage", "redis");
        storageConfig.put("storageAddress", Address.storageAddress() + "-main");
        return storageConfig;
    }

    /**
     * Builds a standard UserProfileConfiguration.
     */
    public static UserProfileConfiguration buildUserProfileConfiguration(){
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
                .userProfileUriPattern(SERVER_ROOT + "/users/v1/([^/]*)/profile")
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
     * <li>li.chee.vertx~redisques
     * <li>li.chee.vertx~rest-storage
     * <li>com.bloidonia~mod-metrics
     * </ul><p>
     * The handler is called with Boolean.TRUE when all modules have been deployed successfully. When any of the modules
     * could not be deployed correctly, the handler returns Boolean.FALSE.
     *
     * @param vertx
     * @param verticleClass
     * @param props
     * @param handler
     */
    public static void deployModules(final Vertx vertx, Class verticleClass, Map<String, Object> props, final Handler<Boolean> handler){
        final Logger log = LoggerFactory.getLogger(verticleClass);
        String redisHost = (String) props.get("redis.host");
        Integer redisPort = (Integer) props.get("redis.port");
        log.info("deploying redis module with host:" + redisHost + " port:" + redisPort);

        // redisques module
        vertx.deployVerticle("li.chee.vertx.redisques.RedisQues", new DeploymentOptions().setConfig(RunConfig.buildRedisquesConfig()).setInstances(4), event -> {
            if (event.failed()) {
                log.error("Could not load redisques module", event.cause());
                handler.handle(false);
                return;
            }
            // rest storage module
            vertx.deployVerticle("li.chee.vertx.reststorage.RestStorageMod", new DeploymentOptions().setConfig(RunConfig.buildStorageConfig()).setInstances(4), event1 -> {
                if (event1.failed()) {
                    log.error("Could not load rest storage redis module", event1.cause());
                    handler.handle(false);
                    return;
                }

                // metrics module
                vertx.deployVerticle("com.bloidonia.vertx.metrics.MetricsModule", new DeploymentOptions().setConfig(RunConfig.buildMetricsConfig()), event2 -> {
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
    public Handler<RoutingContext> buildRoutingContextHandler(){
        return new Handler<RoutingContext>() {

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

                monitoringHandler.updateIncomingRequests(request);

                if(corsHandler != null) {
                    corsHandler.handle(request);
                    if (corsHandler.isOptionsRequest(request)) {
                        return;
                    }
                }

                if(authorizer != null){
                    authorizer.authorize(request, event -> handleRequest(request));
                } else {
                    handleRequest(request);
                }
            }

            private void handleRequest(final HttpServerRequest request){
                if (request.path().equals(SERVER_ROOT + "/cleanup")) {
                    QueuingHandler.cleanup(vertx);
                    request.response().end();
                    return;
                }
                if (PackingHandler.isPacked(request)) {
                    request.bodyHandler(new PackingHandler(request, new QueuingHandler(vertx, redisClient, request, monitoringHandler)));
                } else {
                    if (QueuingHandler.isQueued(request)) {
                        setISO8601Timestamps(request);
                        request.bodyHandler(new QueuingHandler(vertx, redisClient, request, monitoringHandler));
                    } else {
                        if (copyResourceHandler != null && copyResourceHandler.handle(request)) {
                            return;
                        }
                        if (request.path().startsWith(SERVER_ROOT + "/queuing/")) {
                            queueBrowser.handle(request);
                            return;
                        }
                        if (hookHandler != null && hookHandler.handle(request)) {
                            return;
                        }
                        if (eventBusHandler != null && eventBusHandler.handle(request)) {
                            return;
                        }
                        if (validationHandler != null && validationHandler.isToValidate(request)) {
                            validationHandler.handle(request);
                            return;
                        }
                        if (loggingResourceManager != null && loggingResourceManager.handleLoggingResource(request)) {
                            return;
                        }
                        if (validationResourceManager != null && validationResourceManager.handleValidationResource(request)) {
                            return;
                        }
                        if (schedulerResourceManager != null && schedulerResourceManager.handleSchedulerResource(request)) {
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
                        } else {
                            setISO8601Timestamps(request);
                            router.route(request);
                        }
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
        } else {
            uid = request.headers().get("x-rp-unique-id");
            if (uid != null) {
                sb.append(uid);
                sb.append(" ");
            }
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
     * @param header The header name.
     */
    private void localizeTimestamp(HttpServerRequest request, String header) {
        String timestamp = request.headers().get(header);
        if (timestamp != null && timestamp.toUpperCase().endsWith("Z")) {
            try {
                DateTime dt = isoDateTimeParser.parseDateTime(timestamp);
                request.headers().set(header, dfISO8601.print(dt));
            } catch (IllegalArgumentException e) {
                log.warn("Could not parse " + header + " : " + timestamp);
            }
        }
    }
}

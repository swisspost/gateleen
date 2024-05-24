package org.swisspush.gateleen.scheduler;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.exception.GateleenExceptionFactory;
import org.swisspush.gateleen.core.logging.LoggableResource;
import org.swisspush.gateleen.core.logging.RequestLogger;
import org.swisspush.gateleen.core.redis.RedisProvider;
import org.swisspush.gateleen.core.refresh.Refreshable;
import org.swisspush.gateleen.core.storage.ResourceStorage;
import org.swisspush.gateleen.core.util.Address;
import org.swisspush.gateleen.core.util.ResourcesUtils;
import org.swisspush.gateleen.core.util.ResponseStatusCodeLogUtil;
import org.swisspush.gateleen.core.util.StatusCode;
import org.swisspush.gateleen.monitoring.MonitoringHandler;
import org.swisspush.gateleen.validation.ValidationException;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.swisspush.gateleen.core.exception.GateleenExceptionFactory.newGateleenThriftyExceptionFactory;

/**
 * @author https://github.com/lbovet [Laurent Bovet]
 */
public class SchedulerResourceManager implements Refreshable, LoggableResource {

    private static final String UPDATE_ADDRESS = "gateleen.schedulers-updated";
    private final String schedulersUri;
    private final ResourceStorage storage;
    private final Logger log = LoggerFactory.getLogger(SchedulerResourceManager.class);
    private final Vertx vertx;
    private List<Scheduler> schedulers;
    private final Map<String, Object> properties;
    private final SchedulerFactory schedulerFactory;
    private final String schedulersSchema;
    private boolean logConfigurationResourceChanges = false;

    public SchedulerResourceManager(Vertx vertx, RedisProvider redisProvider, final ResourceStorage storage,
                                    MonitoringHandler monitoringHandler, String schedulersUri) {
        this(vertx, redisProvider, storage, monitoringHandler, schedulersUri, null);
    }

    public SchedulerResourceManager(Vertx vertx, RedisProvider redisProvider, final ResourceStorage storage,
                                    MonitoringHandler monitoringHandler, String schedulersUri, Map<String,Object> props) {
        this(vertx, redisProvider, storage, monitoringHandler, schedulersUri, props, Address.redisquesAddress());
    }

    public SchedulerResourceManager(Vertx vertx, RedisProvider redisProvider, final ResourceStorage storage,
                                    MonitoringHandler monitoringHandler, String schedulersUri, Map<String,Object> props,
                                    String redisquesAddress) {
        this(vertx, redisProvider, newGateleenThriftyExceptionFactory(), storage, monitoringHandler, schedulersUri, props, redisquesAddress, Collections.emptyMap());
    }

    public SchedulerResourceManager(
        Vertx vertx,
        RedisProvider redisProvider,
        GateleenExceptionFactory exceptionFactory,
        ResourceStorage storage,
        MonitoringHandler monitoringHandler,
        String schedulersUri,
        Map<String, Object> props,
        String redisquesAddress,
        Map<String, String> defaultRequestHeaders
    ) {
        this.vertx = vertx;
        this.storage = storage;
        this.schedulersUri = schedulersUri;
        this.properties = props;

        this.schedulersSchema = ResourcesUtils.loadResource("gateleen_scheduler_schema_schedulers", true);
        this.schedulerFactory = new SchedulerFactory(properties, defaultRequestHeaders, vertx, redisProvider,
                exceptionFactory, monitoringHandler, schedulersSchema, redisquesAddress);

        updateSchedulers();

        // Receive update notifications
        vertx.eventBus().consumer(UPDATE_ADDRESS, (Handler<Message<Boolean>>) event -> updateSchedulers());
    }

    private void updateSchedulers() {
        storage.get(schedulersUri, buffer -> {
            if (buffer != null) {
                updateSchedulers(buffer);
            } else {
                log.info("No schedulers configured");
            }
        });
    }

    private void updateSchedulers(Buffer buffer) {
        stopSchedulers();
        try {
            schedulers = schedulerFactory.parseSchedulers(buffer);
        } catch(ValidationException validationException) {
            log.error("Could not parse schedulers: " + validationException);
        } finally {
            vertx.setTimer(2000, aLong -> startSchedulers());
        }
    }

    public boolean handleSchedulerResource(final HttpServerRequest request) {
        if (request.uri().equals(schedulersUri) && HttpMethod.PUT == request.method()) {
            request.bodyHandler(buffer -> {
                try {
                    schedulerFactory.parseSchedulers(buffer);
                } catch (ValidationException validationException) {
                    log.warn("Could not parse schedulers: " + validationException);
                    ResponseStatusCodeLogUtil.info(request, StatusCode.BAD_REQUEST, SchedulerResourceManager.class);
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
                storage.put(schedulersUri, buffer, status -> {
                    if (status == 200) {
                        if(logConfigurationResourceChanges) {
                            RequestLogger.logRequest(vertx.eventBus(), request, status, buffer);
                        }
                        vertx.eventBus().publish(UPDATE_ADDRESS, true);
                    } else {
                        request.response().setStatusCode(status);
                    }
                    ResponseStatusCodeLogUtil.info(request, StatusCode.fromCode(status), SchedulerResourceManager.class);
                    request.response().end();
                });
            });
            return true;
        }

        if (request.uri().equals(schedulersUri) && HttpMethod.DELETE == request.method()) {
            stopSchedulers();
        }
        return false;
    }

    private void startSchedulers() {
        if(schedulers != null) {
            schedulers.forEach(Scheduler::start);
        }
    }

    private void stopSchedulers() {
        if(schedulers != null) {
            schedulers.forEach(Scheduler::stop);
        }
    }

    /**
     * Returns a list of all registered
     * schedulers.
     * @return List
     */
    protected List<Scheduler> getSchedulers() {
        return schedulers;
    }

    @Override
    public void refresh() {
        updateSchedulers();
    }

    @Override
    public void enableResourceLogging(boolean resourceLoggingEnabled) {
        this.logConfigurationResourceChanges = resourceLoggingEnabled;
    }
}

package org.swisspush.gateleen.scheduler;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.event.TrackableEventPublish;
import org.swisspush.gateleen.core.exception.GateleenExceptionFactory;
import org.swisspush.gateleen.core.logging.LoggableResource;
import org.swisspush.gateleen.core.logging.RequestLogger;
import org.swisspush.gateleen.core.redis.RedisProvider;
import org.swisspush.gateleen.core.refresh.Refreshable;
import org.swisspush.gateleen.core.storage.ResourceStorage;
import org.swisspush.gateleen.core.util.Address;
import org.swisspush.gateleen.core.util.PropertyUtils;
import org.swisspush.gateleen.core.util.ResourcesUtils;
import org.swisspush.gateleen.core.util.ResponseStatusCodeLogUtil;
import org.swisspush.gateleen.core.util.StatusCode;
import org.swisspush.gateleen.monitoring.MonitoringHandler;
import org.swisspush.gateleen.validation.ValidationException;

import javax.annotation.Nullable;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.swisspush.gateleen.core.exception.GateleenExceptionFactory.newGateleenThriftyExceptionFactory;

/**
 * @author https://github.com/lbovet [Laurent Bovet]
 */
public class SchedulerResourceManager implements Refreshable, LoggableResource {

    public static final String DAYLIGHT_SAVING_TIME_OBSERVE_PROPERTY = "dst.observe";
    private static final int PUBLISH_EVENTS_FEEDBACK_TIMEOUT_MS = 1000;
    static final String UPDATE_ADDRESS = "gateleen.schedulers-updated";
    private final String schedulersUri;
    private final ResourceStorage storage;
    private final Logger log = LoggerFactory.getLogger(SchedulerResourceManager.class);
    private final Vertx vertx;
    private List<Scheduler> schedulers;
    private final Map<String, Object> properties;
    private final SchedulerFactory schedulerFactory;
    private final String schedulersSchema;
    private boolean logConfigurationResourceChanges = false;
    private boolean lastDaylightSavingTimeState;

    public SchedulerResourceManager(Vertx vertx, RedisProvider redisProvider, final ResourceStorage storage,
                                    @Nullable MonitoringHandler monitoringHandler, String schedulersUri) {
        this(vertx, redisProvider, storage, monitoringHandler, schedulersUri, null);
    }

    public SchedulerResourceManager(Vertx vertx, RedisProvider redisProvider, final ResourceStorage storage,
                                    @Nullable MonitoringHandler monitoringHandler, String schedulersUri, Map<String, Object> props) {
        this(vertx, redisProvider, storage, monitoringHandler, schedulersUri, props, Address.redisquesAddress());
    }

    public SchedulerResourceManager(Vertx vertx, RedisProvider redisProvider, final ResourceStorage storage,
                                    @Nullable MonitoringHandler monitoringHandler, String schedulersUri, Map<String, Object> props,
                                    String redisquesAddress) {
        this(vertx, redisProvider, newGateleenThriftyExceptionFactory(), storage, monitoringHandler, schedulersUri, props, redisquesAddress, Collections.emptyMap());
    }

    public SchedulerResourceManager(
            Vertx vertx,
            RedisProvider redisProvider,
            GateleenExceptionFactory exceptionFactory,
            ResourceStorage storage,
            @Nullable MonitoringHandler monitoringHandler,
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
        TrackableEventPublish.consumer(vertx, UPDATE_ADDRESS, event -> updateSchedulers());

        // Check for daylight saving time changes every minute
        // If a change is detected, all schedulers are restarted
        scheduleDaylightSavingTimeStateChangeObserver();
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
        } catch (ValidationException validationException) {
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
                    if (validationException.getValidationDetails() != null) {
                        request.response().headers().add("content-type", "application/json");
                        request.response().end(validationException.getValidationDetails().encode());
                    } else {
                        request.response().end(validationException.getMessage());
                    }
                    return;
                }
                storage.put(schedulersUri, buffer, status -> {
                    if (status == 200) {
                        if (logConfigurationResourceChanges) {
                            RequestLogger.logRequest(vertx.eventBus(), request, status, buffer);
                        }
                        TrackableEventPublish.publish(vertx, UPDATE_ADDRESS, true, PUBLISH_EVENTS_FEEDBACK_TIMEOUT_MS)
                                .onComplete(event -> {
                                    if (event.failed()) {
                                        log.error("Could not publish scheduler resource update.", event.cause());
                                        return;
                                    }
                                    log.info("scheduler resource update published, {} consumer answered", event.result());
                                });
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
        if (schedulers != null) {
            schedulers.forEach(Scheduler::start);
        }
    }

    private void stopSchedulers() {
        if (schedulers != null) {
            schedulers.forEach(Scheduler::stop);
        }
    }

    /**
     * Returns a list of all registered
     * schedulers.
     *
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

    /**
     * Evaluate if the given date is in daylight saving time or not.
     *
     * @return true if in daylight saving time, false otherwise
     */
    public static boolean isDaylightSavingTimeState(ZonedDateTime checkDate) {
        return checkDate.getZone().getRules().isDaylightSavings(checkDate.toInstant());
    }

    /**
     * Evaluate if the current date is in daylight saving time or not.
     *
     * @return true if in daylight saving time, false otherwise
     */
    public static boolean isCurrentDaylightSavingTimeState() {
        return isDaylightSavingTimeState(ZonedDateTime.now());
    }

    /**
     * Schedule a periodic check for daylight saving time changes.
     */
    private void scheduleDaylightSavingTimeStateChangeObserver() {
        if (PropertyUtils.isPropertyTrue(properties, DAYLIGHT_SAVING_TIME_OBSERVE_PROPERTY)) {
            // initialise the current daylight saving time for later state change comparison
            lastDaylightSavingTimeState = isCurrentDaylightSavingTimeState();
            // check each minute for a daylight saving time change
            vertx.setPeriodic(60_000, 60_000, timerId -> observeDaylightSavingTimeChange());
            log.info("Daylight saving time Observer started");
        } else {
            log.info("Daylight saving time Observer omitted");
        }
    }

    /**
     * Checks if a daylight saving time change has occurred
     * and refreshes all schedulers if this is the case.
     * This will stop and restart all schedulers and remove their
     * corresponding redis timestamps and thus re-evaluate their next
     * execution time.
     */
    private void observeDaylightSavingTimeChange() {
        boolean currentDaylightSavingTimeState = isCurrentDaylightSavingTimeState();
        if (currentDaylightSavingTimeState != lastDaylightSavingTimeState) {
            log.info("Daylight saving time change detected, refreshing all schedulers");
            lastDaylightSavingTimeState = currentDaylightSavingTimeState;
            refresh();
        }
    }
}

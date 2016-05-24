package org.swisspush.gateleen.scheduler;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.redis.RedisClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.refresh.Refreshable;
import org.swisspush.gateleen.monitoring.MonitoringHandler;
import org.swisspush.gateleen.core.storage.ResourceStorage;
import org.swisspush.gateleen.core.util.ResourcesUtils;
import org.swisspush.gateleen.core.util.StatusCode;
import org.swisspush.gateleen.validation.ValidationException;

import java.util.List;
import java.util.Map;

/**
 * @author https://github.com/lbovet [Laurent Bovet]
 */
public class SchedulerResourceManager implements Refreshable {

    private static final String UPDATE_ADDRESS = "gateleen.schedulers-updated";
    private String schedulersUri;
    private ResourceStorage storage;
    private Logger log = LoggerFactory.getLogger(SchedulerResourceManager.class);
    private Vertx vertx;
    private List<Scheduler> schedulers;
    private Map<String, Object> properties;
    private SchedulerFactory schedulerFactory;
    private String schedulersSchema;

    public SchedulerResourceManager(Vertx vertx, RedisClient redisClient, final ResourceStorage storage, MonitoringHandler monitoringHandler, String schedulersUri) {
        this(vertx, redisClient, storage, monitoringHandler, schedulersUri, null);
    }

    public SchedulerResourceManager(Vertx vertx, RedisClient redisClient, final ResourceStorage storage, MonitoringHandler monitoringHandler, String schedulersUri, Map<String,Object> props) {
        this.vertx = vertx;
        this.storage = storage;
        this.schedulersUri = schedulersUri;
        this.properties = props;

        this.schedulersSchema = ResourcesUtils.loadResource("gateleen_scheduler_schema_schedulers", true);
        this.schedulerFactory = new SchedulerFactory(properties, vertx, redisClient, monitoringHandler, schedulersSchema);

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
            log.error("Could not parse schedulers: " + validationException.toString());
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
                    log.warn("Could not parse schedulers: " + validationException.toString());
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
                        vertx.eventBus().publish(UPDATE_ADDRESS, true);
                    } else {
                        request.response().setStatusCode(status);
                    }
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
     * Returns a list of all registred
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
}

package org.swisspush.gateleen.queue.queuing.circuitbreaker;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.refresh.Refreshable;
import org.swisspush.gateleen.core.storage.ResourceStorage;
import org.swisspush.gateleen.core.util.ResourcesUtils;
import org.swisspush.gateleen.core.util.StatusCode;
import org.swisspush.gateleen.validation.ValidationException;
import org.swisspush.gateleen.validation.ValidationResult;
import org.swisspush.gateleen.validation.Validator;

import java.util.ArrayList;
import java.util.List;

/**
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class QueueCircuitBreakerConfigurationResourceManager {
    private static final String UPDATE_ADDRESS = "gateleen.queue-circuit-breaker.config-updated";

    private final String circuitBreakerConfigUri;
    private final ResourceStorage storage;
    private final Logger log = LoggerFactory.getLogger(QueueCircuitBreakerConfigurationResourceManager.class);
    private final Vertx vertx;

    private QueueCircuitBreakerConfigurationResource configurationResource;

    private final List<Refreshable> refreshables;

    private final String configResourceSchema;

    public QueueCircuitBreakerConfigurationResourceManager(Vertx vertx, ResourceStorage storage, String circuitBreakerConfigUri) {
        this.vertx = vertx;
        this.storage = storage;
        this.circuitBreakerConfigUri = circuitBreakerConfigUri;

        configResourceSchema = ResourcesUtils.loadResource("gateleen_queue_schema_circuitBreakerConfiguration", true);

        refreshables = new ArrayList<>();

        updateConfigurationResource();

        // Receive update notifications
        vertx.eventBus().consumer(UPDATE_ADDRESS, (Handler<Message<Boolean>>) event -> updateConfigurationResource());
    }

    public QueueCircuitBreakerConfigurationResource getConfigurationResource() {
        if (configurationResource == null) {
            configurationResource = new QueueCircuitBreakerConfigurationResource();
        }
        return configurationResource;
    }

    /**
     * Adds a new Refreshable. <br >
     * All refreshables will be refreshed, if the
     * QueueCircuitBreakerConfigurationResource changes.
     *
     * @param refreshable - an instance of Refreshable
     */
    public void addRefreshable(Refreshable refreshable) {
        refreshables.add(refreshable);
    }

    /**
     * Refreshes all refreshables.
     */
    private void notifyRefreshables() {
        refreshables.forEach(Refreshable::refresh);
    }

    private void updateConfigurationResource() {
        storage.get(circuitBreakerConfigUri, buffer -> {
            if (buffer != null) {
                try {
                    extractConfigurationValues(buffer);
                    log.info("Applying circuit breaker configuration values : " + getConfigurationResource().toString());
                } catch (ValidationException e) {
                    log.warn("Could not reconfigure circuitbreaker", e);
                }
            } else {
                log.warn("Could not get URL '" + (circuitBreakerConfigUri == null ? "<null>" : circuitBreakerConfigUri) + "'.");
            }
            notifyRefreshables();
        });
    }

    public boolean handleConfigurationResource(final HttpServerRequest request) {
        if (request.uri().equals(circuitBreakerConfigUri) && HttpMethod.PUT == request.method()) {
            request.bodyHandler(configResourceBuffer -> {
                try {
                    extractConfigurationValues(configResourceBuffer);
                } catch (ValidationException validationException) {
                    log.error("Could not parse circuit breaker configuration resource: " + validationException.toString());
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
                storage.put(circuitBreakerConfigUri, configResourceBuffer, status -> {
                    if (status == StatusCode.OK.getStatusCode()) {
                        vertx.eventBus().publish(UPDATE_ADDRESS, true);
                    } else {
                        request.response().setStatusCode(status);
                    }
                    request.response().end();
                });
            });
            return true;
        }

        if (request.uri().equals(circuitBreakerConfigUri) && HttpMethod.DELETE == request.method()) {
            getConfigurationResource().reset();
            log.info("reset circuit breaker configuration resource");
            notifyRefreshables();
        }

        return false;
    }

    private void extractConfigurationValues(Buffer configResourceBuffer) throws ValidationException {
        ValidationResult validationResult = Validator.validateStatic(configResourceBuffer, configResourceSchema, log);
        if(!validationResult.isSuccess()){
            throw new ValidationException(validationResult);
        }

        try{
            JsonObject configRes = new JsonObject(configResourceBuffer.toString("UTF-8"));
            getConfigurationResource().reset();

            getConfigurationResource().setCircuitCheckEnabled(configRes.getBoolean("circuitCheckEnabled"));
            getConfigurationResource().setStatisticsUpdateEnabled(configRes.getBoolean("statisticsUpdateEnabled"));
            getConfigurationResource().setErrorThresholdPercentage(configRes.getInteger("errorThresholdPercentage"));
            getConfigurationResource().setEntriesMaxAgeMS(configRes.getInteger("entriesMaxAgeMS"));
            getConfigurationResource().setMinQueueSampleCount(configRes.getInteger("minQueueSampleCount"));
            getConfigurationResource().setMaxQueueSampleCount(configRes.getInteger("maxQueueSampleCount"));

            JsonObject openToHalfOpen = configRes.getJsonObject("openToHalfOpen");
            getConfigurationResource().setOpenToHalfOpenTaskEnabled(openToHalfOpen.getBoolean("enabled"));
            getConfigurationResource().setOpenToHalfOpenTaskInterval(openToHalfOpen.getInteger("interval"));

            JsonObject unlockQueues = configRes.getJsonObject("unlockQueues");
            getConfigurationResource().setUnlockQueuesTaskEnabled(unlockQueues.getBoolean("enabled"));
            getConfigurationResource().setUnlockQueuesTaskInterval(unlockQueues.getInteger("interval"));

            JsonObject unlockSampleQueues = configRes.getJsonObject("unlockSampleQueues");
            getConfigurationResource().setUnlockSampleQueuesTaskEnabled(unlockSampleQueues.getBoolean("enabled"));
            getConfigurationResource().setUnlockSampleQueuesTaskInterval(unlockSampleQueues.getInteger("interval"));

        } catch (Exception ex) {
            getConfigurationResource().reset();
            throw new ValidationException(ex);
        }
    }
}

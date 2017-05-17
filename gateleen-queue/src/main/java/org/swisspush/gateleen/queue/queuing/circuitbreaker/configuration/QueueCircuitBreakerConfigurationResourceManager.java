package org.swisspush.gateleen.queue.queuing.circuitbreaker.configuration;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.logging.LoggableResource;
import org.swisspush.gateleen.core.logging.RequestLogger;
import org.swisspush.gateleen.core.refresh.Refreshable;
import org.swisspush.gateleen.core.storage.ResourceStorage;
import org.swisspush.gateleen.core.util.ResourcesUtils;
import org.swisspush.gateleen.core.util.ResponseStatusCodeLogUtil;
import org.swisspush.gateleen.core.util.StatusCode;
import org.swisspush.gateleen.validation.ValidationException;
import org.swisspush.gateleen.core.validation.ValidationResult;
import org.swisspush.gateleen.validation.Validator;

import java.util.ArrayList;
import java.util.List;

import static org.swisspush.gateleen.core.util.StatusCode.OK;

/**
 * Manager class for the {@link QueueCircuitBreakerConfigurationResource}.
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class QueueCircuitBreakerConfigurationResourceManager implements LoggableResource {
    private static final String UPDATE_ADDRESS = "gateleen.queue-circuit-breaker.config-updated";

    private boolean logConfigurationResourceChanges = false;
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

    /**
     * Get the {@link QueueCircuitBreakerConfigurationResource} with the actual configuration values. When the config
     * resource is <code>null</code>, a new {@link QueueCircuitBreakerConfigurationResource} with the default values is
     * returned.
     * @return returns the {@link QueueCircuitBreakerConfigurationResource}
     */
    public QueueCircuitBreakerConfigurationResource getConfigurationResource() {
        if (configurationResource == null) {
            configurationResource = new QueueCircuitBreakerConfigurationResource();
        }
        return configurationResource;
    }

    @Override
    public void enableResourceLogging(boolean resourceLoggingEnabled) {
        this.logConfigurationResourceChanges = resourceLoggingEnabled;
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
     * Handles the provided request when the following conditions are met:
     * <ul>
     *     <li>Request URI matches the configured circuit breaker configuration URI</li>
     *     <li>Request method is either PUT or DELETE</li>
     * </ul>
     * @param request the request to handle
     * @return returns true if the specified conditions are met, false otherwise
     */
    public boolean handleConfigurationResource(final HttpServerRequest request) {
        if (request.uri().equals(circuitBreakerConfigUri) && HttpMethod.PUT == request.method()) {
            request.bodyHandler(configResourceBuffer -> {
                try {
                    extractConfigurationValues(configResourceBuffer);
                } catch (ValidationException validationException) {
                    log.error("Could not parse circuit breaker configuration resource: " + validationException.toString());
                    ResponseStatusCodeLogUtil.info(request, StatusCode.BAD_REQUEST, QueueCircuitBreakerConfigurationResourceManager.class);
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
                    if (status == OK.getStatusCode()) {
                        if(logConfigurationResourceChanges){
                            RequestLogger.logRequest(vertx.eventBus(), request, OK.getStatusCode(), configResourceBuffer);
                        }
                        vertx.eventBus().publish(UPDATE_ADDRESS, true);
                    } else {
                        request.response().setStatusCode(status);
                    }
                    ResponseStatusCodeLogUtil.info(request, StatusCode.fromCode(status), QueueCircuitBreakerConfigurationResourceManager.class);
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

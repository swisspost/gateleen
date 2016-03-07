package org.swisspush.gateleen.logging.logging;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.storage.ResourceStorage;
import org.swisspush.gateleen.core.util.ResourcesUtils;
import org.swisspush.gateleen.core.util.StatusCode;
import org.swisspush.gateleen.validation.validation.ValidationException;
import org.swisspush.gateleen.validation.validation.ValidationResult;
import org.swisspush.gateleen.validation.validation.Validator;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class LoggingResourceManager {

    private static final String UPDATE_ADDRESS = "gateleen.logging-updated";

    private final String loggingUri;
    private final ResourceStorage storage;
    private final Logger log = LoggerFactory.getLogger(LoggingResourceManager.class);
    private final Vertx vertx;
    private LoggingResource loggingResource;
    private final String loggingResourceSchema;

    public LoggingResource getLoggingResource() {
        if (loggingResource == null) {
            loggingResource = new LoggingResource();
        }
        return loggingResource;
    }

    public LoggingResourceManager(Vertx vertx, final ResourceStorage storage, String loggingUri) {
        this.storage = storage;
        this.vertx = vertx;
        this.loggingUri = loggingUri;

        loggingResourceSchema = ResourcesUtils.loadResource("gateleen_logging_schema_logging", true);

        updateLoggingResources();

        // Receive update notifications
        vertx.eventBus().consumer(UPDATE_ADDRESS, (Handler<Message<Boolean>>) event -> updateLoggingResources());
    }

    private void updateLoggingResources() {
        storage.get(loggingUri, buffer -> {
            if (buffer != null) {
                try {
                    updateLoggingResources(buffer);
                } catch (ValidationException e) {
                    log.warn("Could not reconfigure logging resources (filters and headers)", e);
                }
            } else {
                log.warn("Could not get URL '" + (loggingUri == null ? "<null>" : loggingUri) + "'.");
            }
        });
    }

    private void updateLoggingResources(Buffer buffer) throws ValidationException{
        extractLoggingFilterValues(buffer);

        for (Map<String, String> payloadFilters : getLoggingResource().getPayloadFilters()) {
            log.info("Applying Logging-Filter: " + payloadFilters);
        }

        switch (getLoggingResource().getHeaderLogStrategy()) {
        case LOG_ALL:
            log.info("All headers will be logged");
            break;
        case LOG_NONE:
            log.info("No headers will be logged");
            break;
        case LOG_LIST:
            log.info("Headers to log: " + getLoggingResource().getHeaders().toString());
        }
    }

    public boolean handleLoggingResource(final HttpServerRequest request) {
        if (request.uri().equals(loggingUri) && HttpMethod.PUT == request.method()) {
            request.bodyHandler(loggingResourceBuffer -> {
                try {
                    extractLoggingFilterValues(loggingResourceBuffer);
                } catch (ValidationException validationException) {
                    log.error("Could not parse logging resource: " + validationException.toString());
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
                storage.put(loggingUri, loggingResourceBuffer, status -> {
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

        if (request.uri().equals(loggingUri) && HttpMethod.DELETE == request.method()) {
            getLoggingResource().reset();
            log.info("Reset LoggingResource");
        }

        return false;
    }

    private void extractLoggingFilterValues(Buffer loggingResourceBuffer) throws ValidationException {
        ValidationResult validationResult = Validator.validateStatic(loggingResourceBuffer, loggingResourceSchema, log);
        if(!validationResult.isSuccess()){
            throw new ValidationException(validationResult);
        }

        try {
            JsonObject loggingRes = new JsonObject(loggingResourceBuffer.toString("UTF-8"));
            getLoggingResource().reset();

            /* Headers */
            List<String> headersList;
            JsonArray headersJsonArray = loggingRes.getJsonArray("headers");
            if (headersJsonArray == null) {
                getLoggingResource().setHeaderLogStrategy(LoggingResource.HeaderLogStrategy.LOG_ALL);
            } else {
                headersList = headersJsonArray.getList();
                if (headersList != null && headersList.isEmpty()) {
                    getLoggingResource().setHeaderLogStrategy(LoggingResource.HeaderLogStrategy.LOG_NONE);
                } else {
                    getLoggingResource().setHeaderLogStrategy(LoggingResource.HeaderLogStrategy.LOG_LIST);
                }
                getLoggingResource().addHeaders(headersList);
            }

            /* Payload */
            JsonObject payload = loggingRes.getJsonObject("payload");

            JsonObject destinations = payload.getJsonObject("destinations");
            if (destinations != null) {
                Map<String, Map<String, String>> destinationEntries = new HashMap<>();

                for (String fieldName : destinations.fieldNames()) {
                    JsonObject destination = destinations.getJsonObject(fieldName);

                    Map<String, String> options = new HashMap<>();
                    options.put("type", destination.getString("type"));

                    String typeLocation = null;

                    if (destination.getString("type").equalsIgnoreCase("file")) {
                        typeLocation = "file";
                    }
                    else if (destination.getString("type").equalsIgnoreCase("eventBus")) {
                        typeLocation = "address";
                    }

                    if (typeLocation != null) {
                        options.put(typeLocation, destination.getString(typeLocation));
                        destinationEntries.put(fieldName, options);
                    }
                    else {
                        log.warn("Could not configure destination '" + fieldName + "'. Missing typeLocation (file|address).");
                    }
                }
                getLoggingResource().addFilterDestinations(destinationEntries);
            }

            JsonArray filtersArray = payload.getJsonArray("filters");
            if (filtersArray != null) {
                for (Object filterEntry : filtersArray) {
                    JsonObject filterObject = (JsonObject) filterEntry;
                    Map<String, String> filterEntries = new HashMap<>();
                    for (String filterName : filterObject.fieldNames()) {
                        filterEntries.put(filterName, filterObject.getString(filterName));
                    }
                    getLoggingResource().addPayloadFilter(filterEntries);
                }
            }
        } catch (Exception ex) {
            getLoggingResource().reset();
            throw new ValidationException(ex);
        }
    }
}

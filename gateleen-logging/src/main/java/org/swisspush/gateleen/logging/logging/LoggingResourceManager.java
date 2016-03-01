package org.swisspush.gateleen.logging.logging;

import org.swisspush.gateleen.core.storage.ResourceStorage;
import org.swisspush.gateleen.core.util.StatusCode;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class LoggingResourceManager {

    private static final String UPDATE_ADDRESS = "gateleen.logging-updated";

    private String loggingUri;

    private ResourceStorage storage;

    private Logger log = LoggerFactory.getLogger(LoggingResourceManager.class);

    private Vertx vertx;

    private LoggingResource loggingResource;

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

        updateLoggingResources();

        // Receive update notifications
        vertx.eventBus().consumer(UPDATE_ADDRESS, (Handler<Message<Boolean>>) event -> updateLoggingResources());
    }

    private void updateLoggingResources() {
        storage.get(loggingUri, buffer -> {
            if (buffer != null) {
                try {
                    updateLoggingResources(buffer);
                } catch (IllegalArgumentException e) {
                    log.warn("Could not reconfigure logging resources (filters and headers)", e);
                }
            } else {
                log.warn("Could not get URL '" + (loggingUri == null ? "<null>" : loggingUri) + "'.");
            }
        });
    }

    private void updateLoggingResources(Buffer buffer) {
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
                } catch (IllegalArgumentException e) {
                    log.error("Could not parse logging resource", e);
                    request.response().setStatusCode(StatusCode.BAD_REQUEST.getStatusCode());
                    request.response().setStatusMessage(StatusCode.BAD_REQUEST.getStatusMessage());
                    request.response().end(e.getMessage());
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

    private void extractLoggingFilterValues(Buffer loggingResourceBuffer) {
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
                Map<String, Map<String, String>> destinationEntries = new HashMap<String, Map<String, String>>();

                for (String fieldName : destinations.fieldNames()) {
                    JsonObject destination = destinations.getJsonObject(fieldName);

                    Map<String, String> options = new HashMap<String, String>();
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
                        log.warn("Could not configure destination '" + fieldName + "'. Missing typeLocation (file|adress).");
                    }
                }
                getLoggingResource().addFilterDestinations(destinationEntries);
            }

            JsonArray filtersArray = payload.getJsonArray("filters");
            if (filtersArray != null) {
                for (Object filterEntry : filtersArray) {
                    JsonObject filterObject = (JsonObject) filterEntry;
                    Map<String, String> filterEntries = new HashMap<String, String>();
                    for (String filterName : filterObject.fieldNames()) {
                        filterEntries.put(filterName, filterObject.getString(filterName));
                    }
                    getLoggingResource().addPayloadFilter(filterEntries);
                }
            }
        } catch (Exception ex) {
            getLoggingResource().reset();
            throw new IllegalArgumentException(ex);
        }
    }
}

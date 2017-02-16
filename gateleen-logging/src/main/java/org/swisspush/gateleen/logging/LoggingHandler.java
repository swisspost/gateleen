package org.swisspush.gateleen.logging;

import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.http.CaseInsensitiveHeaders;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonObject;
import org.apache.log4j.Appender;
import org.apache.log4j.DailyRollingFileAppender;
import org.apache.log4j.EnhancedPatternLayout;
import org.slf4j.Logger;
import org.swisspush.gateleen.core.event.EventBusWriter;
import org.swisspush.gateleen.core.http.RequestLoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/**
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class LoggingHandler {

    private HttpServerRequest request;
    private MultiMap requestHeaders;
    private HttpClientResponse response;
    private boolean active = false;

    private Buffer requestPayload;
    private Buffer responsePayload;
    private LoggingResource loggingResource;
    private EventBus eventBus;

    private String currentDestination;

    private static final String LOGGING_DIR_PROPERTY = "org.swisspush.logging.dir";

    private static final String CONTENT_TYPE = "content-type";
    private static final String APPLICATION_JSON = "application/json";
    private static final String DEFAULT_LOGGER = "RequestLog";
    private static final String REJECT = "reject";
    private static final String DESTINATION = "destination";
    private static final String DESCRIPTION = "description";
    private static final String META_DATA = "metadata";
    private static final String TRANSMISSION = "transmission";
    private static final String URL = "url";
    private static final String METHOD = "method";
    private static final String STATUS_CODE = "statusCode";
    private static final String STATUS_MESSAGE = "statusMessage";
    private static final String REQUEST = "request";
    private static final String RESPONSE = "response";
    private static final String HEADERS = "headers";
    private static final String BODY = "body";
    private static final String FILE = "file";
    private static final String ADDRESS = "address";

    private Map<String, org.apache.log4j.Logger> loggers = new HashMap<>();
    private Map<String, Appender> appenders = new HashMap<>();

    private Logger log;

    public LoggingHandler(LoggingResourceManager loggingResourceManager, HttpServerRequest request, EventBus eventBus) {
        this.request = request;
        this.eventBus = eventBus;
        this.loggingResource = loggingResourceManager.getLoggingResource();
        this.log = RequestLoggerFactory.getLogger(LoggingHandler.class, request);

        boolean stopValidation = false;
        for (Map<String, String> payloadFilter : loggingResource.getPayloadFilters()) {
            if (active || stopValidation) {
                break;
            }

            // NEMO-5551: Custom sorting. We have to make sure key "URL" comes first in the array.
            List<Entry<String, String>> payloadFilterEntrySetList = new ArrayList<>();
            for (Entry<String, String> filterEntry : payloadFilter.entrySet()) {
                if (filterEntry.getKey().equalsIgnoreCase("url")) {
                    payloadFilterEntrySetList.add(0, filterEntry);
                } else {
                    payloadFilterEntrySetList.add(filterEntry);
                }
            }

            boolean reject = Boolean.parseBoolean(payloadFilter.get(REJECT));
            for (Entry<String, String> filterEntry : payloadFilterEntrySetList) {
                if (REJECT.equalsIgnoreCase(filterEntry.getKey())
                        || DESTINATION.equalsIgnoreCase(filterEntry.getKey())
                        || DESCRIPTION.equalsIgnoreCase(filterEntry.getKey())) {
                    continue;
                }

                FilterResult result = RequestPropertyFilter.filterProperty(request, filterEntry.getKey(), filterEntry.getValue(), reject);
                if (result == FilterResult.FILTER) {
                    active = true;
                    currentDestination = createLoggerAndGetDestination(payloadFilter);
                } else if (result == FilterResult.REJECT) {
                    active = false;
                    stopValidation = true;
                    break;
                } else if (result == FilterResult.NO_MATCH) {
                    active = false;
                    break;
                }
            }
        }
    }

    public boolean isActive() {
        return this.active;
    }

    /**
     * Returns the destination key for the given filterProperty. If no destination is
     * set the default key is used instead. <br />
     * A logger for the given destination is created if necessary or reused if
     * it already exists.
     *
     * @param payloadFilter
     * @return currentDestination
     */
    private String createLoggerAndGetDestination(Map<String, String> payloadFilter) {
        // the destination of the active filterProperty
        String filterDestination = payloadFilter.get(DESTINATION);

        // if not available set to 'default'
        if (filterDestination == null) {
            log.debug("no filterDestination set");
            filterDestination = "default";
        }

        // if the key is found, create a logger for the given file ...
        if (loggingResource.getDestinationEntries().containsKey(filterDestination)) {
            Map<String, String> destinationOptions = loggingResource.getDestinationEntries().get(filterDestination);

            Appender appender = null;
            if (destinationOptions.containsKey(FILE)) {
                log.debug("found destination entry with type 'file' for: " + filterDestination);
                appender = getFileAppender(filterDestination, destinationOptions.get(FILE));
            } else if (destinationOptions.containsKey("address")) {
                log.debug("found destination entry with type 'eventBus' for: " + filterDestination);
                appender = getEventBusAppender(filterDestination, destinationOptions);
            } else {
                log.warn("Unknown typeLocation for destination: " + filterDestination);
            }

            if (appender != null) {
                if (!loggers.containsKey(filterDestination)) {
                    org.apache.log4j.Logger filterLogger = org.apache.log4j.Logger.getLogger("LOG_FILTER_" + payloadFilter.get(URL));
                    filterLogger.removeAllAppenders();
                    filterLogger.addAppender(appender);
                    filterLogger.setAdditivity(false);
                    loggers.put(filterDestination, filterLogger);
                }
            } else {
                loggers.put(filterDestination, org.apache.log4j.Logger.getLogger(DEFAULT_LOGGER));
            }
        }
        // ... or use the default logger
        else {
            log.warn("no destination entry with name '" + filterDestination + "' found, using default logger instead");

            // use default logger!
            loggers.put(filterDestination, org.apache.log4j.Logger.getLogger(DEFAULT_LOGGER));
        }

        return filterDestination;
    }

    /**
     * Returns the eventBus appender matching the given
     * filterDestination. If no appender exists for the
     * given filterDestination, a new one is created and
     * returned.
     *
     * @param filterDestination
     * @param destinationOptions
     * @return
     */
    private Appender getEventBusAppender(String filterDestination, Map<String, String> destinationOptions) {
        if (!appenders.containsKey(filterDestination)) {

            /*
             * <appender name="requestLogEventBusAppender" class="EventBusAppender">
             * <param name="Address" value="event/gateleen-request-log" />
             * <layout class="org.apache.log4j.EnhancedPatternLayout">
             * <param name="ConversionPattern" value="%m%n" />
             * </layout>
             * </appender>
             */

            EventBusAppender appender = new EventBusAppender();
            EventBusAppender.setEventBus(eventBus);
            appender.setName(filterDestination);
            appender.setAddress(destinationOptions.get(ADDRESS));
            appender.setDeliveryOptionsHeaders(new CaseInsensitiveHeaders().add(META_DATA, destinationOptions.get(META_DATA)));
            appender.setTransmissionMode(EventBusWriter.TransmissionMode.fromString(destinationOptions.get(TRANSMISSION)));
            EnhancedPatternLayout layout = new EnhancedPatternLayout();
            layout.setConversionPattern("%m%n");
            appender.setLayout(layout);
            appenders.put(filterDestination, appender);
        }
        return appenders.get(filterDestination);
    }

    /**
     * Returns the file appender matching the given
     * filterDestination. If no appender exists for the
     * given filterDestination, a new one is created and
     * returned.
     *
     * @param filterDestination
     * @param fileName
     * @return
     */
    private Appender getFileAppender(String filterDestination, String fileName) {
        if (!appenders.containsKey(filterDestination)) {

            /*
             * <appender name="requestLogFileAppender" class="org.apache.log4j.DailyRollingFileAppender">
             * <param name="File" value="${org.swisspush.logging.dir}/gateleen-requests.log" />
             * <param name="Encoding" value="UTF-8" />
             * <param name="Append" value="true" />
             * <layout class="org.apache.log4j.EnhancedPatternLayout">
             * <param name="ConversionPattern" value="%m%n" />
             * </layout>
             * </appender>
             */

            log.debug("file path: " + System.getProperty(LOGGING_DIR_PROPERTY) + fileName);

            DailyRollingFileAppender appender = new DailyRollingFileAppender();
            appender.setName(filterDestination);
            appender.setFile(System.getProperty(LOGGING_DIR_PROPERTY) + fileName);
            appender.setEncoding("UTF-8");
            appender.setAppend(true);
            EnhancedPatternLayout layout = new EnhancedPatternLayout();
            layout.setConversionPattern("%m%n");
            appender.setLayout(layout);
            appender.activateOptions();

            appenders.put(filterDestination, appender);
        }

        return appenders.get(filterDestination);
    }

    public void setResponse(HttpClientResponse response) {
        this.response = response;
    }

    public void request(MultiMap headers) {
        this.requestHeaders = headers;
    }

    public void appendRequestPayload(Buffer data) {
        appendRequestPayload(data, request.headers());
    }

    public void appendResponsePayload(Buffer data) {
        appendResponsePayload(data, response.headers());
    }

    public void appendRequestPayload(Buffer data, MultiMap headers) {
        if (active && isJsonContent(headers)) {
            getRequestPayload().appendBuffer(data);
        }
    }

    public void appendResponsePayload(Buffer data, MultiMap headers) {
        if (active && isJsonContent(headers)) {
            getResponsePayload().appendBuffer(data);
        }
    }

    public void log() {
        log(request.uri(), request.method(), response.statusCode(), response.statusMessage(), this.requestHeaders, response.headers());
    }

    public void log(String uri, HttpMethod method, int statusCode, String statusMessage, MultiMap requestHeaders, MultiMap responseHeaders) {
        if (active) {
            JsonObject logEvent = new JsonObject().
                    put(URL, uri).
                    put(METHOD, method.name()).
                    put(STATUS_CODE, statusCode).
                    put(STATUS_MESSAGE, statusMessage);
            JsonObject requestLog = new JsonObject();
            JsonObject responseLog = new JsonObject();
            logEvent.put(REQUEST, requestLog);
            logEvent.put(RESPONSE, responseLog);
            requestLog.put(HEADERS, headersAsJson(requestHeaders));
            responseLog.put(HEADERS, headersAsJson(responseHeaders));
            if (requestPayload != null) {
                try {
                    requestLog.put(BODY, new JsonObject(requestPayload.toString("UTF-8")));
                } catch (DecodeException e) {
                    // ignore, bogus JSON
                }
            }
            if (responsePayload != null) {
                try {
                    responseLog.put(BODY, new JsonObject(responsePayload.toString("UTF-8")));
                } catch (DecodeException e) {
                    // ignore, bogus JSON
                }
            }

            try {
                aboutToLogRequest(currentDestination);
                loggers.get(currentDestination).info(logEvent.encode());
            } catch (Exception ex) {
                errorLogRequest(currentDestination, ex);
            }
        }
    }

    private void aboutToLogRequest(String currentDestination) {
        log.info("About to log to destination " + currentDestination);
    }

    private void errorLogRequest(String currentDestination, Exception ex) {
        log.error("Error logging to destination " + currentDestination + ". Cause: " + ex.toString());
    }

    private JsonObject headersAsJson(MultiMap headers) {
        JsonObject obj = new JsonObject();
        switch (loggingResource.getHeaderLogStrategy()) {
            case LOG_ALL:
                for (Entry<String, String> entry : headers) {
                    String key = entry.getKey().toLowerCase();
                    obj.put(key, entry.getValue());
                }
                break;
            case LOG_LIST:
                for (String header : loggingResource.getHeaders()) {
                    String value = headers.get(header);
                    if (value != null) {
                        obj.put(header, value);
                    }
                }
                break;
            case LOG_NONE:
                return obj;
            default:
                loggers.get(currentDestination).warn("Unsupported HeaderLogStrategy '" + loggingResource.getHeaderLogStrategy() + "' used. Log nothing!");
        }
        return obj;
    }

    private Buffer getRequestPayload() {
        if (requestPayload == null) {
            requestPayload = Buffer.buffer();
        }
        return requestPayload;
    }

    private Buffer getResponsePayload() {
        if (responsePayload == null) {
            responsePayload = Buffer.buffer();
        }
        return responsePayload;
    }

    private boolean isJsonContent(MultiMap headers) {
        return headers.contains(CONTENT_TYPE) && headers.get(CONTENT_TYPE).contains(APPLICATION_JSON);
    }
}
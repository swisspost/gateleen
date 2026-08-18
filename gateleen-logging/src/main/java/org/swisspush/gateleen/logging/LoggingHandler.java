package org.swisspush.gateleen.logging;

import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.appender.RollingFileAppender;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.slf4j.Logger;
import org.swisspush.gateleen.core.http.RequestLoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Pattern;

/**
 * Updated LoggingHandler with regex caching
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class LoggingHandler {

    private final HttpServerRequest request;
    private MultiMap requestHeaders;
    private HttpClientResponse response;
    private boolean active = false;

    private Buffer requestPayload;
    private Buffer responsePayload;
    private final LoggingResource loggingResource;
    private final LogAppenderRepository logAppenderRepository;

    private String currentDestination;

    private static final String LOGGING_DIR_PROPERTY = "org.swisspush.logging.dir";

    private static final String CONTENT_TYPE = "content-type";
    private static final String APPLICATION_JSON = "application/json";
    private static final String DEFAULT_LOGGER = "RequestLog";
    private static final String REJECT = "reject";
    private static final String DESTINATION = "destination";

    private static final String URL = "url";
    private static final String METHOD = "method";
    private static final String STATUS_CODE = "statusCode";
    private static final String STATUS_MESSAGE = "statusMessage";
    private static final String REQUEST = "request";
    private static final String RESPONSE = "response";
    private static final String HEADERS = "headers";
    private static final String BODY = "body";

    public static final String SKIP_LOGGING_HEADER = "x-skip-request-log";

    private final Map<String, org.apache.logging.log4j.Logger> loggers = new HashMap<>();
    private final Logger log;

    // Cache for precompiled regex patterns
    private static final Map<String, Pattern> regexCache = new HashMap<>();

    // Helper method to fetch or compile a regex pattern
    private static Pattern getOrCompilePattern(String regex) {
        return regexCache.computeIfAbsent(regex, Pattern::compile);
    }

    public LoggingHandler(LoggingResourceManager loggingResourceManager, LogAppenderRepository logAppenderRepository, HttpServerRequest request, EventBus eventBus) {
        this.logAppenderRepository = logAppenderRepository;
        this.request = request;
        this.loggingResource = loggingResourceManager.getLoggingResource();
        this.log = RequestLoggerFactory.getLogger(LoggingHandler.class, request);
        ((org.apache.logging.log4j.core.Logger) LogManager.getLogger(DEFAULT_LOGGER)).setAdditive(false);

        if (request.headers().get(SKIP_LOGGING_HEADER) != null) {
            log.info("Request will not be logged because of skip log request header");
            return;
        }

        boolean stopValidation = false;

        for (Map<String, String> payloadFilter : loggingResource.getPayloadFilters()) {
            if (active || stopValidation) {
                break;
            }

            // Sort "url" key to the head of the list
            List<Entry<String, String>> payloadFilterEntrySetList = new ArrayList<>();
            for (Entry<String, String> filterEntry : payloadFilter.entrySet()) {
                if (filterEntry.getKey().equalsIgnoreCase(URL)) {
                    payloadFilterEntrySetList.add(0, filterEntry);
                } else {
                    payloadFilterEntrySetList.add(filterEntry);
                }
            }

            boolean reject = Boolean.parseBoolean(payloadFilter.get(REJECT));
            for (Entry<String, String> filterEntry : payloadFilterEntrySetList) {
                String key = filterEntry.getKey();
                String value = filterEntry.getValue();
                Pattern pattern = getOrCompilePattern(value);

                if (RequestPropertyFilter.filterProperty(request, key, pattern, reject) == FilterResult.FILTER) {
                    active = true;
                    currentDestination = createLoggerAndGetDestination(payloadFilter);
                } else if (RequestPropertyFilter.filterProperty(request, key, pattern, reject) == FilterResult.REJECT) {
                    active = false;
                    stopValidation = true;
                    break;
                }
            }
        }
    }

    public boolean isActive() {
        return this.active;
    }

    private String createLoggerAndGetDestination(Map<String, String> payloadFilter) {
        String filterDestination = payloadFilter.get(DESTINATION);
        if (filterDestination == null) {
            log.debug("No filterDestination set");
            filterDestination = DEFAULT_LOGGER;
        }

        if (loggingResource.getDestinationEntries().containsKey(filterDestination)) {
            Map<String, String> destinationOptions = loggingResource.getDestinationEntries().get(filterDestination);
            Appender appender = getAppenderForDestination(filterDestination, destinationOptions);
            if (appender != null && !loggers.containsKey(filterDestination)) {
                org.apache.logging.log4j.Logger logger = LogManager.getLogger("LOG_FILTER_" + payloadFilter.get(URL));
                ((org.apache.logging.log4j.core.Logger) logger).addAppender(appender);
                ((org.apache.logging.log4j.core.Logger) logger).setAdditive(false);
                loggers.put(filterDestination, logger);
            }
        } else {
            loggers.put(filterDestination, LogManager.getLogger(DEFAULT_LOGGER));
        }

        return filterDestination;
    }

    private Appender getAppenderForDestination(String filterDestination, Map<String, String> destinationOptions) {
        if (logAppenderRepository.hasAppender(filterDestination)) {
            return logAppenderRepository.getAppender(filterDestination);
        }

        if (destinationOptions.containsKey("file")) {
            String fileName = destinationOptions.get("file");
            RollingFileAppender.Builder builder = RollingFileAppender.newBuilder();
            builder.withFileName(System.getProperty(LOGGING_DIR_PROPERTY) + fileName);
            builder.withAppend(true);
            builder.withLayout(PatternLayout.createDefaultLayout());
            builder.withName(filterDestination);
            Appender appender = builder.build();
            logAppenderRepository.addAppender(filterDestination, appender);
            return appender;
        }
        return null;
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
            log.info("request is going to be logged");
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
                    requestLog.put(BODY, new JsonObject(requestPayload));
                } catch (DecodeException e) {
                    // maybe payload was a JsonArray
                    try {
                        requestLog.put(BODY, new JsonArray(requestPayload));
                    } catch (DecodeException ex) {
                        log.info("request payload could not be parsed and will not be logged");
                    }
                }
            }
            if (responsePayload != null) {
                String responsePayloadString = responsePayload.toString("UTF-8");
                try {
                    responseLog.put(BODY, new JsonObject(responsePayloadString));
                } catch (DecodeException e) {
                    // maybe payload was a JsonArray
                    try {
                        responseLog.put(BODY, new JsonArray(responsePayloadString));
                    } catch (DecodeException ex) {
                        log.info("response payload could not be parsed and will not be logged");
                    }
                }
            }

            try {
                aboutToLogRequest(currentDestination);
                loggers.get(currentDestination).info(logEvent.encode());
            } catch (Exception ex) {
                errorLogRequest(currentDestination, ex);
            }
        } else {
            log.info("request will not be logged");
        }
    }

    private void aboutToLogRequest(String currentDestination) {
        log.info("About to log to destination {}", currentDestination);
    }

    private void errorLogRequest(String currentDestination, Exception ex) {
        log.error("Error logging to destination {}. Cause: {}", currentDestination, ex.toString());
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

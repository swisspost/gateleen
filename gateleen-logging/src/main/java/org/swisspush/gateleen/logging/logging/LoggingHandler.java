package org.swisspush.gateleen.logging.logging;

import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
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
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class LoggingHandler {

    private HttpServerRequest request;
    private MultiMap requestHeaders;
    private HttpClientResponse response;
    private Boolean active = false;
    private Buffer requestPayload;
    private Buffer responsePayload;
    private LoggingResource loggingResource;

    private String currentDestination;

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
    private static final String FILE = "file";
    private static final String ADDRESS = "address";

    private Map<String, org.apache.log4j.Logger> loggers = new HashMap<>();
    private Map<String, Appender> appenders = new HashMap<>();

    private static final Logger log = LoggerFactory.getLogger(LoggingHandler.class);

    private enum FilterResult {
        FILTER, REJECT, NO_MATCH;
    }

    public LoggingHandler(LoggingResourceManager loggingResourceManager, HttpServerRequest request) {
        this.request = request;
        this.loggingResource = loggingResourceManager.getLoggingResource();

        boolean stopValidation = false;
        for (Map<String, String> payloadFilter : loggingResource.getPayloadFilters()) {
            if (active || stopValidation) {
                break;
            }

            boolean reject = Boolean.parseBoolean(payloadFilter.get(REJECT));
            for (Entry<String, String> payloadFilterEntry : payloadFilter.entrySet()) {
                if (REJECT.equalsIgnoreCase(payloadFilterEntry.getKey()) || DESTINATION.equalsIgnoreCase(payloadFilterEntry.getKey())) {
                    continue;
                }

                FilterResult result = filterRequest(request, payloadFilterEntry, reject);
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

    /**
     * Returns the destination key for the given filter. If no destination is
     * set the default key is used instead. <br />
     * A logger for the given destination is created if necessary or reused if
     * it already exists.
     * 
     * @param payloadFilter
     * @return currentDestination
     */
    private String createLoggerAndGetDestination(Map<String, String> payloadFilter) {
        // the destination of the active filter
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
            }
            else if (destinationOptions.containsKey("address")) {
                log.debug("found destination entry with type 'eventBus' for: " + filterDestination);
                appender = getEventBusAppender(filterDestination, destinationOptions.get(ADDRESS));
            }
            else {
                log.warn("Unknown typeLocation for destination: " + filterDestination);
            }

            if (appender != null) {
                org.apache.log4j.Logger filterLogger = org.apache.log4j.Logger.getLogger("LOG_FILTER_" + payloadFilter.get(URL));
                filterLogger.removeAllAppenders();
                filterLogger.addAppender(appender);
                filterLogger.setAdditivity(false);
                loggers.put(filterDestination, filterLogger);
            }
            else {
                loggers.put(filterDestination, org.apache.log4j.Logger.getLogger(DEFAULT_LOGGER));
            }
        }
        // ... or use the default logger
        else {
            log.debug("no destination entry found, use default logger");

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
     * @param address
     * @return
     */
    private Appender getEventBusAppender(String filterDestination, String address) {
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
            appender.setName(filterDestination);
            appender.setAddress(address);
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

            log.debug("file path: " + System.getProperty("org.swisspush.logging.dir") + fileName);

            DailyRollingFileAppender appender = new DailyRollingFileAppender();
            appender.setName(filterDestination);
            appender.setFile(System.getProperty("org.swisspush.logging.dir") + fileName);
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
                    // ignore, bogous JSON
                }
            }
            if (responsePayload != null) {
                try {
                    responseLog.put(BODY, new JsonObject(responsePayload.toString("UTF-8")));
                } catch (DecodeException e) {
                    // ignore, bogous JSON
                }
            }

            loggers.get(currentDestination).info(logEvent.encode());
        }
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

    private FilterResult filterRequest(HttpServerRequest request, Entry<String, String> filterEntry, boolean reject) {
        CaseInsensitiveHeaders headers = new CaseInsensitiveHeaders();
        headers.setAll(request.headers());

        if (URL.equals(filterEntry.getKey())) {
            boolean matches = filterRequestURL(request, filterEntry.getValue());
            return rejectIfNeeded(reject, matches);
        }
        if (METHOD.equals(filterEntry.getKey())) {
            boolean matches = filterRequestMethod(request, filterEntry.getValue());
            return rejectIfNeeded(reject, matches);
        }
        if (headers.names().contains(filterEntry.getKey()) && headers.get(filterEntry.getKey()).equalsIgnoreCase(filterEntry.getValue())) {
            return reject ? FilterResult.REJECT : FilterResult.FILTER;
        }
        return FilterResult.REJECT;
    }

    private FilterResult rejectIfNeeded(boolean reject, boolean matches) {
        if (reject) {
            return matches ? FilterResult.REJECT : FilterResult.NO_MATCH;
        } else {
            return matches ? FilterResult.FILTER : FilterResult.NO_MATCH;
        }
    }

    private boolean filterRequestURL(HttpServerRequest request, String url) {
        Pattern urlPattern = Pattern.compile(url);
        Matcher urlMatcher = urlPattern.matcher(request.uri());
        return urlMatcher.matches();
    }

    private boolean filterRequestMethod(HttpServerRequest request, String method) {
        Pattern methodPattern = Pattern.compile(method);
        Matcher methodMatcher = methodPattern.matcher(request.method().toString());
        return methodMatcher.matches();
    }
}

package org.swisspush.gateleen.scheduler;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.http.HttpRequest;
import org.swisspush.gateleen.core.redis.RedisProvider;
import org.swisspush.gateleen.core.util.StringUtils;
import org.swisspush.gateleen.core.validation.ValidationResult;
import org.swisspush.gateleen.monitoring.MonitoringHandler;
import org.swisspush.gateleen.validation.ValidationException;
import org.swisspush.gateleen.validation.Validator;

import java.nio.charset.Charset;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * SchedulerFactory is used to create scheduler objects from their text representation.
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class SchedulerFactory {

    private static final String HEADERS = "headers";
    private static final String PAYLOAD = "payload";
    private static final String REQUESTS = "requests";
    private static final String SCHEDULERS = "schedulers";
    private static final String RANDOM_OFFSET = "randomOffset";
    private static final String EXECUTE_ON_STARTUP = "executeOnStartup";
    private static final String EXECUTE_ON_RELOAD = "executeOnReload";

    private final Map<String, Object> properties;
    private final JsonArray defaultRequestHeaders;
    private Vertx vertx;
    private RedisProvider redisProvider;
    private MonitoringHandler monitoringHandler;
    private String schedulersSchema;
    private String redisquesAddress;

    private Logger log = LoggerFactory.getLogger(SchedulerFactory.class);

    public SchedulerFactory(Map<String, Object> properties, Map<String, String> defaultRequestHeaders, Vertx vertx,
                            RedisProvider redisProvider, MonitoringHandler monitoringHandler, String schedulersSchema,
                            String redisquesAddress) {
        this.properties = properties;
        this.defaultRequestHeaders = defaultRequestHeadersAsJsonArray(defaultRequestHeaders);
        this.vertx = vertx;
        this.redisProvider = redisProvider;
        this.monitoringHandler = monitoringHandler;
        this.schedulersSchema = schedulersSchema;
        this.redisquesAddress = redisquesAddress;
    }

    private JsonArray defaultRequestHeadersAsJsonArray(Map<String, String> defaultRequestHeaders) {
        JsonArray headers = new JsonArray();
        if (defaultRequestHeaders == null) {
            return headers;
        }
        for (Map.Entry<String, String> entry : defaultRequestHeaders.entrySet()) {
            headers.add(new JsonArray().add(entry.getKey()).add(entry.getValue()));
        }
        return headers;
    }

    public List<Scheduler> parseSchedulers(Buffer buffer) throws ValidationException {
        List<Scheduler> result = new ArrayList<>();
        String configString;
        try {
            configString = StringUtils.replaceWildcardConfigs(buffer.toString("UTF-8"), properties);
        } catch (Exception e) {
            throw new ValidationException(e);
        }

        ValidationResult validationResult = Validator.validateStatic(Buffer.buffer(configString), schedulersSchema, log);
        if (!validationResult.isSuccess()) {
            throw new ValidationException(validationResult);
        }

        JsonObject mainObject = new JsonObject(configString);
        for (Map.Entry<String, Object> entry : mainObject.getJsonObject(SCHEDULERS).getMap().entrySet()) {
            Map<String, Object> schedulerJson = (Map<String, Object>) entry.getValue();

            boolean executeOnStartup = false;
            boolean executeOnReload = false;
            if (schedulerJson.containsKey(EXECUTE_ON_STARTUP)) {
                executeOnStartup = (boolean) schedulerJson.get(EXECUTE_ON_STARTUP);

                // reload is always as a default performed, if startup execution is enforced
                executeOnReload = executeOnStartup;
            }

            // do we need to fire a scheduler on a reload?
            if (schedulerJson.containsKey(EXECUTE_ON_RELOAD)) {
                executeOnReload = (boolean) schedulerJson.get(EXECUTE_ON_RELOAD);
            }


            int maxRandomOffset = 0;
            if (schedulerJson.containsKey(RANDOM_OFFSET)) {
                try {
                    maxRandomOffset = (Integer) schedulerJson.get(RANDOM_OFFSET);
                } catch (NumberFormatException e) {
                    throw new ValidationException("Could not parse " + RANDOM_OFFSET + " of scheduler '" + entry.getKey() + "'", e);
                }
            }

            List<HttpRequest> requests = new ArrayList<>();
            for (int i = 0; i < ((ArrayList<Object>) schedulerJson.get(REQUESTS)).size(); i++) {
                try {
                    requests.add(new HttpRequest(prepare((JsonObject) mainObject.getJsonObject(SCHEDULERS)
                            .getJsonObject(entry.getKey()).getJsonArray(REQUESTS).getValue(i)))
                    );
                } catch (Exception e) {
                    throw new ValidationException("Could not parse request [" + i + "] of scheduler " + entry.getKey(), e);
                }
            }
            try {
                result.add(new Scheduler(vertx, redisquesAddress, redisProvider, entry.getKey(),
                        (String) schedulerJson.get("cronExpression"), requests, monitoringHandler, maxRandomOffset, executeOnStartup, executeOnReload)
                );
            } catch (ParseException e) {
                throw new ValidationException("Could not parse cron expression of scheduler '" + entry.getKey() + "'", e);
            }
        }
        return result;
    }

    private JsonObject prepare(JsonObject httpRequestJsonObject) {
        String payloadStr;
        try {
            if (httpRequestJsonObject.getJsonObject(PAYLOAD) != null) {
                payloadStr = httpRequestJsonObject.getJsonObject(PAYLOAD).encode();
            } else {
                payloadStr = null;
            }
        } catch (ClassCastException e) {
            payloadStr = httpRequestJsonObject.getString(PAYLOAD);
        }

        JsonArray headers = httpRequestJsonObject.getJsonArray(HEADERS);
        if (headers != null) {
            httpRequestJsonObject.put(HEADERS, headers.addAll(defaultRequestHeaders.copy()));
        } else {
            httpRequestJsonObject.put(HEADERS, defaultRequestHeaders.copy());
        }


        if (payloadStr != null) {
            byte[] payload = payloadStr.getBytes(Charset.forName("UTF-8"));
            httpRequestJsonObject.getJsonArray(HEADERS).add(new JsonArray(Arrays.asList(new Object[]{"content-length", "" + payload.length})));
            httpRequestJsonObject.put(PAYLOAD, payload);
        }
        return httpRequestJsonObject;
    }
}

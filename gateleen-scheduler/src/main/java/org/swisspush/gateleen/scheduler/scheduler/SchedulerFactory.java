package org.swisspush.gateleen.scheduler.scheduler;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.redis.RedisClient;
import org.swisspush.gateleen.core.http.HttpRequest;
import org.swisspush.gateleen.core.monitoring.MonitoringHandler;
import org.swisspush.gateleen.core.util.StringUtils;

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

    private final Map<String, Object> properties;
    private Vertx vertx;
    private RedisClient redisClient;
    private MonitoringHandler monitoringHandler;

    public SchedulerFactory(Map<String, Object> properties, Vertx vertx, RedisClient redisClient, MonitoringHandler monitoringHandler) {
        this.properties = properties;
        this.vertx = vertx;
        this.redisClient = redisClient;
        this.monitoringHandler = monitoringHandler;
    }

    public List<Scheduler> parseSchedulers(Buffer buffer) {
        List<Scheduler> result = new ArrayList<>();
        String configString = StringUtils.replaceWildcardConfigs(buffer.toString("UTF-8"), properties);
        JsonObject mainObject = new JsonObject(configString);
        if(!mainObject.containsKey(SCHEDULERS)) {
            throw new IllegalArgumentException("Main object must have a '"+SCHEDULERS+"' field");
        }
        for(Map.Entry<String,Object> entry: mainObject.getJsonObject(SCHEDULERS).getMap().entrySet()) {
            Map<String,Object> schedulerJson = (Map<String,Object>)entry.getValue();
            List<HttpRequest> requests = new ArrayList<>();
            for(int i = 0; i< ((ArrayList<Object>)schedulerJson.get(REQUESTS)).size(); i++) {
                try {
                    requests.add(new HttpRequest(prepare((JsonObject) mainObject.getJsonObject(SCHEDULERS).getJsonObject(entry.getKey()).getJsonArray(REQUESTS).getValue(i))));
                } catch(Exception e) {
                    throw new IllegalArgumentException("Could not parse request ["+i+"] of scheduler "+entry.getKey(), e);
                }
            }
            try {
                result.add(new Scheduler(vertx, redisClient, entry.getKey(), (String)schedulerJson.get("cronExpression"), requests, monitoringHandler));
            } catch (ParseException e) {
                throw new IllegalArgumentException("Could not parse cron expression of scheduler '"+entry.getKey()+"'", e);
            }
        }
        return result;
    }

    private JsonObject prepare(JsonObject httpRequestJsonObject){
        String payloadStr;
        try {
            payloadStr = httpRequestJsonObject.getString(PAYLOAD);
        } catch(ClassCastException e) {
            payloadStr = httpRequestJsonObject.getJsonObject(PAYLOAD).encode();
        }
        if(payloadStr != null){
            byte[] payload = payloadStr.getBytes(Charset.forName("UTF-8"));
            JsonArray headers = httpRequestJsonObject.getJsonArray(HEADERS);
            if(headers == null) {
                httpRequestJsonObject.put(HEADERS, new JsonArray());
            }
            httpRequestJsonObject.getJsonArray(HEADERS).add(new JsonArray(Arrays.asList(new Object[]{ "content-length", ""+payload.length})));
            httpRequestJsonObject.put(PAYLOAD, payload);
        }
        return httpRequestJsonObject;
    }
}

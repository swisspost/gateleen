package org.swisspush.gateleen.scheduler.scheduler;

import org.swisspush.gateleen.core.http.HttpRequest;
import org.swisspush.gateleen.core.monitoring.MonitoringHandler;
import org.swisspush.gateleen.core.storage.ResourceStorage;
import com.floreysoft.jmte.DefaultModelAdaptor;
import com.floreysoft.jmte.Engine;
import com.floreysoft.jmte.ErrorHandler;
import com.floreysoft.jmte.TemplateContext;
import com.floreysoft.jmte.token.Token;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.redis.RedisClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.Charset;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class SchedulerResourceManager {

    private static final String UPDATE_ADDRESS = "gateleen.schedulers-updated";
    private String schedulersUri;
    private ResourceStorage storage;
    private Logger log = LoggerFactory.getLogger(SchedulerResourceManager.class);
    private Vertx vertx;
    private RedisClient redisClient;
    private List<Scheduler> schedulers;
    private MonitoringHandler monitoringHandler;
    private Map<String, Object> properties;

    public SchedulerResourceManager(Vertx vertx, RedisClient redisClient, final ResourceStorage storage, MonitoringHandler monitoringHandler, String schedulersUri) {
        this(vertx, redisClient, storage, monitoringHandler, schedulersUri, null);
    }

    public SchedulerResourceManager(Vertx vertx, RedisClient redisClient, final ResourceStorage storage, MonitoringHandler monitoringHandler, String schedulersUri, Map<String,Object> props) {
        this.vertx = vertx;
        this.redisClient = redisClient;
        this.storage = storage;
        this.monitoringHandler = monitoringHandler;
        this.schedulersUri = schedulersUri;
        this.properties = props;

        updateSchedulers();

        // Receive update notifications
        vertx.eventBus().consumer(UPDATE_ADDRESS, (Handler<Message<Boolean>>) event -> updateSchedulers());
    }

    private void updateSchedulers() {
        storage.get(schedulersUri, buffer -> {
            if (buffer != null) {
                try {
                    updateSchedulers(buffer);
                } catch (IllegalArgumentException e) {
                    log.error("Could not configure schedulers", e);
                }
            } else {
                log.info("No schedulers configured");
            }
        });
    }

    private void updateSchedulers(Buffer buffer) {
        stopSchedulers();
        try {
            schedulers = parseSchedulers(buffer);
        } catch(Exception e) {
            log.error("Could not parse schedulers", e);
        } finally {
            vertx.setTimer(2000, aLong -> startSchedulers());
        }
    }

    public boolean handleSchedulerResource(final HttpServerRequest request) {
        if (request.uri().equals(schedulersUri) && HttpMethod.PUT == request.method()) {
            request.bodyHandler(buffer -> {
                try {
                    parseSchedulers(buffer);
                } catch (Exception e) {
                    log.warn("Could not parse schedulers", e);
                    request.response().setStatusCode(400);
                    request.response().setStatusMessage("Bad Request");
                    request.response().end(e.getMessage()+(e.getCause()!=null ? "\n"+e.getCause().getMessage():""));
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

    private List<Scheduler> parseSchedulers(Buffer buffer) {
        List<Scheduler> result = new ArrayList<>();
        String configString = replaceConfigWildcards(buffer.toString("UTF-8"));
        JsonObject mainObject = new JsonObject(configString);
        if(!mainObject.containsKey("schedulers")) {
            throw new IllegalArgumentException("Main object must have a 'schedulers' field");
        }
        for(Map.Entry<String,Object> entry: mainObject.getJsonObject("schedulers").getMap().entrySet()) {
            Map<String,Object> schedulerJson = (Map<String,Object>)entry.getValue();
            List<HttpRequest> requests = new ArrayList<>();
            for(int i = 0; i< ((ArrayList<Object>)schedulerJson.get("requests")).size(); i++) {
                try {
                    requests.add(new HttpRequest(prepare((JsonObject) mainObject.getJsonObject("schedulers").getJsonObject(entry.getKey()).getJsonArray("requests").getValue(i))));
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
            payloadStr = httpRequestJsonObject.getString("payload");
        } catch(ClassCastException e) {
            payloadStr = httpRequestJsonObject.getJsonObject("payload").encode();
        }
        if(payloadStr != null){
            byte[] payload = payloadStr.getBytes(Charset.forName("UTF-8"));
            JsonArray headers = httpRequestJsonObject.getJsonArray("headers");
            if(headers == null) {
                httpRequestJsonObject.put("headers", new JsonArray());
            }
            httpRequestJsonObject.getJsonArray("headers").add(new JsonArray(Arrays.asList(new Object[]{ "content-length", ""+payload.length})));
            httpRequestJsonObject.put("payload", payload);
        }
        return httpRequestJsonObject;
    }

    private String replaceConfigWildcards(String configWithWildcards) {
        if(properties==null) {
            return configWithWildcards;
        }
        Engine engine = new Engine();
        engine.setModelAdaptor(new DefaultModelAdaptor() {
            @Override
            public Object getValue(TemplateContext context, Token arg1, List<String> arg2, String expression) {
                // First look in model map. Needed for dot-separated properties
                Object value = context.model.get(expression);
                if (value != null) {
                    return value;
                } else {
                    return super.getValue(context, arg1, arg2, expression);
                }
            }

            @Override
            protected Object traverse(Object obj, List<String> arg1, int arg2, ErrorHandler arg3, Token token) {
                // Throw exception if a token cannot be resolved instead of returning empty string.
                if (obj == null) {
                    throw new IllegalArgumentException("Could not resolve " + token);
                }
                return super.traverse(obj, arg1, arg2, arg3, token);
            }
        });
        try {
            return engine.transform(configWithWildcards, properties);
        } catch (com.floreysoft.jmte.message.ParseException e) {
            throw new IllegalArgumentException(e);
        }
    }
}

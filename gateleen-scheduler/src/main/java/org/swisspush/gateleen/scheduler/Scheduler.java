package org.swisspush.gateleen.scheduler;

import org.swisspush.gateleen.core.http.HttpRequest;
import org.swisspush.gateleen.core.monitoring.MonitoringHandler;
import org.swisspush.gateleen.core.util.Address;
import org.swisspush.gateleen.queue.queuing.RedisquesAPI;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonObject;
import io.vertx.redis.RedisClient;
import org.quartz.CronExpression;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import static org.swisspush.gateleen.queue.queuing.RedisquesAPI.OK;
import static org.swisspush.gateleen.queue.queuing.RedisquesAPI.STATUS;

/**
 * Schedules requests to be queued. Synchronizes using redis to ensure only one instance is fired.
 *
 * @author https://github.com/lbovet [Laurent Bovet]
 */
public class Scheduler {

    private Vertx vertx;
    private RedisClient redisClient;
    private String name;
    private CronExpression cronExpression;
    private List<HttpRequest> requests;
    private long timer;
    private MonitoringHandler monitoringHandler;

    private Logger log;

    public Scheduler(Vertx vertx, RedisClient redisClient, String name, String cronExpression, List<HttpRequest> requests, MonitoringHandler monitoringHandler) throws ParseException {
        this.vertx = vertx;
        this.redisClient = redisClient;
        this.name = name;
        this.cronExpression = new CronExpression(cronExpression);
        this.requests = requests;
        this.log = LoggerFactory.getLogger(Scheduler.class.getName()+".scheduler-"+name);
        this.monitoringHandler = monitoringHandler;
    }

    public void start() {
        log.info("Starting scheduler [ "+cronExpression.getCronExpression()+" ]");
        final EventBus eventBus = vertx.eventBus();
        timer = vertx.setPeriodic(5000, timer -> redisClient.get("schedulers:" + name, reply -> {
            final String stringValue = reply.result();
            if (stringValue == null || Long.parseLong(stringValue) <= System.currentTimeMillis()) {
                // Either first use of the scheduler or run time reached.
                // We need to set the next run time
                final long nextRunTime = nextRunTime();
                if (log.isTraceEnabled()) {
                    log.trace("Setting next run time to " + SimpleDateFormat.getDateTimeInstance().format(new Date(nextRunTime)));
                }
                redisClient.getset("schedulers:" + name, "" + nextRunTime, event -> {
                    String previousValue = event.result();
                    if (stringValue != null && stringValue.equals(previousValue)) {
                        // a run time was set and we were the first instance to update it
                        trigger();
                    }
                });
            }
        }));
    }

    public void stop() {
        log.info("Stopping scheduler [ "+cronExpression.getCronExpression()+" ] ");
        vertx.cancelTimer(timer);
        String key = "schedulers:"+name;
        redisClient.del(key, reply -> {
            if(reply.failed()){
                log.error("Could not reset scheduler '"+key+"'");
            }
        });
    }

    private void trigger() {
        for(final HttpRequest request: requests) {
            monitoringHandler.updateEnqueue();

            if(log.isTraceEnabled()) {
                log.trace("Triggering request "+request.toJsonObject().encodePrettily());
            }

            vertx.eventBus().send(Address.redisquesAddress(), RedisquesAPI.buildEnqueueOperation("scheduler-" + name, request.toJsonObject().encode()), new Handler<AsyncResult<Message<JsonObject>>>() {
                @Override
                public void handle(AsyncResult<Message<JsonObject>> event) {
                    if (!OK.equals(event.result().body().getString(STATUS))) {
                        log.error("Could not enqueue request "+request.toJsonObject().encodePrettily());
                    }
                }
            });
        }
    }

    private long nextRunTime() {
        return cronExpression.getNextValidTimeAfter(new Date()).getTime();
    }
}

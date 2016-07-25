package org.swisspush.gateleen.scheduler;

import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonObject;
import io.vertx.redis.RedisClient;
import org.quartz.CronExpression;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.http.HttpRequest;
import org.swisspush.gateleen.core.util.Address;
import org.swisspush.gateleen.monitoring.MonitoringHandler;
import org.swisspush.gateleen.queue.expiry.ExpiryCheckHandler;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Random;

import static org.swisspush.redisques.util.RedisquesAPI.*;

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
    private long randomOffset = 0L;
    private boolean executeOnStartup=false;
    private boolean executed = false;

    private Logger log;

    public Scheduler(Vertx vertx, RedisClient redisClient, String name, String cronExpression, List<HttpRequest> requests, MonitoringHandler monitoringHandler, int maxRandomOffset, boolean executeOnStartup) throws ParseException {
        this.vertx = vertx;
        this.redisClient = redisClient;
        this.name = name;
        this.cronExpression = new CronExpression(cronExpression);
        this.requests = requests;
        this.log = LoggerFactory.getLogger(Scheduler.class.getName()+".scheduler-"+name);
        this.monitoringHandler = monitoringHandler;
        calcRandomOffset(maxRandomOffset);
        this.executeOnStartup=executeOnStartup;
    }

    /**
     * Calculates a randomOffset (delay) before a scheduler
     * is triggered. The possible offset range is limited bx the
     * maxRandomOffeset. If the maxRandomOffset is 0, no calculation
     * is performed.
     *
     * @param maxRandomOffset possible offset range in seconds
     */
    private void calcRandomOffset(int maxRandomOffset) {
        // only calc randomOffset if maxRandomOffset is set
        if ( maxRandomOffset != 0 ) {
            // offset between 0 and maxRandomOffset (from seconds to miliseconds)
            randomOffset = new Random().nextInt(maxRandomOffset + 1) * 1000L;
        }
    }

    public void start() {
        log.info("Starting scheduler [ "+cronExpression.getCronExpression()+" ]");
        timer = vertx.setPeriodic(5000, timer -> redisClient.get("schedulers:" + name, reply -> {
            final String stringValue = reply.result();

            /*
                To guarantee that a scheduler is always triggered after the same interval,
                we have to subtract the randomOffset from the current time. This way we don’t
                change the behavior of the scheduler, because we simply “adjust” the current time.
             */
            if (stringValue == null || Long.parseLong(stringValue) <= ( System.currentTimeMillis() - randomOffset) ) {
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
            if(!executed && executeOnStartup ){
                executed = true;
                vertx.setTimer((randomOffset + 1) * 1000, new Handler<Long>() {
                    @Override
                    public void handle(Long aLong) {
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

            if ( request.getHeaders() != null ) {
                request.getHeaders().remove(ExpiryCheckHandler.SERVER_TIMESTAMP_HEADER);
            }

            ExpiryCheckHandler.updateServerTimestampHeader(request);

            vertx.eventBus().send(Address.redisquesAddress(), buildEnqueueOperation("scheduler-" + name, request.toJsonObject().encode()), new Handler<AsyncResult<Message<JsonObject>>>() {
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
        /*
            To not increase the interval, we also have to adapt the
            current time, by subtracting the randomOffset. Otherwise
            the interval will be increased by the randomOffset.
         */
        return cronExpression.getNextValidTimeAfter(new Date(System.currentTimeMillis() - randomOffset)).getTime();
    }

    /**
     * Returns the name of the scheduler.
     *
     * @return name
     */
    protected String getName() {
        return name;
    }

    /**
     * Returns the calculated random offset
     * for this scheduler.
     *
     * @return randomOffset
     */
    protected long getRandomOffset() {
        return randomOffset;
    }
}

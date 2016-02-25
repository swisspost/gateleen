package org.swisspush.gateleen.core.control;

import org.swisspush.gateleen.core.monitoring.MonitoringHandler;
import org.swisspush.gateleen.core.util.Address;
import org.swisspush.gateleen.core.util.RedisUtils;
import io.vertx.core.AsyncResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonObject;

/**
 * Implementation of the ResetMetricsMBean using the vertx eventbus
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class ResetMetrics implements ResetMetricsMBean {

    private Vertx vertx;
    private String prefix;

    private static Logger log = LoggerFactory.getLogger(ResetMetrics.class);

    public ResetMetrics(Vertx vertx, String prefix){
        this.vertx = vertx;
        this.prefix = prefix;
    }

    @Override
    public void resetLastUsedQueueSizeInformation() {
        removeMetric(prefix+MonitoringHandler.LAST_USED_QUEUE_SIZE_METRIC);
    }

    @Override
    public void resetRequestsFromClientsToCrushCount() {
        removeMetric(prefix+MonitoringHandler.REQUESTS_CLIENT_NAME);
    }

    @Override
    public void resetRequestsFromCrushToBackendsCount() {
        removeMetric(prefix+MonitoringHandler.REQUESTS_BACKENDS_NAME);
    }

    @Override public void resetMetricByName(String mBeanName) {
        removeMetric(mBeanName);
    }

    private void removeMetric(final String metric){
        log.debug("About to reset '" + metric + "' (triggered by an operation from MBean)");
        vertx.eventBus().send(Address.monitoringAddress(), new JsonObject().put("name", metric).put("action", "remove"), new Handler<AsyncResult<Message<JsonObject>>>() {
            @Override
            public void handle(AsyncResult<Message<JsonObject>> reply) {
                if (!RedisUtils.STATUS_OK.equals(reply.result().body().getString(RedisUtils.REPLY_STATUS))) {
                    log.error("Removing value for metric '" + metric + "' resulted in status '" + reply.result().body().getString(RedisUtils.REPLY_STATUS) + "'. Message: " + reply.result().body().getString(RedisUtils.REPLY_MESSAGE));
                } else {
                    log.debug("Value for metric '" + metric + "' successfully removed");
                }
            }
        });
    }
}

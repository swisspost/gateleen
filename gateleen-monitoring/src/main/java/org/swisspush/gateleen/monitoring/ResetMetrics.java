package org.swisspush.gateleen.monitoring;

import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.util.RedisUtils;

/**
 * Implementation of the ResetMetricsMBean using the vertx eventbus
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class ResetMetrics implements ResetMetricsMBean {

    private Vertx vertx;
    private String prefix;
    private String monitoringAddress;

    private static Logger log = LoggerFactory.getLogger(ResetMetrics.class);

    public ResetMetrics(Vertx vertx, String prefix, String monitoringAddress){
        this.vertx = vertx;
        this.prefix = prefix;
        this.monitoringAddress = monitoringAddress;
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
        vertx.eventBus().send(monitoringAddress, new JsonObject().put("name", metric).put("action", "remove"), (Handler<AsyncResult<Message<JsonObject>>>) reply -> {
            if (!RedisUtils.STATUS_OK.equals(reply.result().body().getString(RedisUtils.REPLY_STATUS))) {
                log.error("Removing value for metric '" + metric + "' resulted in status '" + reply.result().body().getString(RedisUtils.REPLY_STATUS) + "'. Message: " + reply.result().body().getString(RedisUtils.REPLY_MESSAGE));
            } else {
                log.debug("Value for metric '" + metric + "' successfully removed");
            }
        });
    }
}

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
        log.debug("About to reset '{}' (triggered by an operation from MBean) by sending message to monitoring address '{}'", metric, monitoringAddress);
        vertx.eventBus().request(monitoringAddress, new JsonObject().put("name", metric).put("action", "remove"), (Handler<AsyncResult<Message<JsonObject>>>) reply -> {
            if(reply.failed()){
             log.error("Failed to remove value for metric '{}'. Cause: {}", metric, reply.cause().getMessage(), reply.cause());
            } else {
                if (!RedisUtils.STATUS_OK.equals(reply.result().body().getString(RedisUtils.REPLY_STATUS))) {
                    log.error("Removing value for metric '{}' resulted in status '{}'. Message: {}", metric,
                            reply.result().body().getString(RedisUtils.REPLY_STATUS),
                            reply.result().body().getString(RedisUtils.REPLY_MESSAGE));
                } else {
                    log.debug("Value for metric '{}' successfully removed", metric);
                }
            }
        });
    }
}

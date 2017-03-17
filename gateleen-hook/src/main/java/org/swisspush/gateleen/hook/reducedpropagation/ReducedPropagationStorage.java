package org.swisspush.gateleen.hook.reducedpropagation;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import java.util.List;

/**
 * Provides storage access to the reduced propagation feature related data.
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public interface ReducedPropagationStorage {

    Future<List<String>> removeExpiredQueues(long currentTS);

    Future<Boolean> addQueue(String queue, long expireTS);

    Future<Void> storeQueueRequest(String queue, JsonObject queueRequest);

    Future<Void> removeQueueRequest(String queue);

    Future<JsonObject> getQueueRequest(String queue);
}

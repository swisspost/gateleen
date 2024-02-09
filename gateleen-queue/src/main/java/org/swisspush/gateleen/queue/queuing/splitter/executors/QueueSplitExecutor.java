package org.swisspush.gateleen.queue.queuing.splitter.executors;

import io.vertx.core.http.HttpServerRequest;

public interface QueueSplitExecutor {

    boolean matches(String queue);

    String executeSplit(String queue, HttpServerRequest request);
}

package org.swisspush.gateleen.queue.queuing.splitter.executors;

import io.vertx.core.http.HttpServerRequest;
import org.swisspush.gateleen.queue.queuing.splitter.QueueSplitterConfiguration;

import java.util.concurrent.atomic.AtomicInteger;

public class QueueSplitExecutorFromList extends QueueSplitExecutorBase {

    AtomicInteger atomicInteger = new AtomicInteger(0);

    public QueueSplitExecutorFromList(QueueSplitterConfiguration configuration) {
        super(configuration);
    }

    @Override
    public String executeSplit(String queue, HttpServerRequest request) {
        StringBuilder stringBuilder = new StringBuilder(queue);
        if (matches(queue)) {
            stringBuilder.append(configuration.getPostfixDelimiter());
            stringBuilder.append(configuration.getPostfixFromStatic().get(
                    atomicInteger.getAndAccumulate(
                            1,
                            (left, right) -> (left + right) % configuration.getPostfixFromStatic().size()
                    )
            ));
        }
        return stringBuilder.toString();
    }
}

package org.swisspush.gateleen.queue.queuing.splitter.executors;

import org.swisspush.gateleen.queue.queuing.splitter.QueueSplitterConfiguration;

public class DynamicQueueSplitExecutor extends QueueSplitExecutorFromStaticList {

    public DynamicQueueSplitExecutor(QueueSplitterConfiguration configuration) {
        super(configuration);
    }

    public QueueSplitterConfiguration getConfiguration() {
        return configuration;
    }
}

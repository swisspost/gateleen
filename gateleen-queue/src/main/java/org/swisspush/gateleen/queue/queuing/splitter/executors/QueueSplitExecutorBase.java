package org.swisspush.gateleen.queue.queuing.splitter.executors;

import org.swisspush.gateleen.queue.queuing.splitter.QueueSplitterConfiguration;

public abstract class QueueSplitExecutorBase implements QueueSplitExecutor {

    protected final QueueSplitterConfiguration configuration;

    protected QueueSplitExecutorBase(QueueSplitterConfiguration configuration) {
        this.configuration = configuration;
    }

    @Override
    public boolean matches(String queue) {
        return configuration.getQueue().matcher(queue).matches();
    }
}

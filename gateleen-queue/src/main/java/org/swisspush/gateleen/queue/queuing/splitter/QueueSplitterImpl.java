package org.swisspush.gateleen.queue.queuing.splitter;

import io.vertx.core.buffer.Buffer;
import org.swisspush.gateleen.core.configuration.ConfigurationResourceConsumer;
import org.swisspush.gateleen.core.configuration.ConfigurationResourceManager;

/**
 * {@inheritDoc}
 */
public class QueueSplitterImpl extends ConfigurationResourceConsumer implements QueueSplitter {

    public QueueSplitterImpl(
            ConfigurationResourceManager configurationResourceManager,
            String configResourceUri
    ) {
        this(configurationResourceManager, configResourceUri, "gateleen_queue_splitter_configuration_schema");
    }

    public QueueSplitterImpl(
            ConfigurationResourceManager configurationResourceManager,
            String configResourceUri,
            String schemaResourceName
    ) {
        super(configurationResourceManager, configResourceUri, schemaResourceName);
    }

    @Override
    public void resourceChanged(String resourceUri, Buffer resource) {

    }

    @Override
    public void resourceRemoved(String resourceUri) {

    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String convertToSubQueue(final String queue) {
        return queue + "-1";
    }
}

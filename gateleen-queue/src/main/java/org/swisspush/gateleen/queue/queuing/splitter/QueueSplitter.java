package org.swisspush.gateleen.queue.queuing.splitter;

import io.vertx.core.buffer.Buffer;
import org.swisspush.gateleen.core.configuration.ConfigurationResourceConsumer;
import org.swisspush.gateleen.core.configuration.ConfigurationResourceManager;

/**
 * Class for queues configured to be split in sub-queues.
 *
 * @author https://github.com/gcastaldi [Giannandrea Castaldi]
 */
public class QueueSplitter extends ConfigurationResourceConsumer {

    public QueueSplitter(
            ConfigurationResourceManager configurationResourceManager,
            String configResourceUri
    ) {
        this(configurationResourceManager, configResourceUri, "gateleen_queue_splitter_configuration_schema");
    }

    public QueueSplitter(
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

    public String handle(final String queue) {
        return queue + "-1";
    }
}

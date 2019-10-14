package org.swisspush.gateleen.core.configuration;

import io.vertx.core.buffer.Buffer;

/**
 * Interface for classes interested in configuration resources
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public interface ConfigurationResourceObserver {

    /**
     * Gets called when the resource with uri <code>resourceUri</code> has been changed.
     * The changed resource is provided in the <code>resource</code> parameter.
     *
     * @param resourceUri the uri of the resource that has been changed
     * @param resource the resource including the changes
     */
    void resourceChanged(String resourceUri, Buffer resource);

    /**
     * Gets called when the resource with uri <code>resourceUri</code> has been removed.
     *
     * @param resourceUri the uri of the resource that has been removed
     */
    void resourceRemoved(String resourceUri);
}

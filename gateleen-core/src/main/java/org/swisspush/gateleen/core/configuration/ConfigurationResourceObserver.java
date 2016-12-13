package org.swisspush.gateleen.core.configuration;

/**
 * Interface for classes interested in configuration resources
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public interface ConfigurationResourceObserver {
    void resourceChanged(String resourceUri, String resource);
    void resourceResetted(String resourceUri);
}

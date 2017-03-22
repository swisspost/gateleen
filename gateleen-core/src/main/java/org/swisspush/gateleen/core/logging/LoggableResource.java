package org.swisspush.gateleen.core.logging;

import org.swisspush.gateleen.core.util.Address;

/**
 * Interface for configuration resource manager classes to enable or disable the logging of resource changes.
 * Typically this is used in combination with the {@link RequestLogger}
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public interface LoggableResource {

    /**
     * Enable or disable the resource logging. When enabling the resource logging, make sure to have a consumer for
     * event bus events to the address {@link Address#requestLoggingConsumerAddress()} like the RequestLoggingConsumer class
     * from gateleen-logging module.
     *
     * @param resourceLoggingEnabled boolean value whether to enable or disable the resource logging
     */
    void enableResourceLogging(boolean resourceLoggingEnabled);
}

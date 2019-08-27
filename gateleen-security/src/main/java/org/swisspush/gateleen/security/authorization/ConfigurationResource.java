package org.swisspush.gateleen.security.authorization;

import org.swisspush.gateleen.validation.ValidationException;
import io.vertx.core.buffer.Buffer;

/**
 * Interface declaring the method to be commonly used for role related resource configuration
 */
interface ConfigurationResource {

    /**
     * Check if the given resource in the buffer is valid
     *
     * @param buffer The resource to be checked
     * @throws ValidationException Thrown if the validation fails
     */
    void checkConfigResource(Buffer buffer) throws ValidationException;

    /**
     * Reload the Configuration resources, they might have changed in the system
     */
    void configUpdate();


}

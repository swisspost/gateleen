package org.swisspush.gateleen.core.util;

import com.google.common.base.Charsets;
import com.google.common.io.Resources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;

/**
 * <p>
 * Utility class providing handy methods to deal with Resources.
 * </p>
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class ResourcesUtils {

    private static Logger log = LoggerFactory.getLogger(ResourcesUtils.class);

    private ResourcesUtils() {
        // prevent instantiation
    }

    /**
     * <p>
     * Loads the resource with the provided name from the classpath. When param {@code exceptionWhenNotFound}
     * set to true, a {@link RuntimeException} is thrown when the resource cannot be loaded.
     * </p>
     *
     * @param resourceName the name of the resource to load
     * @param exceptionWhenNotFound throw a {@link RuntimeException} when the resource could not be loaded
     * @throws RuntimeException when {@code exceptionWhenNotFound} is set to true and resource cannot be loaded
     * @return The content of the resource or null
     */
    public static String loadResource(String resourceName, boolean exceptionWhenNotFound) {
        try {
            URL url = Resources.getResource(resourceName);
            return Resources.toString(url, Charsets.UTF_8);
        } catch (Exception e) {
            log.error("Error loading resource '"+resourceName+"'", e);
            if(exceptionWhenNotFound){
                throw new RuntimeException("Error loading required resource '"+resourceName+"'");
            }
            return null;
        }
    }
}

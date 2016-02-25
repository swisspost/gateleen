package org.swisspush.gateleen.hook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract base class for the hook repositories.
 * 
 * @author https://github.com/ljucam [Mario Ljuca]
 */
public abstract class RouteRepositoryBase<T> implements RouteRepository {
    private static final Logger LOG = LoggerFactory.getLogger(RouteRepositoryBase.class);

    /**
     * Searches in the given container for the first matching
     * key (url pattern) for the given url.<br />
     * The search is performend up - down (first /a/b/c/d, ..., last /a ).
     * 
     * @see {@link #containsKey(Object, String)}
     * @param url
     * @param container
     * @return the key
     */
    String findFirstMatchingKey(T container, String url) {
        // get rid of url parameters (if there are any)
        String tUrl = url.contains("?") ? url.substring(0, url.indexOf('?')) : url;

        int index = -1;
        while ((index = tUrl.lastIndexOf('/')) > -1) {

            // found
            if (containsKey(container, tUrl)) {
                return tUrl;
            }

            tUrl = tUrl.substring(0, index);
        }

        // nothing found or root route
        return url.startsWith("/") && containsKey(container, "/") ? "/" : null;
    }

    /**
     * Cleanup the given route.
     * 
     * @param route
     */
    void cleanupRoute(Route route) {
        LOG.debug("Route for cleanup available? " + (route != null));

        if (route != null) {
            route.cleanup();
        }
    }

    /**
     * Checks if the container contains the given key. <br />
     * Returns only true if the key is found. <br />
     * <br />
     * This method allows you to create your own implementation
     * depending on your needs.
     * 
     * @see {@link #findFirstMatchingKey(Object, String)}
     * @param container
     * @param key - you can use this variable for key or value, depending what you implement
     * @return true if value is found.
     */
    abstract boolean containsKey(T container, String key);

}

package org.swisspush.gateleen.hook;

import java.util.Map;

/**
 * A repository for routes of hooks.
 * 
 * @author https://github.com/ljucam [Mario Ljuca]
 */
public interface RouteRepository {

    /**
     * Adds a route for the given url pattern.
     * 
     * @param urlPattern - is used as a key.
     * @param route route
     */
    void addRoute(String urlPattern, Route route);

    /**
     * Removes the route for the url pattern.
     * 
     * @param urlPattern urlPattern
     */
    void removeRoute(String urlPattern);

    /**
     * Returns the route which is foreseen
     * for the given url.
     * The repository checks if a url pattern is
     * present for this url and then returns
     * the corresponding route.
     * If nothing is found, null is returned.
     * 
     * @param url url
     * @return a route or null if no route is found
     */
    Route getRoute(String url);

    /**
     * Returns a copy of all routes.
     * 
     * @return a copy of all routes
     */
    Map<String, Route> getRoutes();
}

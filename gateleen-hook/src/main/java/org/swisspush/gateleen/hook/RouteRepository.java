package org.swisspush.gateleen.hook;

import java.util.Map;
import java.util.Set;

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

    /**
     * Returns a set with collections (routes) contained in the
     * given parent. Only routes (collections) directly resided in
     * the given parent will be returned. If no route
     * could be found an empty set will be returned.
     *
     * @param parentUri the parent of which the routes should be listed
     * @return a set with routes
     */
    Set<String> getCollections(String parentUri);
}

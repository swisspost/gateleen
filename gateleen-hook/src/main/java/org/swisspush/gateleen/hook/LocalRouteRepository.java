package org.swisspush.gateleen.hook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Local in-memory implementation of a RouteRepository.
 * 
 * @author https://github.com/ljucam [Mario Ljuca]
 */
public class LocalRouteRepository extends RouteRepositoryBase<Map<String, Route>>implements RouteRepository {
    private Logger log = LoggerFactory.getLogger(LocalRouteRepository.class);

    private Map<String, Route> routes;

    /**
     * Creates a new instance of a local in-memory HookRouteRepository.
     */
    public LocalRouteRepository() {
        routes = new ConcurrentHashMap<>();
    }

    @Override
    public void addRoute(String urlPattern, Route route) {
        log.debug("Creating route for url pattern {} and route destination {}", urlPattern, route.getHook().getDestination());

        /*
         * Because we perform a cleanup before
         * adding a new route, we may encounter
         * the problem, that if we want to create
         * a sub route, we could find the parent
         * route, because the child route doesn't
         * exist yet!
         * To solve this problem, we do not use
         * the methode <code>getRoute(urlPattern)</code>.
         * Instead we directly check, if a route
         * exists for this pattern.
         */
        cleanupRoute(routes.get(urlPattern));

        routes.put(urlPattern, route);
    }

    @Override
    public void removeRoute(String urlPattern) {
        log.debug("Removing route for url pattern {}", urlPattern);

        String routeKey = findFirstMatchingKey(routes, urlPattern);

        if (routeKey != null) {
            cleanupRoute(getRoute(routeKey));
            routes.remove(routeKey);
        }
    }

    @Override
    public Route getRoute(String url) {
        String key = findFirstMatchingKey(routes, url);
        return key != null ? routes.get(key) : null;
    }

    @Override
    boolean containsKey(Map<String, Route> container, String key) {
        return container.containsKey(key);
    }

    @Override
    public Map<String, Route> getRoutes() {
        return new HashMap<>(routes);
    }

    @Override
    public Set<String> getCollections(String parentUri) {
        // get rid of url parameters (if there are any)
        parentUri = parentUri.contains("?") ? parentUri.substring(0, parentUri.indexOf('?')) : parentUri;

        // add trailing '/'
        parentUri = ! parentUri.endsWith("/") ? parentUri + "/" : parentUri;

        // make it final for compiler (lambda)
        final String parent =  parentUri;

        /*  Example:
            parentUri   = /gateleen/server/v1/test
            route1      = /gateleen/server/v1/test/route1
            route2      = /gateleen/server/v1/test/route2/subroute
            route3      = /gateleen/server/v1/test/route3
            !!! A stored route never ends with '/'.
            !!! Incoming parent is prepared to have a trailing '/'

            Listed:
                > route1
                > route3

            Check:
                > route is collection
                > route is listable
                > does a route start with parent?
                > does a route have subroutes?
                    => subroutes only exists, if a '/' can be found
         */

        return routes.entrySet().stream()
                .filter( entry -> entry.getValue().getHook().isCollection()
                        && entry.getValue().getHook().isListable()
                        && entry.getKey().startsWith(parent)
                        && ! entry.getKey().substring(parent.length()).contains("/")
                )
                .map( (Map.Entry<String, Route> entry) -> entry.getKey().substring(parent.length()) + "/")
                .collect(Collectors.toSet());
    }
}

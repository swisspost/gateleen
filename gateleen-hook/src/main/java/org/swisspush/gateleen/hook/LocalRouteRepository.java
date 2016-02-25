package org.swisspush.gateleen.hook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

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
        routes = new HashMap<String, Route>();
    }

    @Override
    public void addRoute(String urlPattern, Route route) {
        log.debug("Creating route for url pattern " + urlPattern + " and route destination " + route.getHook().getDestination());

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
        log.debug("Removing route for url pattern " + urlPattern);

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
        return new HashMap<String, Route>(routes);
    }
}

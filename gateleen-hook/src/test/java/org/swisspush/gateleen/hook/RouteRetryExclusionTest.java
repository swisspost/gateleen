package org.swisspush.gateleen.hook;

import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.swisspush.gateleen.core.storage.MockResourceStorage;
import org.swisspush.gateleen.logging.LogAppenderRepository;
import org.swisspush.gateleen.logging.LoggingResourceManager;
import org.swisspush.gateleen.routing.ForwarderRetryPolicy;

import static org.mockito.Mockito.mock;

/**
 * Verifies that the rule a hook {@link Route} builds for its forwarder is complete enough for
 * {@link ForwarderRetryPolicy#RETRY_EXCLUDE_PATTERN_PROPERTY}: hook routes are excluded by their
 * URL pattern or by their destination URL (including the default port), like routing rules.
 */
@RunWith(VertxUnitRunner.class)
public class RouteRetryExclusionTest {

    private static final String HOOKED_PATTERN = "/eagle/houston/v1/routes/(.*)";

    private Vertx vertx;
    private String previousStatuses;
    private String previousExclude;

    /** Creates a Vert.x instance and configures a static policy of two retries on 502. */
    @Before
    public void setUp() {
        vertx = Vertx.vertx();
        previousStatuses = System.getProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY);
        previousExclude = System.getProperty(ForwarderRetryPolicy.RETRY_EXCLUDE_PATTERN_PROPERTY);
        System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY, "{\"502\":2}");
    }

    /**
     * Restores the system properties and closes Vert.x.
     *
     * @param ctx Vert.x unit context used to await the shutdown
     */
    @After
    public void tearDown(TestContext ctx) {
        restoreProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY, previousStatuses);
        restoreProperty(ForwarderRetryPolicy.RETRY_EXCLUDE_PATTERN_PROPERTY, previousExclude);
        vertx.close(ctx.asyncAssertSuccess());
    }

    /**
     * A hook route whose URL pattern matches is excluded; another hook route is not.
     */
    @Test
    public void testHookRouteExcludedByUrlPattern(TestContext ctx) {
        System.setProperty(ForwarderRetryPolicy.RETRY_EXCLUDE_PATTERN_PROPERTY, "/eagle/houston/.*");
        ctx.assertEquals(1L, attemptsOn502(createRoute(HOOKED_PATTERN, "http://backend:7013/target")));
        ctx.assertEquals(3L, attemptsOn502(createRoute("/eagle/other/(.*)", "http://backend:7013/target")));
    }

    /**
     * A hook route whose destination matches is excluded; the destination is matched with the
     * scheme's default port when it has none.
     */
    @Test
    public void testHookRouteExcludedByDestination(TestContext ctx) {
        System.setProperty(ForwarderRetryPolicy.RETRY_EXCLUDE_PATTERN_PROPERTY, "http://mobile-backend:80/.*");
        ctx.assertEquals(1L, attemptsOn502(createRoute("/eagle/a/(.*)", "http://mobile-backend/target")));
        ctx.assertEquals(3L, attemptsOn502(createRoute("/eagle/a/(.*)", "http://backend/target")));
    }

    /**
     * Creates a route the way {@code HookHandler.createRoute} does and schedules its cleanup.
     *
     * @param urlPattern  the route's URL pattern
     * @param destination the hook's destination URL
     * @return the route
     */
    private Route createRoute(String urlPattern, String destination) {
        Route route = new Route(vertx, new MockResourceStorage(), mock(LoggingResourceManager.class),
                mock(LogAppenderRepository.class), null, "/users/v1/%s/profile",
                new HttpHook(destination), urlPattern, mock(HttpClient.class), urlPattern);
        route.cleanup();
        return route;
    }

    /**
     * Resolves the policy the route's forwarder uses and returns its attempts for a 502 without
     * retry headers.
     *
     * @param route the route
     * @return 1 if the route is excluded from the static policy, 3 otherwise
     */
    private static long attemptsOn502(Route route) {
        return ForwarderRetryPolicy.fromSystemProperties(route.getRule())
                .maxAttemptsForResponse(MultiMap.caseInsensitiveMultiMap(), 502, null);
    }

    /**
     * Restores or clears one system property after a test.
     *
     * @param name          property name to restore
     * @param previousValue previous value, or {@code null} when the property was absent
     */
    private static void restoreProperty(String name, String previousValue) {
        if (previousValue == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, previousValue);
        }
    }
}

package org.swisspush.gateleen.hook;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import org.swisspush.gateleen.routing.AbstractForwarder;
import org.swisspush.gateleen.routing.Forwarder;

import static org.mockito.Mockito.mock;

/**
 * Verifies that the forwarding outcome counters of hook routes, which are tagged with the hook's
 * own URL pattern, do not outlive the hook: they are removed from the registry when the route is
 * cleaned up, but survive the replacement of a route by a refreshed registration.
 */
@RunWith(VertxUnitRunner.class)
public class RouteMetricsTest {

    private static final String LISTENER_PATTERN = "/hookRootURI/listeners/http/listener-1";
    private static final String OTHER_LISTENER_PATTERN = "/hookRootURI/listeners/http/listener-2";
    private static final String[] COUNTERS = {
            Forwarder.FORWARDED_REQUESTS_METRIC_NAME,
            Forwarder.FORWARDED_RETRIES_METRIC_NAME,
            Forwarder.FORWARDED_FAILURES_METRIC_NAME
    };

    private Vertx vertx;
    private SimpleMeterRegistry registry;

    /** Creates a Vert.x instance and an empty meter registry. */
    @Before
    public void setUp() {
        vertx = Vertx.vertx();
        registry = new SimpleMeterRegistry();
    }

    /**
     * Closes the registry and Vert.x.
     *
     * @param ctx Vert.x unit context used to await the shutdown
     */
    @After
    public void tearDown(TestContext ctx) {
        registry.close();
        vertx.close(ctx.asyncAssertSuccess());
    }

    /**
     * Registers a listener route and asserts its three outcome counters are tagged with the
     * listener's URL pattern and destination, and removed from the registry by {@code cleanup()}.
     */
    @Test
    public void testCleanupRemovesOutcomeCountersOfRoute(TestContext ctx) {
        Route route = createRoute(LISTENER_PATTERN);
        for (String name : COUNTERS) {
            Counter counter = find(name, LISTENER_PATTERN);
            ctx.assertNotNull(counter, name + " must be registered");
            ctx.assertEquals("localhost:7012", counter.getId().getTag(Forwarder.FORWARDER_METRIC_TAG_TARGET));
        }

        route.cleanup();

        for (String name : COUNTERS) {
            ctx.assertNull(find(name, LISTENER_PATTERN), name + " must be removed with its route");
        }
    }

    /**
     * Replaces a route by a refreshed registration with the same URL pattern (the new route is
     * created before the old one is cleaned up, as in {@code LocalRouteRepository.addRoute}) and
     * asserts the counters, including their values, survive until the last route is cleaned up.
     */
    @Test
    public void testReplacedRouteKeepsCountersOfSuccessor(TestContext ctx) {
        Route old = createRoute(LISTENER_PATTERN);
        Counter requests = find(Forwarder.FORWARDED_REQUESTS_METRIC_NAME, LISTENER_PATTERN);
        requests.increment();
        Route successor = createRoute(LISTENER_PATTERN);

        old.cleanup();

        Counter afterReplacement = find(Forwarder.FORWARDED_REQUESTS_METRIC_NAME, LISTENER_PATTERN);
        ctx.assertTrue(afterReplacement == requests, "The successor must keep using the same counter");
        ctx.assertEquals(1d, afterReplacement.count());

        successor.cleanup();
        ctx.assertNull(find(Forwarder.FORWARDED_REQUESTS_METRIC_NAME, LISTENER_PATTERN));
    }

    /**
     * Cleans up one of two routes with different URL patterns and asserts only its counters are
     * removed; repeated cleanup has no further effect.
     */
    @Test
    public void testCleanupOnlyRemovesOwnCounters(TestContext ctx) {
        Route first = createRoute(LISTENER_PATTERN);
        createRoute(OTHER_LISTENER_PATTERN);

        first.cleanup();
        first.cleanup();

        for (String name : COUNTERS) {
            ctx.assertNull(find(name, LISTENER_PATTERN));
            ctx.assertNotNull(find(name, OTHER_LISTENER_PATTERN), name + " of the other route must remain");
        }
    }

    /**
     * Creates a route the way {@code HookHandler.createRoute} does, with the test registry.
     *
     * @param urlPattern the route's URL pattern
     * @return the route
     */
    private Route createRoute(String urlPattern) {
        Route route = new Route(vertx, new MockResourceStorage(), mock(LoggingResourceManager.class),
                mock(LogAppenderRepository.class), null, "/users/v1/%s/profile",
                new HttpHook("http://localhost:7012/target"), urlPattern, mock(HttpClient.class), urlPattern);
        route.setMeterRegistry(registry);
        return route;
    }

    /**
     * Looks up an outcome counter of the given route.
     *
     * @param name       the counter name
     * @param urlPattern the route's URL pattern ({@code rule} tag)
     * @return the counter, or {@code null} if not registered
     */
    private Counter find(String name, String urlPattern) {
        return registry.find(name)
                .tag(AbstractForwarder.FORWARDER_METRIC_TAG_METRICNAME, AbstractForwarder.FORWARDER_NO_METRICNAME)
                .tag(Forwarder.FORWARDER_METRIC_TAG_RULE, urlPattern)
                .counter();
    }
}

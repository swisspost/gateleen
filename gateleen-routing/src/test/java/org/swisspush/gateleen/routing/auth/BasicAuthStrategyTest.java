package org.swisspush.gateleen.routing.auth;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.swisspush.gateleen.core.http.LocalHttpClientRequest;
import org.swisspush.gateleen.routing.Rule;

import static org.mockito.Mockito.mock;

/**
 * Tests for the {@link BasicAuthStrategy} class
 */
@RunWith(VertxUnitRunner.class)
public class BasicAuthStrategyTest {

    private static final String AUTHORIZATION = "Authorization";
    private BasicAuthStrategy authStrategy;
    private Vertx vertx;
    private Rule rule;
    private LocalHttpClientRequest request;

    @Before
    public void setUp() {
        authStrategy = new BasicAuthStrategy();
        rule = new Rule();
        vertx = mock(Vertx.class);
        request = new LocalHttpClientRequest(HttpMethod.GET, "/some/uri", vertx, null, null);
    }

    @Test
    public void testBasicAuthHeaderAdded(TestContext context) {
        rule.setBasicAuthUsername("foo");
        rule.setBasicAuthPassword("bar");

        // no Authorization header before authentication
        context.assertFalse(request.headers().contains(AUTHORIZATION));

        authStrategy.authenticate(request, rule);

        // Authorization header after authentication
        context.assertTrue(request.headers().contains(AUTHORIZATION));
        context.assertEquals("Basic Zm9vOmJhcg==", request.headers().get(AUTHORIZATION));
    }

    @Test
    public void testMissingBasicAuthProperties(TestContext context) {
        // no Authorization header before authentication
        context.assertFalse(request.headers().contains(AUTHORIZATION));

        authStrategy.authenticate(request, rule);

        // no Authorization header after authentication because of missing basic auth properties
        context.assertFalse(request.headers().contains(AUTHORIZATION));
    }
}

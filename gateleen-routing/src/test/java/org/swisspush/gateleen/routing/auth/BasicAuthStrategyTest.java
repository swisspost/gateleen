package org.swisspush.gateleen.routing.auth;

import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.swisspush.gateleen.routing.Rule;

/**
 * Tests for the {@link BasicAuthStrategy} class
 */
@RunWith(VertxUnitRunner.class)
public class BasicAuthStrategyTest {

    private static final String AUTHORIZATION = "Authorization";
    private BasicAuthStrategy authStrategy;
    private Rule rule;

    @Before
    public void setUp() {
        authStrategy = new BasicAuthStrategy();
        rule = new Rule();
    }

    @Test
    public void testBasicAuth(TestContext context) {
        Async async = context.async();

        rule.setBasicAuthUsername("foo");
        rule.setBasicAuthPassword("bar");

        authStrategy.authenticate(rule).onComplete(event -> {
            context.assertTrue(event.succeeded());

            context.assertEquals(AUTHORIZATION, event.result().key());
            context.assertEquals("Basic Zm9vOmJhcg==", event.result().value());

            async.complete();
        });
    }

    @Test
    public void testMissingBasicAuthProperties(TestContext context) {
        Async async = context.async();

        authStrategy.authenticate(rule).onComplete(event -> {
            context.assertFalse(event.succeeded());
            context.assertNotNull(event.cause());
            context.assertEquals("Unable to authenticate request because no basic auth credentials provided. " +
                    "This should not happen!", event.cause().getMessage());
            async.complete();
        });
    }
}

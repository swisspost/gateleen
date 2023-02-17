package org.swisspush.gateleen.routing.auth;

import io.vertx.core.Future;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.swisspush.gateleen.routing.Rule;

/**
 * Tests for the {@link BasicAuthStrategy} class
 */
@RunWith(VertxUnitRunner.class)
public class OAuthStrategyTest {

    private static final String AUTHORIZATION = "Authorization";
    private OAuthProvider oAuthProvider;
    private OAuthStrategy authStrategy;
    private Rule rule;

    @Before
    public void setUp() {
        oAuthProvider = Mockito.mock(OAuthProvider.class);
        authStrategy = new OAuthStrategy(oAuthProvider);
        rule = new Rule();
    }

    @Test
    public void testSuccessfulAuth(TestContext context) {
        Async async = context.async();
        Mockito.when(oAuthProvider.requestAccessToken(Mockito.anyString())).thenReturn(Future.succeededFuture("zzz123"));

        rule.setOAuthId("some-oauth-id");

        authStrategy.authenticate(rule).onComplete(event -> {
            context.assertTrue(event.succeeded());
            context.assertEquals(AUTHORIZATION, event.result().key());
            context.assertEquals("Bearer zzz123", event.result().value());
            async.complete();
        });
    }

    @Test
    public void testAuthError(TestContext context) {
        Async async = context.async();
        Mockito.when(oAuthProvider.requestAccessToken(Mockito.anyString())).thenReturn(Future.failedFuture("Boooom"));

        rule.setOAuthId("some-oauth-id");

        authStrategy.authenticate(rule).onComplete(event -> {
            context.assertTrue(event.failed());
            context.assertEquals("Boooom", event.cause().getMessage());
            async.complete();
        });
    }
}

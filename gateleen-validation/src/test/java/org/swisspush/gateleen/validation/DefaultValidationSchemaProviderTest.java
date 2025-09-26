package org.swisspush.gateleen.validation;

import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.impl.headers.HeadersMultiMap;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import io.vertx.ext.web.RoutingContext;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.swisspush.gateleen.core.http.ClientRequestCreator;
import org.swisspush.gateleen.core.http.LocalHttpClient;

import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.swisspush.gateleen.core.exception.GateleenExceptionFactory.newGateleenWastefulExceptionFactory;

@RunWith(VertxUnitRunner.class)
public class DefaultValidationSchemaProviderTest {

    private Vertx vertx;
    private ClientRequestCreator clientRequestCreator;
    private DefaultValidationSchemaProvider schemaProvider;

    @Before
    public void setUp(){
        vertx = Vertx.vertx();
        Supplier<Handler<RoutingContext>> getRoutingContext = () -> mock(Handler.class);
        final LocalHttpClient selfClient = new LocalHttpClient(vertx, getRoutingContext, newGateleenWastefulExceptionFactory());
        clientRequestCreator = Mockito.spy(new ClientRequestCreator(selfClient));
        schemaProvider = new DefaultValidationSchemaProvider(vertx, clientRequestCreator, Duration.ofSeconds(5));
    }

    @Test
    public void testSchemaFromLocation(TestContext context){
        schemaProvider.schemaFromLocation(new SchemaLocation("/path/to/schema", null));

        ArgumentCaptor<HeadersMultiMap> headersCaptor = ArgumentCaptor.forClass(HeadersMultiMap.class);
        verify(clientRequestCreator, times(1)).createClientRequest(
                eq(HttpMethod.GET),
                eq("/path/to/schema"),
                headersCaptor.capture(),
                anyLong(),
                any(Handler.class)
        );

        MultiMap headers = headersCaptor.getValue();
        context.assertTrue(headers.contains("Accept", "application/json", true));
        context.assertTrue(headers.contains("x-self-request", "true", true));
    }

    @Test
    public void testSchemaFromLocationWithDefaultRequestHeaders(TestContext context){
        schemaProvider = new DefaultValidationSchemaProvider(vertx, clientRequestCreator, Duration.ofSeconds(5), Map.of("foo", "bar"));
        schemaProvider.schemaFromLocation(new SchemaLocation("/path/to/schema", null));

        ArgumentCaptor<HeadersMultiMap> headersCaptor = ArgumentCaptor.forClass(HeadersMultiMap.class);
        verify(clientRequestCreator, times(1)).createClientRequest(
                eq(HttpMethod.GET),
                eq("/path/to/schema"),
                headersCaptor.capture(),
                anyLong(),
                any(Handler.class)
        );

        MultiMap headers = headersCaptor.getValue();
        context.assertTrue(headers.contains("foo", "bar", true));
        context.assertTrue(headers.contains("Accept", "application/json", true));
        context.assertTrue(headers.contains("x-self-request", "true", true));
    }
}

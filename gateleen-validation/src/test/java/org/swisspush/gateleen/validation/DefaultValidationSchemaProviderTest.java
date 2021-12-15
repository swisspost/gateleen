package org.swisspush.gateleen.validation;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.swisspush.gateleen.core.http.ClientRequestCreator;
import org.swisspush.gateleen.core.http.LocalHttpClient;

import java.time.Duration;

import static org.mockito.Matchers.*;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@RunWith(VertxUnitRunner.class)
public class DefaultValidationSchemaProviderTest {

    private Vertx vertx;
    private ClientRequestCreator clientRequestCreator;
    private DefaultValidationSchemaProvider schemaProvider;

    @Before
    public void setUp(){
        vertx = Vertx.vertx();
        final LocalHttpClient selfClient = new LocalHttpClient(vertx);
        selfClient.setRoutingContexttHandler(event -> {});
        clientRequestCreator = Mockito.spy(new ClientRequestCreator(selfClient));
        schemaProvider = new DefaultValidationSchemaProvider(vertx, clientRequestCreator, Duration.ofSeconds(5));
    }

    @Test
    public void testSchemaFromLocation(TestContext context){
        schemaProvider.schemaFromLocation(new SchemaLocation("/path/to/schema", null));

        verify(clientRequestCreator, times(1)).createClientRequest(
                eq(HttpMethod.GET),
                eq("/path/to/schema"),
                any(),
                anyLong(),
                any(Handler.class),
                any(Handler.class)
        );
    }
}

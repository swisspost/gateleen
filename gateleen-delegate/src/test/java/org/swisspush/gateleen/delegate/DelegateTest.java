package org.swisspush.gateleen.delegate;

import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.http.CaseInsensitiveHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.impl.headers.VertxHttpHeaders;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.swisspush.gateleen.core.http.DummyHttpServerRequest;
import org.swisspush.gateleen.core.http.LocalHttpClient;
import org.swisspush.gateleen.validation.ValidationException;

import java.util.HashMap;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.*;
import static org.swisspush.gateleen.core.util.ResourcesUtils.loadResource;

/**
 * Tests for the {@link Delegate} class
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class DelegateTest {

    private final String VALID_HEADER_DEFINITON_DELEGATE = loadResource("valid_header_definition_delegate", true);

    private String delegatesSchema = loadResource("gateleen_delegate_schema_delegates", true);

    private DelegateClientRequestCreator delegateClientRequestCreator;
    private DelegateFactory delegateFactory;


    @Before
    public void setUp() {
        Vertx vertx = Mockito.mock(Vertx.class);
        Mockito.when(vertx.eventBus()).thenReturn(Mockito.mock(EventBus.class));
        final LocalHttpClient selfClient = new LocalHttpClient(vertx);
        selfClient.setRoutingContexttHandler(event -> {});
        delegateClientRequestCreator = Mockito.spy(new DelegateClientRequestCreator(selfClient));
        delegateFactory = new DelegateFactory(delegateClientRequestCreator, new HashMap<>(), delegatesSchema);
    }

    @Test
    public void testWithDefinedHeaders(TestContext context) throws ValidationException {
        Delegate delegate = delegateFactory.parseDelegate("someDelegate",
                Buffer.buffer(VALID_HEADER_DEFINITON_DELEGATE));

        CustomHttpServerRequest request = new CustomHttpServerRequest("/gateleen/playground/foobar", HttpMethod.PUT,
                new CaseInsensitiveHeaders());

        delegate.handle(request);

        ArgumentCaptor<VertxHttpHeaders> headersArgumentCaptor = ArgumentCaptor.forClass(VertxHttpHeaders.class);

        verify(delegateClientRequestCreator, times(1)).createClientRequest(
                eq(HttpMethod.POST),
                eq("/gateleen/server/v1/copy"),
                headersArgumentCaptor.capture(),
                anyLong(),
                any(Handler.class),
                any(Handler.class)
        );

        VertxHttpHeaders delegateRequestHeaders = headersArgumentCaptor.getValue();

        context.assertNotNull(delegateRequestHeaders);
        context.assertEquals(2, delegateRequestHeaders.size());
        context.assertEquals("bar", delegateRequestHeaders.get("x-foo"));
        context.assertEquals("helloworld", delegateRequestHeaders.get("x-test"));
    }

    private static class CustomHttpServerRequest extends DummyHttpServerRequest {

        private final String uri;
        private final HttpMethod method;
        private final MultiMap headers;

        public CustomHttpServerRequest(String uri, HttpMethod method, MultiMap headers) {
            this.uri = uri;
            this.method = method;
            this.headers = headers;
        }

        @Override public HttpMethod method() {
            return method;
        }
        @Override public String uri() {
            return uri;
        }
        @Override public MultiMap headers() { return headers; }

    }
}

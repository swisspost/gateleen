package org.swisspush.gateleen.packing;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.*;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.swisspush.gateleen.core.exception.GateleenExceptionFactory;
import org.swisspush.gateleen.core.util.StatusCode;
import org.swisspush.gateleen.core.validation.ValidationResult;
import org.swisspush.gateleen.core.validation.ValidationStatus;
import org.swisspush.gateleen.packing.validation.PackingValidator;

import static org.mockito.Mockito.*;
import static org.swisspush.gateleen.packing.PackingHandler.PACK_HEADER;
import static org.swisspush.redisques.util.RedisquesAPI.*;

/**
 * Test class for the {@link PackingHandler}
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class PackingHandlerTest {

    private PackingHandler packingHandler;
    private PackingValidator validator;
    private HttpServerRequest request;
    private HttpServerResponse response;
    private GateleenExceptionFactory exceptionFactory;
    private SimpleMeterRegistry meterRegistry;
    private final String redisquesAddress = "redisquesAddress";
    private final String queuePrefix = "packed-";

    private Vertx vertx;
    private EventBus eventBus;

    @Before
    public void setUp() {
        vertx = Vertx.vertx();
        eventBus = Mockito.spy(vertx.eventBus());
        exceptionFactory = mock(GateleenExceptionFactory.class);
        validator = mock(PackingValidator.class);
        packingHandler = new PackingHandler(vertx, queuePrefix, redisquesAddress, validator, exceptionFactory);

        meterRegistry = new SimpleMeterRegistry();
        packingHandler.setMeterRegistry(meterRegistry);

        request = mock(HttpServerRequest.class);
        response = mock(HttpServerResponse.class);
        when(request.response()).thenReturn(response);

        doAnswer(invocation -> {
            Handler<Buffer> bodyHandler = invocation.getArgument(0);
            // Simulate the body content
            bodyHandler.handle(Buffer.buffer("Mocked body content"));
            return request;
        }).when(request).bodyHandler(any());
    }

    @Test
    public void testIsPacked(TestContext context) {
        when(request.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap().add(PACK_HEADER, "true"));
        context.assertTrue(packingHandler.isPacked(request));

        when(request.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap().add("X-PACKED", "true"));
        context.assertTrue(packingHandler.isPacked(request));

        when(request.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap().add("X-pACkEd", "true"));
        context.assertTrue(packingHandler.isPacked(request));

        when(request.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap());
        context.assertFalse(packingHandler.isPacked(request));
    }

    @Test
    public void testHandleUnPackedRequest(TestContext context) {
        when(request.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap());
        boolean handled = packingHandler.handle(request);
        context.assertTrue(handled);

        verify(response, times(1)).setStatusCode(eq(StatusCode.INTERNAL_SERVER_ERROR.getStatusCode()));
        verify(response, times(1)).setStatusMessage(eq(StatusCode.INTERNAL_SERVER_ERROR.getStatusMessage()));

        assertSuccessMetricCounts(context, 0.0);
        assertFailMetricCounts(context, 0.0);
    }

    @Test
    public void testHandleWrongMethod(TestContext context) {
        when(request.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap().add(PACK_HEADER, "true"));
        when(request.method()).thenReturn(HttpMethod.GET);

        boolean handled = packingHandler.handle(request);
        context.assertTrue(handled);

        when(request.method()).thenReturn(HttpMethod.DELETE);
        handled = packingHandler.handle(request);
        context.assertTrue(handled);

        when(request.method()).thenReturn(HttpMethod.HEAD);
        handled = packingHandler.handle(request);
        context.assertTrue(handled);

        when(request.method()).thenReturn(HttpMethod.OPTIONS);
        handled = packingHandler.handle(request);
        context.assertTrue(handled);

        verify(response, times(4)).setStatusCode(eq(StatusCode.BAD_REQUEST.getStatusCode()));
        verify(response, times(4)).setStatusMessage(eq(StatusCode.BAD_REQUEST.getStatusMessage()));

        assertSuccessMetricCounts(context, 0.0);
        assertFailMetricCounts(context, 0.0);
    }

    @Test
    public void testHandleInvalidPayload(TestContext context) {
        when(request.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap().add(PACK_HEADER, "true"));
        when(request.method()).thenReturn(HttpMethod.PUT);
        when(validator.validatePackingPayload(any())).thenReturn(new ValidationResult(ValidationStatus.VALIDATED_NEGATIV, "Invalid payload"));

        boolean handled = packingHandler.handle(request);
        context.assertTrue(handled);

        verify(response, times(1)).setStatusCode(eq(StatusCode.BAD_REQUEST.getStatusCode()));
        verify(response, times(1)).setStatusMessage(eq(StatusCode.BAD_REQUEST.getStatusMessage()));
        verify(validator, times(1)).validatePackingPayload(eq(Buffer.buffer("Mocked body content")));

        assertSuccessMetricCounts(context, 0.0);
        assertFailMetricCounts(context, 0.0);
    }

    @Test
    public void testHandleInvalidPayloadRequestParsing(TestContext context) {
        when(request.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap().add(PACK_HEADER, "true"));
        when(request.method()).thenReturn(HttpMethod.PUT);
        when(validator.validatePackingPayload(any())).thenReturn(new ValidationResult(ValidationStatus.VALIDATED_POSITIV));

        boolean handled = packingHandler.handle(request);
        context.assertTrue(handled);

        verify(response, times(1)).setStatusCode(eq(StatusCode.BAD_REQUEST.getStatusCode()));
        verify(response, times(1)).setStatusMessage(eq(StatusCode.BAD_REQUEST.getStatusMessage()));
        verify(validator, times(1)).validatePackingPayload(eq(Buffer.buffer("Mocked body content")));

        assertSuccessMetricCounts(context, 0.0);
        assertFailMetricCounts(context, 0.0);
    }

    @Test
    public void testHandleValid(TestContext context) throws InterruptedException {
        Async async = context.async(1);
        when(request.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap().add(PACK_HEADER, "true"));
        when(request.method()).thenReturn(HttpMethod.PUT);
        when(validator.validatePackingPayload(any())).thenReturn(new ValidationResult(ValidationStatus.VALIDATED_POSITIV));

        Buffer data = Buffer.buffer("{\n" +
                "  \"requests\": [\n" +
                "    {\n" +
                "      \"uri\": \"/playground/some/url\",\n" +
                "      \"method\": \"PUT\",\n" +
                "      \"payload\": {\n" +
                "        \"key\": 1,\n" +
                "        \"key2\": [1,2,3]\n" +
                "      },\n" +
                "      \"headers\": [[\"x-foo\", \"bar\"], [\"x-bar\", \"foo\"]]\n" +
                "    }\n" +
                "  ]\n" +
                "}");

        doAnswer(invocation -> {
            Handler<Buffer> bodyHandler = invocation.getArgument(0);
            // Simulate the body content
            bodyHandler.handle(data);
            return request;
        }).when(request).bodyHandler(any());

        eventBus.consumer(redisquesAddress, message -> {
            async.countDown();
            context.assertTrue(((JsonObject) message.body()).getJsonObject(PAYLOAD).getString(QUEUENAME).startsWith(queuePrefix));
            message.reply(new JsonObject().put(STATUS, OK));
        });

        boolean handled = packingHandler.handle(request);
        context.assertTrue(handled);

        verify(response, times(1)).setStatusCode(eq(StatusCode.OK.getStatusCode()));
        verify(response, times(1)).setStatusMessage(eq(StatusCode.OK.getStatusMessage()));
        verify(validator, times(1)).validatePackingPayload(eq(data));

        Thread.sleep(500);

        assertSuccessMetricCounts(context, 1.0);
        assertFailMetricCounts(context, 0.0);

        async.awaitSuccess();
    }

    @Test
    public void testHandleRedisquesEnqueueFail(TestContext context) throws InterruptedException {
        Async async = context.async(1);
        when(request.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap().add(PACK_HEADER, "true"));
        when(request.method()).thenReturn(HttpMethod.PUT);
        when(validator.validatePackingPayload(any())).thenReturn(new ValidationResult(ValidationStatus.VALIDATED_POSITIV));

        Buffer data = Buffer.buffer("{\n" +
                "  \"requests\": [\n" +
                "    {\n" +
                "      \"uri\": \"/playground/some/url\",\n" +
                "      \"method\": \"PUT\",\n" +
                "      \"payload\": {\n" +
                "        \"key\": 1,\n" +
                "        \"key2\": [1,2,3]\n" +
                "      },\n" +
                "      \"headers\": [[\"x-foo\", \"bar\"], [\"x-bar\", \"foo\"]]\n" +
                "    }\n" +
                "  ]\n" +
                "}");

        doAnswer(invocation -> {
            Handler<Buffer> bodyHandler = invocation.getArgument(0);
            // Simulate the body content
            bodyHandler.handle(data);
            return request;
        }).when(request).bodyHandler(any());

        eventBus.consumer(redisquesAddress, message -> {
            async.countDown();
            context.assertTrue(((JsonObject) message.body()).getJsonObject(PAYLOAD).getString(QUEUENAME).startsWith(queuePrefix));
            message.reply(new JsonObject().put(STATUS, ERROR));
        });

        boolean handled = packingHandler.handle(request);
        context.assertTrue(handled);

        verify(response, times(1)).setStatusCode(eq(StatusCode.OK.getStatusCode()));
        verify(response, times(1)).setStatusMessage(eq(StatusCode.OK.getStatusMessage()));
        verify(validator, times(1)).validatePackingPayload(eq(data));

        Thread.sleep(500);

        assertSuccessMetricCounts(context, 0.0);
        assertFailMetricCounts(context, 1.0);

        async.awaitSuccess();
    }

    @Test
    public void testHandleValidMultiple(TestContext context) throws InterruptedException {
        Async async = context.async(2);
        when(request.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap().add(PACK_HEADER, "true"));
        when(request.method()).thenReturn(HttpMethod.PUT);
        when(validator.validatePackingPayload(any())).thenReturn(new ValidationResult(ValidationStatus.VALIDATED_POSITIV));

        Buffer dataMultipleRequests = Buffer.buffer("{\n" +
                "  \"requests\": [\n" +
                "    {\n" +
                "      \"uri\": \"/playground/some/url\",\n" +
                "      \"method\": \"PUT\",\n" +
                "      \"payload\": {\n" +
                "        \"key\": 1,\n" +
                "        \"key2\": [1,2,3]\n" +
                "      },\n" +
                "      \"headers\": [[\"x-foo\", \"bar\"], [\"x-bar\", \"foo\"]]\n" +
                "    },\n" +
                "    {\n" +
                "      \"uri\": \"/playground/some/other/url\",\n" +
                "      \"method\": \"POST\",\n" +
                "      \"payload\": {\n" +
                "      }\n" +
                "    }    \n" +
                "  ]\n" +
                "}");

        doAnswer(invocation -> {
            Handler<Buffer> bodyHandler = invocation.getArgument(0);
            // Simulate the body content
            bodyHandler.handle(dataMultipleRequests);
            return request;
        }).when(request).bodyHandler(any());

        eventBus.consumer(redisquesAddress, message -> {
            async.countDown();
            message.reply(new JsonObject().put(STATUS, OK));
        });

        boolean handled = packingHandler.handle(request);
        context.assertTrue(handled);

        verify(response, times(1)).setStatusCode(eq(StatusCode.OK.getStatusCode()));
        verify(response, times(1)).setStatusMessage(eq(StatusCode.OK.getStatusMessage()));
        verify(validator, times(1)).validatePackingPayload(eq(dataMultipleRequests));

        Thread.sleep(500);

        assertSuccessMetricCounts(context, 2.0);
        assertFailMetricCounts(context, 0.0);

        async.awaitSuccess();
    }

    @Test
    public void testHandleValidDefinedQueueName(TestContext context) throws InterruptedException {
        Async async = context.async(1);
        when(request.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap().add(PACK_HEADER, "true"));
        when(request.method()).thenReturn(HttpMethod.PUT);
        when(validator.validatePackingPayload(any())).thenReturn(new ValidationResult(ValidationStatus.VALIDATED_POSITIV));

        Buffer data = Buffer.buffer("{\n" +
                "  \"requests\": [\n" +
                "    {\n" +
                "      \"uri\": \"/playground/some/url\",\n" +
                "      \"method\": \"PUT\",\n" +
                "      \"payload\": {\n" +
                "        \"key\": 1,\n" +
                "        \"key2\": [1,2,3]\n" +
                "      },\n" +
                "      \"headers\": [[\"x-queue\", \"my-super-queue\"], [\"x-bar\", \"foo\"]]\n" +
                "    }\n" +
                "  ]\n" +
                "}");

        doAnswer(invocation -> {
            Handler<Buffer> bodyHandler = invocation.getArgument(0);
            // Simulate the body content
            bodyHandler.handle(data);
            return request;
        }).when(request).bodyHandler(any());

        eventBus.consumer(redisquesAddress, message -> {
            async.countDown();
            context.assertEquals("my-super-queue", ((JsonObject) message.body()).getJsonObject(PAYLOAD).getString(QUEUENAME));
            message.reply(new JsonObject().put(STATUS, OK));
        });

        boolean handled = packingHandler.handle(request);
        context.assertTrue(handled);

        verify(response, times(1)).setStatusCode(eq(StatusCode.OK.getStatusCode()));
        verify(response, times(1)).setStatusMessage(eq(StatusCode.OK.getStatusMessage()));
        verify(validator, times(1)).validatePackingPayload(eq(data));

        Thread.sleep(500);

        assertSuccessMetricCounts(context, 1.0);
        assertFailMetricCounts(context, 0.0);

        async.awaitSuccess();
    }

    private void assertSuccessMetricCounts(TestContext context, double expectedCount) {
        Counter counter = meterRegistry.get(PackingHandler.PACKING_REQUESTS_SUCCESS_COUNTER).counter();
        context.assertEquals(expectedCount, counter.count(), "Counter for success packed requests should have been incremented by " + expectedCount);
    }

    private void assertFailMetricCounts(TestContext context, double expectedCount) {
        Counter counter = meterRegistry.get(PackingHandler.PACKING_REQUESTS_FAIL_COUNTER).counter();
        context.assertEquals(expectedCount, counter.count(), "Counter for failed packed requests should have been incremented by " + expectedCount);
    }
}

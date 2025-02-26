package org.swisspush.gateleen.packing;

import io.vertx.core.*;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.Message;
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
import org.swisspush.gateleen.core.util.RoleExtractor;
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
        packingHandler = new PackingHandler(vertx, queuePrefix, redisquesAddress, RoleExtractor.groupHeader, validator, exceptionFactory);
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

    private void assertGroupHeader(TestContext context, Message<Object> message, String expectedGroupHeader) {
        JsonObject messageObj = new JsonObject(((JsonObject) message.body()).getString(MESSAGE));
        MultiMap map = PackingRequestParser.multiMapFromJsonArray(messageObj.getJsonArray("headers"));
        context.assertEquals(expectedGroupHeader, map.get("x-rp-grp"));
    }

    private void assertNoQueueHeader(TestContext context, Message<Object> message) {
        JsonObject messageObj = new JsonObject(((JsonObject) message.body()).getString(MESSAGE));
        MultiMap map = PackingRequestParser.multiMapFromJsonArray(messageObj.getJsonArray("headers"));
        context.assertFalse(map.contains("x-queue"));
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
    }

    @Test
    public void testHandleValid(TestContext context) {
        Async async = context.async(1);
        when(request.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap().add(PACK_HEADER, "true").add("x-rp-grp", "master"));
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

            assertGroupHeader(context, message, "master");
            message.reply(new JsonObject().put(STATUS, OK));
        });

        boolean handled = packingHandler.handle(request);
        context.assertTrue(handled);

        verify(response, times(1)).setStatusCode(eq(StatusCode.OK.getStatusCode()));
        verify(response, times(1)).setStatusMessage(eq(StatusCode.OK.getStatusMessage()));
        verify(validator, times(1)).validatePackingPayload(eq(data));

        async.awaitSuccess();
    }

    @Test
    public void testHandleValidMultiple(TestContext context) {
        Async async = context.async(2);
        when(request.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap().add(PACK_HEADER, "true").add("x-rp-grp", "batman"));
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
            assertGroupHeader(context, message, "batman");
            message.reply(new JsonObject().put(STATUS, OK));
        });

        boolean handled = packingHandler.handle(request);
        context.assertTrue(handled);

        verify(response, times(1)).setStatusCode(eq(StatusCode.OK.getStatusCode()));
        verify(response, times(1)).setStatusMessage(eq(StatusCode.OK.getStatusMessage()));
        verify(validator, times(1)).validatePackingPayload(eq(dataMultipleRequests));

        async.awaitSuccess();
    }

    @Test
    public void testHandleValidDefinedQueueName(TestContext context) {
        Async async = context.async(1);
        when(request.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap().add(PACK_HEADER, "true").add("x-rp-grp", "superman"));
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
            assertNoQueueHeader(context, message);
            assertGroupHeader(context, message, "superman");
            message.reply(new JsonObject().put(STATUS, OK));
        });

        boolean handled = packingHandler.handle(request);
        context.assertTrue(handled);

        verify(response, times(1)).setStatusCode(eq(StatusCode.OK.getStatusCode()));
        verify(response, times(1)).setStatusMessage(eq(StatusCode.OK.getStatusMessage()));
        verify(validator, times(1)).validatePackingPayload(eq(data));

        async.awaitSuccess();
    }
}

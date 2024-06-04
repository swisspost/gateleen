package org.swisspush.gateleen.kafka;

import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.impl.headers.HeadersMultiMap;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.swisspush.gateleen.core.configuration.ConfigurationResourceManager;
import org.swisspush.gateleen.core.exception.GateleenExceptionFactory;
import org.swisspush.gateleen.core.storage.MockResourceStorage;
import org.swisspush.gateleen.core.util.ResourcesUtils;
import org.swisspush.gateleen.core.util.StatusCode;
import org.swisspush.gateleen.core.validation.ValidationResult;
import org.swisspush.gateleen.core.validation.ValidationStatus;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static java.lang.Thread.currentThread;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.swisspush.gateleen.core.configuration.ConfigurationResourceManager.CONFIG_RESOURCE_CHANGED_ADDRESS;
import static org.swisspush.gateleen.core.exception.GateleenExceptionFactory.newGateleenWastefulExceptionFactory;

/**
 * Test class for the {@link KafkaHandler}
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class KafkaHandlerTest {

    private Vertx vertx;
    private KafkaProducerRepository repository;
    private KafkaMessageSender kafkaMessageSender;
    private KafkaMessageValidator messageValidator;
    private ConfigurationResourceManager configurationResourceManager;
    private KafkaHandler handler;
    private MockResourceStorage storage;
    private GateleenExceptionFactory exceptionFactory = newGateleenWastefulExceptionFactory();
    private Vertx vertxMock;

    private final String configResourceUri = "/kafka/topicsConfig";
    private final String streamingPath = "/kafka/streaming/";

    private final String CONFIG_RESOURCE = ResourcesUtils.loadResource("testresource_valid_kafka_topic_configuration", true);
    private final String CONFIG_WILDCARD_RESOURCE = ResourcesUtils.loadResource("testresource_wildcard_kafka_topic_configuration", true);

    @Before
    public void setUp() {
        vertx = Vertx.vertx();
        vertxMock = Mockito.mock(Vertx.class);
        doAnswer(inv -> {
            String bkup = currentThread().getName();
            currentThread().setName("blah");
            try {
                var result = ((Callable) inv.getArguments()[0]).call();
                return Future.succeededFuture(result);
            } finally {
                currentThread().setName(bkup);
            }
        }).when(vertxMock).executeBlocking(any(Callable.class));
        repository = Mockito.spy(new KafkaProducerRepository(vertx));
        kafkaMessageSender = Mockito.mock(KafkaMessageSender.class);
        messageValidator = Mockito.mock(KafkaMessageValidator.class);
        storage = new MockResourceStorage();
        configurationResourceManager = new ConfigurationResourceManager(vertx, storage, exceptionFactory);
        handler = new KafkaHandler(
            vertxMock, exceptionFactory, configurationResourceManager, null, repository,
            kafkaMessageSender, configResourceUri, streamingPath, null);

        when(kafkaMessageSender.sendMessages(any(), any())).thenReturn(Future.succeededFuture());
    }

    @Test
    public void initWithMissingConfigResource(TestContext context) {
        Async async = context.async();
        context.assertFalse(handler.isInitialized());
        handler.initialize().onComplete(event -> {
            verifyNoInteractions(repository);
            verifyNoInteractions(kafkaMessageSender);
            context.assertFalse(handler.isInitialized());
            async.complete();
        });
    }

    @Test
    public void initWithExistingConfigResource(TestContext context) {
        Async async = context.async();
        storage.putMockData(configResourceUri, CONFIG_RESOURCE);
        context.assertFalse(handler.isInitialized());
        handler.initialize().onComplete(event -> {
            verify(repository, times(1)).closeAll();

            Map<String, String> configs_1 = new HashMap<>() {{
                put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
                put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
                put("bootstrap.servers", "localhost:9092");
            }};
            verify(repository, times(1)).addKafkaProducer(eq(new KafkaConfiguration(Pattern.compile("my.topic.*"), configs_1)));

            Map<String, String> configs_2 = new HashMap<>() {{
                put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
                put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
                put("bootstrap.servers", "localhost:9093");
                put("acks", "1");
            }};
            verify(repository, times(1)).addKafkaProducer(eq(new KafkaConfiguration(Pattern.compile("."), configs_2)));
            verifyNoInteractions(kafkaMessageSender);
            context.assertTrue(handler.isInitialized());
            async.complete();
        });
    }

    @Test
    public void initWithWildcardConfigResource(TestContext context) {
        Async async = context.async();
        Map<String, Object> props = new HashMap<>();
        props.put("kafka.host", "localhost");
        props.put("kafka.port", "9094");
        storage.putMockData(configResourceUri, CONFIG_WILDCARD_RESOURCE);

        handler = new KafkaHandler(configurationResourceManager, repository, kafkaMessageSender,
                configResourceUri, streamingPath, props);
        context.assertFalse(handler.isInitialized());

        handler.initialize().onComplete(event -> {
            // depending whether resourceChanged fires or not we get one or two invocations
            verify(repository, atLeastOnce()).closeAll();
            Map<String, String> configs_1 = new HashMap<>() {{
                put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
                put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
                put("bootstrap.servers", "localhost:9094");
                put("acks", "all");
            }};
            verify(repository, atLeastOnce()).addKafkaProducer(eq(new KafkaConfiguration(Pattern.compile("my.properties.topic.*"), configs_1)));
            verifyNoInteractions(kafkaMessageSender);
            context.assertTrue(handler.isInitialized());
            async.complete();
        });
    }

    @Test
    public void initWithWildcardConfigResourceException(TestContext context) {
        Async async = context.async();
        Map<String, Object> props = new HashMap<>();
        storage.putMockData(configResourceUri, CONFIG_WILDCARD_RESOURCE);

        handler = new KafkaHandler(configurationResourceManager, repository, kafkaMessageSender,
                configResourceUri, streamingPath, props);
        context.assertFalse(handler.isInitialized());

        handler.initialize().onComplete(event -> {
            verify(repository, never()).addKafkaProducer(any());
            verifyNoInteractions(kafkaMessageSender);
            context.assertTrue(handler.isInitialized());
            async.complete();
        });
    }

    @Test
    public void resourceRemovedTriggersCloseAllProducers(TestContext context){
        Async async = context.async();
        handler.initialize().onComplete(event -> {
            JsonObject object = new JsonObject();
            object.put("requestUri", configResourceUri);
            object.put("type", "remove");
            vertx.eventBus().publish(CONFIG_RESOURCE_CHANGED_ADDRESS, object);
            verify(repository, timeout(100).times(1)).closeAll();
            verifyNoInteractions(kafkaMessageSender);
            async.complete();
        });
    }

    @Test
    public void resourceChangedTriggersCloseAllAndReCreateOfProducers(TestContext context){
        Async async = context.async();
        context.assertFalse(handler.isInitialized());
        storage.putMockData(configResourceUri, CONFIG_RESOURCE);

        JsonObject object = new JsonObject();
        object.put("requestUri", configResourceUri);
        object.put("type", "change");
        vertx.eventBus().publish(CONFIG_RESOURCE_CHANGED_ADDRESS, object);
        verify(repository, timeout(500).times(1)).closeAll();

        Map<String, String> configs_1 = new HashMap<>() {{
            put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
            put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
            put("bootstrap.servers", "localhost:9092");
        }};
        verify(repository, timeout(500).times(1)).addKafkaProducer(eq(new KafkaConfiguration(Pattern.compile("my.topic.*"), configs_1)));

        Map<String, String> configs_2 = new HashMap<>() {{
            put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
            put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
            put("bootstrap.servers", "localhost:9093");
            put("acks", "1");
        }};
        verify(repository, timeout(500).times(1)).addKafkaProducer(eq(new KafkaConfiguration(Pattern.compile("."), configs_2)));
        verifyNoInteractions(kafkaMessageSender);
        await().atMost(1, SECONDS).until( () -> handler.isInitialized(), equalTo(Boolean.TRUE));
        async.complete();
    }

    @Test
    public void handleNotStreamingPath(TestContext context){
        Async async = context.async();
        handler.initialize().onComplete(event -> {
            StreamingRequest request = new StreamingRequest(HttpMethod.POST, "/some/other/uri/path");
            final boolean handled = handler.handle(request);
            context.assertFalse(handled);
            verifyNoInteractions(kafkaMessageSender);
            async.complete();
        });
    }

    @Test
    public void handleNotPOSTRequest(TestContext context){
        Async async = context.async();
        handler.initialize().onComplete(event -> {

            HttpServerResponse response = spy(new StreamingResponse(MultiMap.caseInsensitiveMultiMap()));
            StreamingRequest request = new StreamingRequest(HttpMethod.GET, streamingPath + "myTopic", "", MultiMap.caseInsensitiveMultiMap(), response);
            final boolean handled = handler.handle(request);

            context.assertTrue(handled);
            verifyNoInteractions(kafkaMessageSender);
            verify(response, times(1)).setStatusCode(eq(StatusCode.METHOD_NOT_ALLOWED.getStatusCode()));

            async.complete();
        });
    }

    @Test
    public void handleEmptyTopic(TestContext context){
        Async async = context.async();
        handler.initialize().onComplete(event -> {

            HttpServerResponse response = spy(new StreamingResponse(MultiMap.caseInsensitiveMultiMap()));
            StreamingRequest request = new StreamingRequest(HttpMethod.POST, streamingPath, "", MultiMap.caseInsensitiveMultiMap(), response);
            final boolean handled = handler.handle(request);

            context.assertTrue(handled);
            verifyNoInteractions(kafkaMessageSender);
            verify(response, times(1)).setStatusCode(eq(StatusCode.BAD_REQUEST.getStatusCode()));

            async.complete();
        });
    }

    @Test
    public void handleNoMatchingProducer(TestContext context){
        Async async = context.async();
        handler.initialize().onComplete(event -> {

            HttpServerResponse response = spy(new StreamingResponse(MultiMap.caseInsensitiveMultiMap()));
            StreamingRequest request = new StreamingRequest(HttpMethod.POST, streamingPath + "someTopic", "", MultiMap.caseInsensitiveMultiMap(), response);
            final boolean handled = handler.handle(request);

            context.assertTrue(handled);
            verifyNoInteractions(kafkaMessageSender);
            verify(response, times(1)).setStatusCode(eq(StatusCode.NOT_FOUND.getStatusCode()));

            async.complete();
        });
    }

    @Test
    public void handleInvalidPayload(TestContext context){
        Async async = context.async();
        storage.putMockData(configResourceUri, CONFIG_RESOURCE);
        handler.initialize().onComplete(event -> {
            context.assertTrue(handler.isInitialized());

            HttpServerResponse response = spy(new StreamingResponse(MultiMap.caseInsensitiveMultiMap()));
            StreamingRequest request = new StreamingRequest(HttpMethod.POST, streamingPath + "my.topic.x", "{}", MultiMap.caseInsensitiveMultiMap(), response);
            final boolean handled = handler.handle(request);

            context.assertTrue(handled);
            verifyNoInteractions(kafkaMessageSender);
            verify(response, times(1)).setStatusCode(eq(StatusCode.BAD_REQUEST.getStatusCode()));

            async.complete();
        });
    }

    @Test
    public void handleValidPayloadWithSingleMessage(TestContext context){
        Async async = context.async();
        storage.putMockData(configResourceUri, CONFIG_RESOURCE);
        handler.initialize().onComplete(event -> {
            context.assertTrue(handler.isInitialized());

            String singleMessagePayload = "{\n" +
                    "\t\"records\": [{\n" +
                    "\t\t\"key\": \"record_0000001\",\n" +
                    "\t\t\"value\": {\n" +
                    "\t\t\t\"metadata\": {\n" +
                    "\t\t\t\t\"techId\": \"071X1500492vora1560860907613\",\n" +
                    "\t\t\t\t\"user\": \"foo\"\n" +
                    "\t\t\t},\n" +
                    "\t\t\t\"event\": {\n" +
                    "\t\t\t\t\"actionTime\": \"2019-06-18T14:28:27.617+02:00\",\n" +
                    "\t\t\t\t\"type\": 1,\n" +
                    "\t\t\t\t\"bool\": false\n" +
                    "\t\t\t}\n" +
                    "\t\t},\n" +
                    "\t\t\"headers\": {\n" +
                    "\t\t\t\"x-header-a\": \"value-a\",\n" +
                    "\t\t\t\"x-header-b\": \"value-b\",\n" +
                    "\t\t\t\"x-header-c\": \"value-c\"\n" +
                    "\t\t}\n" +
                    "\t}]\n" +
                    "}";

            HttpServerResponse response = spy(new StreamingResponse(MultiMap.caseInsensitiveMultiMap()));
            StreamingRequest request = new StreamingRequest(HttpMethod.POST, streamingPath + "my.topic.x",
                    singleMessagePayload, MultiMap.caseInsensitiveMultiMap(), response);
            final boolean handled = handler.handle(request);

            context.assertTrue(handled);
            verify(kafkaMessageSender, times(1)).sendMessages(any(), any());
            verify(response, times(1)).setStatusCode(eq(StatusCode.OK.getStatusCode()));

            async.complete();
        });
    }

    @SuppressWarnings(value="unchecked")
    @Test
    public void handleValidPayloadWithTwoMessages(TestContext context){
        Async async = context.async();
        storage.putMockData(configResourceUri, CONFIG_RESOURCE);
        handler.initialize().onComplete(event -> {
            context.assertTrue(handler.isInitialized());

            String singleMessagePayload = "{\n" +
                    "\t\"records\": [{\n" +
                    "\t\t\"key\": \"record_0000001\",\n" +
                    "\t\t\"value\": {\n" +
                    "\t\t\t\"metadata\": {\n" +
                    "\t\t\t\t\"techId\": \"071X150613\",\n" +
                    "\t\t\t\t\"user\": \"foo\"\n" +
                    "\t\t\t},\n" +
                    "\t\t\t\"event\": {\n" +
                    "\t\t\t\t\"actionTime\": \"2019-06-18T14:28:27.617+02:00\",\n" +
                    "\t\t\t\t\"type\": 1,\n" +
                    "\t\t\t\t\"bool\": false\n" +
                    "\t\t\t}\n" +
                    "\t\t},\n" +
                    "\t\t\"headers\": {\n" +
                    "\t\t\t\"x-header-a\": \"value-a\",\n" +
                    "\t\t\t\"x-header-b\": \"value-b\",\n" +
                    "\t\t\t\"x-header-c\": \"value-c\"\n" +
                    "\t\t}\n" +
                    "\t}, {\n" +
                    "\t\t\"key\": \"record_0000002\",\n" +
                    "\t\t\"value\": {\n" +
                    "\t\t\t\"metadata\": {\n" +
                    "\t\t\t\t\"techId\": \"071X15523z4f42x60907613\",\n" +
                    "\t\t\t\t\"user\": \"bar\"\n" +
                    "\t\t\t},\n" +
                    "\t\t\t\"event\": {\n" +
                    "\t\t\t\t\"actionTime\": \"2019-06-18T15:28:21.617+02:00\",\n" +
                    "\t\t\t\t\"type\": 2,\n" +
                    "\t\t\t\t\"bool\": true\n" +
                    "\t\t\t}\n" +
                    "\t\t}\n" +
                    "\t}]\n" +
                    "}";

            HttpServerResponse response = spy(new StreamingResponse(MultiMap.caseInsensitiveMultiMap()));
            StreamingRequest request = new StreamingRequest(HttpMethod.POST, streamingPath + "my.topic.x",
                    singleMessagePayload, MultiMap.caseInsensitiveMultiMap(), response);
            final boolean handled = handler.handle(request);

            context.assertTrue(handled);
            ArgumentCaptor<List> recordsArguments = ArgumentCaptor.forClass(List.class);
            verify(kafkaMessageSender, times(1)).sendMessages(any(), recordsArguments.capture());
            context.assertEquals(2, recordsArguments.getValue().size());
            verify(response, times(1)).setStatusCode(eq(StatusCode.OK.getStatusCode()));

            async.complete();
        });
    }

    @Test
    public void handleValidPayloadWithFailingMessageSending(TestContext context){
        Async async = context.async();

        when(kafkaMessageSender.sendMessages(any(), any())).thenReturn(Future.failedFuture("booom: message could not be sent!"));

        storage.putMockData(configResourceUri, CONFIG_RESOURCE);
        handler.initialize().onComplete(event -> {
            context.assertTrue(handler.isInitialized());

            String singleMessagePayload = "{\n" +
                    "\t\"records\": [{\n" +
                    "\t\t\"key\": \"record_0000001\",\n" +
                    "\t\t\"value\": {\n" +
                    "\t\t\t\"metadata\": {\n" +
                    "\t\t\t\t\"techId\": \"071X1500492vora1560860907613\",\n" +
                    "\t\t\t\t\"user\": \"foo\"\n" +
                    "\t\t\t},\n" +
                    "\t\t\t\"event\": {\n" +
                    "\t\t\t\t\"actionTime\": \"2019-06-18T14:28:27.617+02:00\",\n" +
                    "\t\t\t\t\"type\": 1,\n" +
                    "\t\t\t\t\"bool\": false\n" +
                    "\t\t\t}\n" +
                    "\t\t},\n" +
                    "\t\t\"headers\": {\n" +
                    "\t\t\t\"x-header-a\": \"value-a\",\n" +
                    "\t\t\t\"x-header-b\": \"value-b\",\n" +
                    "\t\t\t\"x-header-c\": \"value-c\"\n" +
                    "\t\t}\n" +
                    "\t}]\n" +
                    "}";

            HttpServerResponse response = spy(new StreamingResponse(MultiMap.caseInsensitiveMultiMap()));
            StreamingRequest request = new StreamingRequest(HttpMethod.POST, streamingPath + "my.topic.x",
                    singleMessagePayload, MultiMap.caseInsensitiveMultiMap(), response);
            final boolean handled = handler.handle(request);

            context.assertTrue(handled);
            verify(kafkaMessageSender, times(1)).sendMessages(any(), any());
            verify(response, times(1)).setStatusCode(eq(StatusCode.INTERNAL_SERVER_ERROR.getStatusCode()));

            async.complete();
        });
    }

    @Test
    public void handlePayloadNotPassingValidation(TestContext context){
        Async async = context.async();

        handler = new KafkaHandler(
            vertxMock, exceptionFactory, configurationResourceManager, messageValidator, repository,
            kafkaMessageSender, configResourceUri, streamingPath, null);

        when(messageValidator.validateMessages(any(HttpServerRequest.class), any()))
                .thenReturn(Future.succeededFuture(new ValidationResult(ValidationStatus.VALIDATED_NEGATIV, "Boooom")));

        storage.putMockData(configResourceUri, CONFIG_RESOURCE);
        handler.initialize().onComplete(event -> {
            context.assertTrue(handler.isInitialized());

            String singleMessagePayload = "{\n" +
                    "\t\"records\": [{\n" +
                    "\t\t\"key\": \"record_0000001\",\n" +
                    "\t\t\"value\": {\n" +
                    "\t\t\t\"metadata\": {\n" +
                    "\t\t\t\t\"techId\": \"071X1500492vora1560860907613\",\n" +
                    "\t\t\t\t\"user\": \"foo\"\n" +
                    "\t\t\t},\n" +
                    "\t\t\t\"event\": {\n" +
                    "\t\t\t\t\"actionTime\": \"2019-06-18T14:28:27.617+02:00\",\n" +
                    "\t\t\t\t\"type\": 1,\n" +
                    "\t\t\t\t\"bool\": false\n" +
                    "\t\t\t}\n" +
                    "\t\t},\n" +
                    "\t\t\"headers\": {\n" +
                    "\t\t\t\"x-header-a\": \"value-a\",\n" +
                    "\t\t\t\"x-header-b\": \"value-b\",\n" +
                    "\t\t\t\"x-header-c\": \"value-c\"\n" +
                    "\t\t}\n" +
                    "\t}]\n" +
                    "}";

            HttpServerResponse response = spy(new StreamingResponse(new HeadersMultiMap()));
            StreamingRequest request = new StreamingRequest(HttpMethod.POST, streamingPath + "my.topic.x",
                    singleMessagePayload, new HeadersMultiMap(), response);
            final boolean handled = handler.handle(request);

            context.assertTrue(handled);
            verifyNoInteractions(kafkaMessageSender);
            verify(response, times(1)).setStatusCode(eq(StatusCode.BAD_REQUEST.getStatusCode()));

            async.complete();
        });
    }

    @Test
    public void handleErrorWhileValidation(TestContext context){
        Async async = context.async();

        handler = new KafkaHandler(
            vertxMock, exceptionFactory, configurationResourceManager, messageValidator, repository,
            kafkaMessageSender, configResourceUri, streamingPath, null);

        when(messageValidator.validateMessages(any(HttpServerRequest.class), any()))
                .thenReturn(Future.failedFuture("Boooom"));

        storage.putMockData(configResourceUri, CONFIG_RESOURCE);
        handler.initialize().onComplete(event -> {
            context.assertTrue(handler.isInitialized());

            String singleMessagePayload = "{\n" +
                    "\t\"records\": [{\n" +
                    "\t\t\"key\": \"record_0000001\",\n" +
                    "\t\t\"value\": {\n" +
                    "\t\t\t\"metadata\": {\n" +
                    "\t\t\t\t\"techId\": \"071X1500492vora1560860907613\",\n" +
                    "\t\t\t\t\"user\": \"foo\"\n" +
                    "\t\t\t},\n" +
                    "\t\t\t\"event\": {\n" +
                    "\t\t\t\t\"actionTime\": \"2019-06-18T14:28:27.617+02:00\",\n" +
                    "\t\t\t\t\"type\": 1,\n" +
                    "\t\t\t\t\"bool\": false\n" +
                    "\t\t\t}\n" +
                    "\t\t},\n" +
                    "\t\t\"headers\": {\n" +
                    "\t\t\t\"x-header-a\": \"value-a\",\n" +
                    "\t\t\t\"x-header-b\": \"value-b\",\n" +
                    "\t\t\t\"x-header-c\": \"value-c\"\n" +
                    "\t\t}\n" +
                    "\t}]\n" +
                    "}";

            HttpServerResponse response = spy(new StreamingResponse(new HeadersMultiMap()));
            StreamingRequest request = new StreamingRequest(HttpMethod.POST, streamingPath + "my.topic.x",
                    singleMessagePayload, new HeadersMultiMap(), response);
            final boolean handled = handler.handle(request);

            context.assertTrue(handled);
            verifyNoInteractions(kafkaMessageSender);
            verify(response, times(1)).setStatusCode(eq(StatusCode.INTERNAL_SERVER_ERROR.getStatusCode()));

            async.complete();
        });
    }
}

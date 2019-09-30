package org.swisspush.gateleen.kafka;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.swisspush.gateleen.core.configuration.ConfigurationResourceManager;
import org.swisspush.gateleen.core.storage.MockResourceStorage;
import org.swisspush.gateleen.core.util.ResourcesUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import static com.jayway.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.*;
import static org.swisspush.gateleen.core.configuration.ConfigurationResourceManager.CONFIG_RESOURCE_CHANGED_ADDRESS;

/**
 * Test class for the {@link KafkaHandler}
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class KafkaHandlerTest {

    private Vertx vertx;
    private KafkaProducerRepository repository;
    private ConfigurationResourceManager configurationResourceManager;
    private KafkaHandler handler;
    private MockResourceStorage storage;

    private String configResourceUri = "/kafka/topicsConfig";
    private String streamingPath = "/kafka/streaming/";

    private final String CONFIG_RESOURCE = ResourcesUtils.loadResource("testresource_valid_kafka_topic_configuration", true);

    @Before
    public void setUp() throws Exception {
        vertx = Vertx.vertx();
        repository = Mockito.spy(new KafkaProducerRepository(vertx));
        storage = new MockResourceStorage();
        configurationResourceManager = new ConfigurationResourceManager(vertx, storage);
        handler = new KafkaHandler(configurationResourceManager, repository,
                configResourceUri, streamingPath);
    }

    @Test
    public void initWithMissingConfigResource(TestContext context) {
        Async async = context.async();
        context.assertFalse(handler.isInitialized());
        handler.initialize().setHandler(event -> {
            verifyZeroInteractions(repository);
            context.assertFalse(handler.isInitialized());
            async.complete();
        });
    }

    @Test
    public void initWithExistingConfigResource(TestContext context) {
        Async async = context.async();
        storage.putMockData(configResourceUri, CONFIG_RESOURCE);
        context.assertFalse(handler.isInitialized());
        handler.initialize().setHandler(event -> {
            verify(repository, times(1)).closeAll();

            Map<String, String> configs_1 = new HashMap<String, String>() {{
                put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
                put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
                put("bootstrap.servers", "localhost:9092");
            }};
            verify(repository, times(1)).addKafkaProducer(eq(new KafkaConfiguration(Pattern.compile("my.topic.*"), configs_1)));

            Map<String, String> configs_2 = new HashMap<String, String>() {{
                put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
                put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
                put("bootstrap.servers", "localhost:9093");
                put("acks", "1");
            }};
            verify(repository, times(1)).addKafkaProducer(eq(new KafkaConfiguration(Pattern.compile("."), configs_2)));
            context.assertTrue(handler.isInitialized());
            async.complete();
        });
    }

    @Test
    public void resourceRemovedTriggersCloseAllProducers(TestContext context){
        Async async = context.async();
        handler.initialize().setHandler(event -> {
            JsonObject object = new JsonObject();
            object.put("requestUri", configResourceUri);
            object.put("type", "remove");
            vertx.eventBus().publish(CONFIG_RESOURCE_CHANGED_ADDRESS, object);
            verify(repository, timeout(100).times(1)).closeAll();
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

        Map<String, String> configs_1 = new HashMap<String, String>() {{
            put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
            put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
            put("bootstrap.servers", "localhost:9092");
        }};
        verify(repository, timeout(500).times(1)).addKafkaProducer(eq(new KafkaConfiguration(Pattern.compile("my.topic.*"), configs_1)));

        Map<String, String> configs_2 = new HashMap<String, String>() {{
            put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
            put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
            put("bootstrap.servers", "localhost:9093");
            put("acks", "1");
        }};
        verify(repository, timeout(500).times(1)).addKafkaProducer(eq(new KafkaConfiguration(Pattern.compile("."), configs_2)));
        await().atMost(1, SECONDS).until( () -> handler.isInitialized(), equalTo(Boolean.TRUE));
        async.complete();

    }
}

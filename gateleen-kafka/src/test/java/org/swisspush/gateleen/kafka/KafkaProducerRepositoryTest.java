package org.swisspush.gateleen.kafka;

import io.vertx.core.Vertx;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Test class for the {@link KafkaProducerRepository}
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class KafkaProducerRepositoryTest {

    private Vertx vertx;
    private KafkaProducerRepository repository;

    private Map<String, String> configs = new HashMap<String, String>() {{
        put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        put("bootstrap.servers", "localhost:9092");
    }};

    @Before
    public void setUp() throws Exception {
        vertx = Vertx.vertx();
        repository = new KafkaProducerRepository(vertx);
    }

    @Test
    public void getKafkaProducerEmptyMap(TestContext context) {
        context.assertFalse(repository.getKafkaProducer(null).isPresent());
        context.assertFalse(repository.getKafkaProducer(patternFrom("my.topic.*")).isPresent());
    }

    @Test
    public void addKafkaProducer(TestContext context) {
        Pattern p1 = patternFrom("my.topic.*");
        Pattern p2 = patternFrom("my.other.topic.*");

        repository.addKafkaProducer(new KafkaConfiguration(p1, configs));

        context.assertTrue(repository.getKafkaProducer(p1).isPresent());
        context.assertFalse(repository.getKafkaProducer(p2).isPresent());
    }

    @Test
    public void closeAll(TestContext context) {
        Async async = context.async();
        Pattern p1 = patternFrom("my.topic.*");
        Pattern p2 = patternFrom("my.other.topic.*");

        repository.addKafkaProducer(new KafkaConfiguration(p1, configs));
        repository.addKafkaProducer(new KafkaConfiguration(p2, configs));

        context.assertTrue(repository.getKafkaProducer(p1).isPresent());
        context.assertTrue(repository.getKafkaProducer(p2).isPresent());

        repository.closeAll().setHandler(event -> {
            context.assertFalse(repository.getKafkaProducer(p1).isPresent());
            context.assertFalse(repository.getKafkaProducer(p2).isPresent());
            async.complete();
        });
    }

    private Pattern patternFrom(String regex){
        return Pattern.compile(regex);
    }
}
package org.swisspush.gateleen.kafka;

import io.vertx.core.Vertx;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import io.vertx.kafka.client.producer.KafkaProducer;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
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

    private final Map<String, String> configs = new HashMap<String, String>() {{
        put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        put("bootstrap.servers", "localhost:9092");
    }};

    private final Map<String, String> configs_2 = new HashMap<String, String>() {{
        put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        put("bootstrap.servers", "localhost:9093");
        put("acks", "1");
    }};

    @Before
    public void setUp() {
        vertx = Vertx.vertx();
        repository = new KafkaProducerRepository(vertx);
    }

    @Test
    public void findMatchingKafkaProducerEmptyMap(TestContext context) {
        context.assertFalse(repository.findMatchingKafkaProducer(null).isPresent());
        context.assertFalse(repository.findMatchingKafkaProducer("my.topic.x").isPresent());
    }

    @Test
    public void findMatchingKafkaProducer(TestContext context) {
        Pattern pattern_1 = patternFrom("my.topic.*");
        Pattern pattern_2 = patternFrom(".+");
        repository.addKafkaProducer(new KafkaConfiguration(pattern_1, configs));
        repository.addKafkaProducer(new KafkaConfiguration(pattern_2, configs_2));

        //
        final Optional<Pair<KafkaProducer<String, String>, Pattern>> anyTopic = repository.findMatchingKafkaProducer("anyTopic");
        context.assertTrue(anyTopic.isPresent());
        context.assertEquals(pattern_2.pattern(), anyTopic.get().getRight().pattern());

        final Optional<Pair<KafkaProducer<String, String>, Pattern>> myTopicX = repository.findMatchingKafkaProducer("my.topic.x");
        context.assertTrue(myTopicX.isPresent());
        context.assertEquals(pattern_1.pattern(), myTopicX.get().getRight().pattern());
    }

    @Test
    public void findMatchingKafkaProducerNonMatching(TestContext context) {
        Pattern pattern_1 = patternFrom("my.topic.*");
        Pattern pattern_2 = patternFrom("my.other.topic.*");
        repository.addKafkaProducer(new KafkaConfiguration(pattern_1, configs));
        repository.addKafkaProducer(new KafkaConfiguration(pattern_2, configs_2));

        context.assertFalse(repository.findMatchingKafkaProducer("anyTopic").isPresent());
        context.assertFalse(repository.findMatchingKafkaProducer("my.unknown.topic.x").isPresent());
        context.assertFalse(repository.findMatchingKafkaProducer("abc.123").isPresent());
    }

    @Test
    public void findMatchingKafkaProducerRespectInsertionOrder(TestContext context) {
        Async async = context.async();

        Pattern pattern_1 = patternFrom("my.topic.*");
        Pattern pattern_2 = patternFrom(".+");

        // add "more general" pattern first
        repository.addKafkaProducer(new KafkaConfiguration(pattern_2, configs_2));
        repository.addKafkaProducer(new KafkaConfiguration(pattern_1, configs));

        final Optional<Pair<KafkaProducer<String, String>, Pattern>> myTopicX = repository.findMatchingKafkaProducer("my.topic.x");
        context.assertTrue(myTopicX.isPresent());

        // the "more general" pattern should have matched
        context.assertEquals(pattern_2.pattern(), myTopicX.get().getRight().pattern());

        // clear the repository to test a different ordering
        repository.closeAll().future().onComplete(event -> {

            // switch order by adding "more specific" pattern first
            repository.addKafkaProducer(new KafkaConfiguration(pattern_1, configs));
            repository.addKafkaProducer(new KafkaConfiguration(pattern_2, configs_2));

            final Optional<Pair<KafkaProducer<String, String>, Pattern>> myTopicX_2 = repository.findMatchingKafkaProducer("my.topic.x");
            context.assertTrue(myTopicX_2.isPresent());

            // now, the more specific pattern should have matched
            context.assertEquals(pattern_1.pattern(), myTopicX_2.get().getRight().pattern());

            async.complete();
        });
    }

    @Test
    public void addKafkaProducer(TestContext context) {
        repository.addKafkaProducer(new KafkaConfiguration(patternFrom("my.topic.*"), configs));

        context.assertTrue(repository.findMatchingKafkaProducer("my.topic.x").isPresent());
        context.assertFalse(repository.findMatchingKafkaProducer("my.other.topic.x").isPresent());
    }

    @Test
    public void closeAll(TestContext context) {
        Async async = context.async();

        repository.addKafkaProducer(new KafkaConfiguration(patternFrom("my.topic.*"), configs));
        repository.addKafkaProducer(new KafkaConfiguration(patternFrom("my.other.topic.*"), configs_2));

        context.assertTrue(repository.findMatchingKafkaProducer("my.topic.zz").isPresent());
        context.assertTrue(repository.findMatchingKafkaProducer("my.other.topic.zz").isPresent());

        repository.closeAll().future().onComplete(event -> {
            context.assertFalse(repository.findMatchingKafkaProducer("my.topic.zz").isPresent());
            context.assertFalse(repository.findMatchingKafkaProducer("my.other.topic.zz").isPresent());
            async.complete();
        });
    }

    private Pattern patternFrom(String regex){
        return Pattern.compile(regex);
    }
}
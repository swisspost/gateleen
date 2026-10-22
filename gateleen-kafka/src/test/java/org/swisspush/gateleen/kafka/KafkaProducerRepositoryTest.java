package org.swisspush.gateleen.kafka;

import io.vertx.core.Vertx;
import io.vertx.core.Context;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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

    private final Map<String, String> configs = new HashMap<>() {{
        put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        put("bootstrap.servers", "localhost:9092");
    }};

    private final Map<String, String> configs_2 = new HashMap<>() {{
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
        Async async = context.async();
        Pattern pattern_1 = patternFrom("my.topic.*");
        Pattern pattern_2 = patternFrom(".+");
        repository.addKafkaProducer(new KafkaConfiguration(pattern_1, configs))
                .compose(ignored -> repository.addKafkaProducer(new KafkaConfiguration(pattern_2, configs_2)))
                .onComplete(event -> {
                    context.assertTrue(event.succeeded());

                    final Optional<Pair<KafkaProducer<String, String>, Pattern>> anyTopic = repository.findMatchingKafkaProducer("anyTopic");
                    context.assertTrue(anyTopic.isPresent());
                    context.assertEquals(pattern_2.pattern(), anyTopic.get().getRight().pattern());

                    final Optional<Pair<KafkaProducer<String, String>, Pattern>> myTopicX = repository.findMatchingKafkaProducer("my.topic.x");
                    context.assertTrue(myTopicX.isPresent());
                    context.assertEquals(pattern_1.pattern(), myTopicX.get().getRight().pattern());
                    async.complete();
                });
    }

    @Test
    public void findMatchingKafkaProducerNonMatching(TestContext context) {
        Async async = context.async();
        Pattern pattern_1 = patternFrom("my.topic.*");
        Pattern pattern_2 = patternFrom("my.other.topic.*");
        repository.addKafkaProducer(new KafkaConfiguration(pattern_1, configs))
                .compose(ignored -> repository.addKafkaProducer(new KafkaConfiguration(pattern_2, configs_2)))
                .onComplete(event -> {
                    context.assertTrue(event.succeeded());
                    context.assertFalse(repository.findMatchingKafkaProducer("anyTopic").isPresent());
                    context.assertFalse(repository.findMatchingKafkaProducer("my.unknown.topic.x").isPresent());
                    context.assertFalse(repository.findMatchingKafkaProducer("abc.123").isPresent());
                    async.complete();
                });
    }

    @Test
    public void findMatchingKafkaProducerRespectInsertionOrder(TestContext context) {
        Async async = context.async();

        Pattern pattern_1 = patternFrom("my.topic.*");
        Pattern pattern_2 = patternFrom(".+");

        // add "more general" pattern first
        repository.addKafkaProducer(new KafkaConfiguration(pattern_2, configs_2))
                .compose(ignored -> repository.addKafkaProducer(new KafkaConfiguration(pattern_1, configs)))
                .onComplete(event -> {
                    context.assertTrue(event.succeeded());

                    final Optional<Pair<KafkaProducer<String, String>, Pattern>> myTopicX = repository.findMatchingKafkaProducer("my.topic.x");
                    context.assertTrue(myTopicX.isPresent());

                    // the "more general" pattern should have matched
                    context.assertEquals(pattern_2.pattern(), myTopicX.get().getRight().pattern());

                    // clear the repository to test a different ordering
                    repository.closeAll().future().onComplete(closeEvent -> {
                        context.assertTrue(closeEvent.succeeded());

                        // switch order by adding "more specific" pattern first
                        repository.addKafkaProducer(new KafkaConfiguration(pattern_1, configs))
                                .compose(ignored -> repository.addKafkaProducer(new KafkaConfiguration(pattern_2, configs_2)))
                                .onComplete(addEvent -> {
                                    context.assertTrue(addEvent.succeeded());

                                    final Optional<Pair<KafkaProducer<String, String>, Pattern>> myTopicX_2 = repository.findMatchingKafkaProducer("my.topic.x");
                                    context.assertTrue(myTopicX_2.isPresent());

                                    // now, the more specific pattern should have matched
                                    context.assertEquals(pattern_1.pattern(), myTopicX_2.get().getRight().pattern());

                                    async.complete();
                                });
                    });
                });
    }

    @Test
    public void addKafkaProducer(TestContext context) {
        Async async = context.async();
        repository.addKafkaProducer(new KafkaConfiguration(patternFrom("my.topic.*"), configs)).onComplete(event -> {
            context.assertTrue(event.succeeded());
            context.assertTrue(repository.findMatchingKafkaProducer("my.topic.x").isPresent());
            context.assertFalse(repository.findMatchingKafkaProducer("my.other.topic.x").isPresent());
            async.complete();
        });
    }

    @Test
    public void closeAll(TestContext context) {
        Async async = context.async();

        repository.addKafkaProducer(new KafkaConfiguration(patternFrom("my.topic.*"), configs))
                .compose(ignored -> repository.addKafkaProducer(new KafkaConfiguration(patternFrom("my.other.topic.*"), configs_2)))
                .onComplete(addEvent -> {
                    context.assertTrue(addEvent.succeeded());

                    context.assertTrue(repository.findMatchingKafkaProducer("my.topic.zz").isPresent());
                    context.assertTrue(repository.findMatchingKafkaProducer("my.other.topic.zz").isPresent());

                    repository.closeAll().future().onComplete(event -> {
                        context.assertFalse(repository.findMatchingKafkaProducer("my.topic.zz").isPresent());
                        context.assertFalse(repository.findMatchingKafkaProducer("my.other.topic.zz").isPresent());
                        async.complete();
                    });
                });
    }

    @Test
    public void producerCreationDoesNotBlockEventLoop(TestContext context) throws InterruptedException {
        CountDownLatch creationStarted = new CountDownLatch(1);
        CountDownLatch allowCreation = new CountDownLatch(1);
        CountDownLatch eventLoopCallbackRan = new CountDownLatch(1);
        AtomicBoolean factoryRanOnEventLoop = new AtomicBoolean(true);
        KafkaProducer<String, String> producer = org.mockito.Mockito.mock(KafkaProducer.class);
        KafkaProducerRepository asyncRepository = new KafkaProducerRepository(vertx, (ignored, configuration) -> {
            factoryRanOnEventLoop.set(Context.isOnEventLoopThread());
            creationStarted.countDown();
            try {
                if (!allowCreation.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to finish producer creation");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            return producer;
        });
        Async async = context.async();

        try {
            vertx.runOnContext(ignored -> {
                Context originalContext = Vertx.currentContext();
                var addFuture = asyncRepository.addKafkaProducer(new KafkaConfiguration(patternFrom("my.topic.*"), configs));
                addFuture.onComplete(event -> {
                    context.assertTrue(event.succeeded());
                    context.assertFalse(factoryRanOnEventLoop.get());
                    context.assertEquals(originalContext, Vertx.currentContext());
                    context.assertTrue(asyncRepository.findMatchingKafkaProducer("my.topic.x").isPresent());
                    async.complete();
                });
                vertx.setTimer(10, timerId -> {
                    try {
                        context.assertTrue(Context.isOnEventLoopThread());
                        context.assertEquals(0L, creationStarted.getCount());
                        context.assertFalse(addFuture.isComplete());
                    } finally {
                        allowCreation.countDown();
                        eventLoopCallbackRan.countDown();
                    }
                });
            });
            context.assertTrue(creationStarted.await(5, TimeUnit.SECONDS));
            context.assertTrue(eventLoopCallbackRan.await(5, TimeUnit.SECONDS));
        } finally {
            allowCreation.countDown();
        }
    }

    @Test
    public void producerFactoryFailureReturnsFailedFutureWithoutInsertion(TestContext context) {
        RuntimeException creationFailure = new RuntimeException("producer creation failed");
        KafkaProducerRepository failingRepository = new KafkaProducerRepository(vertx, (ignored, configuration) -> {
            throw creationFailure;
        });
        Async async = context.async();

        failingRepository.addKafkaProducer(new KafkaConfiguration(patternFrom("my.topic.*"), configs)).onComplete(event -> {
            context.assertTrue(event.failed());
            context.assertEquals(creationFailure, event.cause());
            context.assertFalse(failingRepository.findMatchingKafkaProducer("my.topic.x").isPresent());
            async.complete();
        });
    }

    private Pattern patternFrom(String regex){
        return Pattern.compile(regex);
    }
}

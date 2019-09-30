package org.swisspush.gateleen.kafka;

import io.vertx.core.*;
import io.vertx.kafka.client.producer.KafkaProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Pattern;

/**
 * A repository holding {@link KafkaProducer} instances for topics
 */
public class KafkaProducerRepository {

    private final Logger log = LoggerFactory.getLogger(KafkaProducerRepository.class);
    private Vertx vertx;
    private final Map<Pattern, KafkaProducer<String, String>> kafkaProducers;

    public KafkaProducerRepository(Vertx vertx) {
        this.vertx = vertx;
        this.kafkaProducers = new HashMap<>();
    }

    public void addKafkaProducer(KafkaConfiguration config){
        this.kafkaProducers.put(config.getTopic(), KafkaProducer.create(vertx, config.getConfigurations()));
    }

    public Optional<KafkaProducer<String, String>> getKafkaProducer(Pattern topic){
        return Optional.ofNullable(kafkaProducers.get(topic));
    }

    public Future<Void> closeAll(){
        Future<Void> future = Future.future();
        List<Future> futures = new ArrayList<>();

        for (Map.Entry<Pattern, KafkaProducer<String, String>> entry : kafkaProducers.entrySet()) {
            Future entryFuture = Future.future();
            futures.add(entryFuture);
            entry.getValue().close(new Handler<AsyncResult<Void>>() {
                @Override
                public void handle(AsyncResult<Void> event) {
                    if(event.succeeded()){
                        log.info("Successfully closed producer for topic '{}'", entry.getKey().pattern());
                    } else {
                        log.warn("Failed to close producer for topic '{}'", entry.getKey().pattern());
                    }
                    entryFuture.complete();
                }
            });
        }

        // wait for all producers to be closed
        CompositeFuture.all(futures).setHandler(event -> {
            kafkaProducers.clear();
            future.complete();
        });
        return future;
    }
}

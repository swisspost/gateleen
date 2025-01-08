package org.swisspush.gateleen.kafka;

import io.vertx.core.*;
import io.vertx.kafka.client.producer.KafkaProducer;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A repository holding {@link KafkaProducer} instances for topics
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class KafkaProducerRepository {

    private final Logger log = LoggerFactory.getLogger(KafkaProducerRepository.class);
    private final Vertx vertx;
    private final Map<Pattern, KafkaProducer<String, String>> kafkaProducers;

    public KafkaProducerRepository(Vertx vertx) {
        this.vertx = vertx;
        this.kafkaProducers = new LinkedHashMap<>(); // a linked hash map is used to keep the insertion order
    }

    void addKafkaProducer(KafkaConfiguration config) {
        log.info("About to add kafka producer from {}", config);
        this.kafkaProducers.put(config.getTopic(), KafkaProducer.create(vertx, config.getConfigurations()));
    }

    Optional<Pair<KafkaProducer<String, String>, Pattern>> findMatchingKafkaProducer(String topic) {
        for (Map.Entry<Pattern, KafkaProducer<String, String>> entry : kafkaProducers.entrySet()) {
            Matcher matcher = entry.getKey().matcher(topic);
            if (matcher.matches()) {
                log.debug("Found matching KafkaProducer with pattern '{}' for topic '{}' found", entry.getKey().pattern(), topic);
                return Optional.of(Pair.of(entry.getValue(), entry.getKey()));
            }
        }
        log.info("No matching KafkaProducer for topic '{}' found", topic);
        return Optional.empty();
    }

    Promise<Void> closeAll() {
        log.info("About to close all kafka producers");
        Promise<Void> promise = Promise.promise();
        List<Future<Void>> futures = new ArrayList<>();

        for (Map.Entry<Pattern, KafkaProducer<String, String>> entry : kafkaProducers.entrySet()) {
            Promise<Void> entryFuture = Promise.promise();
            futures.add(entryFuture.future());
            entry.getValue().close(event -> {
                if (event.succeeded()) {
                    log.info("Successfully closed producer for topic '{}'", entry.getKey().pattern());
                } else {
                    log.warn("Failed to close producer for topic '{}'", entry.getKey().pattern());
                }
                entryFuture.complete();
            });
        }

        // wait for all producers to be closed
        Future.all(futures).onComplete(event -> {
            kafkaProducers.clear();
            promise.complete();
        });
        return promise;
    }
}

package org.swisspush.gateleen.kafka;

import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.kafka.client.producer.KafkaProducer;
import io.vertx.kafka.client.producer.KafkaProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static java.util.stream.Collectors.toList;

public class KafkaMessageSender {

    private static final Logger log = LoggerFactory.getLogger(KafkaMessageSender.class);

    Future<Void> sendMessages(KafkaProducer<String, String> kafkaProducer,
                              List<KafkaProducerRecord<String, String>> messages) {
        Promise<Void> promise = Promise.promise();
        log.debug("Start processing {} messages for kafka", messages.size());

        @SuppressWarnings("rawtypes") //https://github.com/eclipse-vertx/vert.x/issues/2627
        List<Future> futures = messages.stream()
                .map(message -> KafkaMessageSender.this.sendMessage(kafkaProducer, message))
                .collect(toList());

        CompositeFuture.all(futures).<Void>mapEmpty().onComplete(result -> {
            if (result.succeeded()) {
                promise.complete();
                log.debug("Batch messages successfully sent to Kafka.");
            } else {
                promise.fail(result.cause());
            }
        });

        return promise.future();
    }

    private Future<Void> sendMessage(KafkaProducer<String, String> kafkaProducer, KafkaProducerRecord<String, String> message) {
        Promise<Void> promise = Promise.promise();
        kafkaProducer.write(message, event -> {
            if (event.succeeded()) {
                if (message.key() != null) {
                    log.debug("Message with key '{}' successfully sent to kafka. Result: {}", message.key(), event);
                } else {
                    log.debug("Message without key successfully sent to kafka. Result: {}", event);
                }
                promise.complete();
            } else {
                log.warn("Failed to send message with key '{}' to kafka. Cause: {}", message.key(), event.cause());
                promise.fail(event.cause());
            }
        });
        return promise.future();
    }
}

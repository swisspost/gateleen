package org.swisspush.gateleen.kafka;

import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.kafka.client.producer.KafkaProducer;
import io.vertx.kafka.client.producer.KafkaProducerRecord;
import io.vertx.kafka.client.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Function;

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
        return kafkaProducer.send(message).compose((Function<RecordMetadata, Future<Void>>) metadata -> {
            log.debug("Message successfully sent to kafka topic {} on partition {} with offset {}. Timestamp: {}",
                    metadata.getTopic(), metadata.getPartition(), metadata.getOffset(), metadata.getTimestamp());
            return Future.succeededFuture();
        }).onFailure(event -> log.warn("Failed to send message with key '{}' to kafka. Cause: {}", message.key(), event));
    }
}

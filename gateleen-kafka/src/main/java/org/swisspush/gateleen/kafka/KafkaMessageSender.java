package org.swisspush.gateleen.kafka;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.kafka.client.producer.KafkaProducer;
import io.vertx.kafka.client.producer.KafkaProducerRecord;
import io.vertx.kafka.client.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static java.util.stream.Collectors.toList;

public class KafkaMessageSender {

    private static final Logger log = LoggerFactory.getLogger(KafkaMessageSender.class);

    private MeterRegistry meterRegistry;
    private final Map<String, Counter> successSendCounterMap = new HashMap<>();
    private final Map<String, Counter> failSendCounterMap = new HashMap<>();

    public static final String SUCCESS_SEND_MESSAGES_METRIC = "gateleen.kafka.send.success.messages";
    public static final String SUCCESS_SEND_MESSAGES_METRIC_DESCRIPTION = "Amount of successfully sent kafka messages";
    public static final String FAIL_SEND_MESSAGES_METRIC = "gateleen.kafka.send.fail.messages";
    public static final String FAIL_SEND_MESSAGES_METRIC_DESCRIPTION = "Amount of failed kafka message sendings";
    public static final String TOPIC = "topic";

    public void setMeterRegistry(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        successSendCounterMap.clear();
        failSendCounterMap.clear();
    }

    Future<Void> sendMessages(KafkaProducer<String, String> kafkaProducer,
                              List<KafkaProducerRecord<String, String>> messages) {
        Promise<Void> promise = Promise.promise();
        log.debug("Start processing {} messages for kafka", messages.size());

        List<Future<Void>> futures = messages.stream()
                .map(message -> KafkaMessageSender.this.sendMessage(kafkaProducer, message))
                .collect(toList());

        Future.all(futures).<Void>mapEmpty().onComplete(result -> {
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
            log.debug("Message successfully sent to kafka topic '{}' on partition {} with offset {}. Timestamp: {}",
                    metadata.getTopic(), metadata.getPartition(), metadata.getOffset(), metadata.getTimestamp());
            incrementSuccessCount(metadata.getTopic());
            return Future.succeededFuture();
        }).onFailure(throwable -> {
            log.warn("Failed to send message with key '{}' to kafka. Cause: {}", message.key(), throwable);
            incrementFailCount1(message.topic());
        });
    }

    private void incrementSuccessCount(String topic) {
        Counter counter = successSendCounterMap.get(topic);
        if(counter != null) {
            counter.increment();
            return;
        }

        if(meterRegistry != null) {
            Counter newCounter = Counter.builder(SUCCESS_SEND_MESSAGES_METRIC)
                    .description(SUCCESS_SEND_MESSAGES_METRIC_DESCRIPTION)
                    .tag(TOPIC, topic)
                    .register(meterRegistry);
            newCounter.increment();
            successSendCounterMap.put(topic, newCounter);
        }
    }

    private void incrementFailCount1(String topic) {
        Counter counter = failSendCounterMap.get(topic);
        if(counter != null) {
            counter.increment();
            return;
        }

        if(meterRegistry != null) {
            Counter newCounter = Counter.builder(FAIL_SEND_MESSAGES_METRIC)
                    .description(FAIL_SEND_MESSAGES_METRIC_DESCRIPTION)
                    .tag(TOPIC, topic)
                    .register(meterRegistry);
            newCounter.increment();
            failSendCounterMap.put(topic, newCounter);
        }
    }
}

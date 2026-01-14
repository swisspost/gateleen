package org.swisspush.gateleen.kafka;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.kafka.client.producer.KafkaProducer;
import io.vertx.kafka.client.producer.KafkaProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static java.util.stream.Collectors.toList;

public class KafkaMessageSender {

    private static final Logger log = LoggerFactory.getLogger(KafkaMessageSender.class);
    private final Vertx vertx;

    private MeterRegistry meterRegistry;
    private final Map<String, Counter> successSendCounterMap = new HashMap<>();
    private final Map<String, Counter> failSendCounterMap = new HashMap<>();

    public static final String SUCCESS_SEND_MESSAGES_METRIC = "gateleen.kafka.send.success.messages";
    public static final String SUCCESS_SEND_MESSAGES_METRIC_DESCRIPTION = "Amount of successfully sent kafka messages";
    public static final String FAIL_SEND_MESSAGES_METRIC = "gateleen.kafka.send.fail.messages";
    public static final String FAIL_SEND_MESSAGES_METRIC_DESCRIPTION = "Amount of failed kafka message sendings";
    public static final String TOPIC = "topic";

    public KafkaMessageSender(Vertx vertx) {
        this.vertx = vertx;
    }

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
        final Promise<Void> promise = Promise.promise();
        kafkaProducer.send(message).compose(metadata -> {
            log.debug("Message successfully sent to kafka topic '{}' on partition {} with offset {}. Timestamp: {}",
                    metadata.getTopic(), metadata.getPartition(), metadata.getOffset(), metadata.getTimestamp());
            return vertx.executeBlocking((Callable<Void>) () -> {
                incrementSuccessCount(metadata.getTopic());
                return null;
            });
        }).onFailure(throwable -> {
            log.warn("Failed to send message with key '{}' to kafka. Cause: {}", message.key(), throwable);
            vertx.executeBlocking((Callable<Void>) () -> {
                incrementFailCount1(message.topic());
                return null;
            }).onComplete(event -> promise.fail(throwable));

        }).onSuccess(event -> promise.complete());
        return promise.future();
    }

    private synchronized void incrementSuccessCount(String topic) {
        Counter counter = successSendCounterMap.get(topic);
        if (counter != null) {
            counter.increment();
            return;
        }

        if (meterRegistry != null) {
            Counter newCounter = Counter.builder(SUCCESS_SEND_MESSAGES_METRIC)
                    .description(SUCCESS_SEND_MESSAGES_METRIC_DESCRIPTION)
                    .tag(TOPIC, topic)
                    .register(meterRegistry);
            newCounter.increment();
            successSendCounterMap.put(topic, newCounter);
        }
    }

    private synchronized void incrementFailCount1(String topic) {
        Counter counter = failSendCounterMap.get(topic);
        if (counter != null) {
            counter.increment();
            return;
        }

        if (meterRegistry != null) {
            Counter newCounter = Counter.builder(FAIL_SEND_MESSAGES_METRIC)
                    .description(FAIL_SEND_MESSAGES_METRIC_DESCRIPTION)
                    .tag(TOPIC, topic)
                    .register(meterRegistry);
            newCounter.increment();
            failSendCounterMap.put(topic, newCounter);
        }
    }
}

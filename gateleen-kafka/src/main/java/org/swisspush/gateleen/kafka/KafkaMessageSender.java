package org.swisspush.gateleen.kafka;

import io.vertx.core.Future;
import io.vertx.kafka.client.producer.KafkaProducer;
import io.vertx.kafka.client.producer.KafkaProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.future.SequentialFutures;

import java.util.List;

public class KafkaMessageSender {

    private static final Logger log = LoggerFactory.getLogger(KafkaMessageSender.class);

    public Future<Void> sendMessages(KafkaProducer<String, String> kafkaProducer,
                                     List<KafkaProducerRecord<String, String>> messages){
        Future<Void> future = Future.future();
        Future<Void> f = Future.succeededFuture();

        SequentialFutures.execute(messages.iterator(), f,
                (f1, message) -> f1.compose(ignore -> KafkaMessageSender.this.sendMessage(kafkaProducer, message)))
                .setHandler(res -> {
                    if(res.succeeded()) {
                        future.complete();
                    } else {
                        future.fail(res.cause());
                    }
                });

        return future;
    }

    private Future<Void> sendMessage(KafkaProducer<String, String> kafkaProducer, KafkaProducerRecord<String, String> message){
        Future<Void> f = Future.future();
        kafkaProducer.write(message, event -> {
            if(event.succeeded()){
                if (message.key() != null){
                    log.debug("Message with key '{}' successfully sent to kafka. Result: {}", message.key(), event.result().toJson());
                } else {
                    log.debug("Message without key successfully sent to kafka. Result: {}", event.result().toJson());
                }
                f.complete();
            } else {
                log.info("Failed to send message with key '{}' to kafka. Cause: {}", message.key(), event.cause());
                f.fail(event.cause());
            }
        });
        return f;
    }
}

package org.swisspush.gateleen.kafka;

import io.vertx.core.Future;
import io.vertx.kafka.client.producer.KafkaProducer;
import io.vertx.kafka.client.producer.KafkaProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class KafkaMessageSender {

    private static final Logger log = LoggerFactory.getLogger(KafkaMessageSender.class);

    Future<Void> sendMessages(KafkaProducer<String, String> kafkaProducer,
                                     List<KafkaProducerRecord<String, String>> messages){
        Future<Void> future = Future.future();
        List<Future<Void>> futureList = new ArrayList<>();

        for (KafkaProducerRecord<String, String> message : messages) {
            log.debug("Start processing {} messages for kafka", messages.size());
            Future<Void> futureEntry = sendMessage(kafkaProducer, message);
            futureList.add(futureEntry);
        }

        kafkaProducer.flush(event -> {});

        final int[] remaining = {futureList.size()};

        for (Future<Void> voidFuture : futureList) {
           voidFuture.setHandler(event -> {
               if (event.succeeded()) {
                   remaining[0] -= 1;

                   if (remaining[0] == 0) {
                       future.complete();
                       log.debug("Batch messages successfully sent to kafka.");
                   }
               } else {
                   future.fail(event.cause());
               }
           });
        }

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
                log.warn("Failed to send message with key '{}' to kafka. Cause: {}", message.key(), event.cause());
                f.fail(event.cause());
            }
        });
        return f;
    }
}

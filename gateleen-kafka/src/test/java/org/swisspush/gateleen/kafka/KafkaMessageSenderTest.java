package org.swisspush.gateleen.kafka;

import io.vertx.core.*;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import io.vertx.kafka.client.producer.KafkaProducer;
import io.vertx.kafka.client.producer.KafkaProducerRecord;
import io.vertx.kafka.client.producer.RecordMetadata;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.swisspush.gateleen.validation.ValidationException;

import java.util.*;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.swisspush.gateleen.kafka.KafkaProducerRecordBuilder.buildRecordsBlocking;

/**
 * Test class for the {@link KafkaMessageSender}
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@SuppressWarnings(value="unchecked")
@RunWith(VertxUnitRunner.class)
public class KafkaMessageSenderTest {

    private KafkaProducer<String, String> producer;
    private KafkaMessageSender kafkaMessageSender;

    @Before
    public void setUp() {
        producer = Mockito.mock(KafkaProducer.class);
        kafkaMessageSender = new KafkaMessageSender();
    }

    @Test
    public void sendSingleMessage(TestContext context) throws ValidationException {
        Async async = context.async();
        String topic = "myTopic";
        final List<KafkaProducerRecord<String, String>> records =
                buildRecordsBlocking(topic, Buffer.buffer(buildSingleRecordPayload("someKey").encode()));

        when(producer.send(any())).thenReturn(Future.succeededFuture(new RecordMetadata(1,1,1, topic)));

        kafkaMessageSender.sendMessages(producer, records).onComplete(event -> {
            context.assertTrue(event.succeeded());
            async.complete();
        });

        Mockito.verify(producer, times(1)).send(eq(records.get(0)));
    }

    @Test
    public void sendSingleMessageWithoutKey(TestContext context) throws ValidationException {
        Async async = context.async();
        String topic = "myTopic";
        final List<KafkaProducerRecord<String, String>> records =
                buildRecordsBlocking(topic, Buffer.buffer(buildSingleRecordPayload(null).encode()));

        when(producer.send(any())).thenReturn(Future.succeededFuture(new RecordMetadata(1,1,1, topic)));

        kafkaMessageSender.sendMessages(producer, records).onComplete(event -> {
            context.assertTrue(event.succeeded());
            async.complete();
        });

        Mockito.verify(producer, times(1)).send(eq(records.get(0)));
    }

    @Test
    public void sendMultipleMessages(TestContext context) throws ValidationException {
        Async async = context.async();
        String topic = "myTopic";
        final List<KafkaProducerRecord<String, String>> records =
                buildRecordsBlocking(topic, Buffer.buffer(buildThreeRecordsPayload("key_1", "key_2", "key_3").encode()));

        when(producer.send(any())).thenReturn(Future.succeededFuture(new RecordMetadata(1,1,1, topic)));

        kafkaMessageSender.sendMessages(producer, records).onComplete(event -> {
            context.assertTrue(event.succeeded());
            async.complete();
        });

        ArgumentCaptor<KafkaProducerRecord> recordCaptor = ArgumentCaptor.forClass(KafkaProducerRecord.class);
        Mockito.verify(producer, times(3)).send(recordCaptor.capture());

        // verify the correct order of the message transmission
        context.assertEquals(3, recordCaptor.getAllValues().size());
        context.assertEquals(records.get(0), recordCaptor.getAllValues().get(0));
        context.assertEquals(records.get(1), recordCaptor.getAllValues().get(1));
        context.assertEquals(records.get(2), recordCaptor.getAllValues().get(2));
    }

    @Test
    public void sendMultipleMessagesWithFailingMessage(TestContext context) throws ValidationException {
        Async async = context.async();
        String topic = "myTopic";
        final List<KafkaProducerRecord<String, String>> records =
                buildRecordsBlocking(topic, Buffer.buffer(buildThreeRecordsPayload("key_1", "key_2", "key_3").encode()));

        when(producer.send(any())).thenReturn(Future.succeededFuture(new RecordMetadata(1,1,1, topic)));
        when(producer.send(eq(records.get(1)))).thenReturn(Future.failedFuture("Message with key '" + records.get(1).key() + "' failed."));

        kafkaMessageSender.sendMessages(producer, records).onComplete(event -> {
            context.assertFalse(event.succeeded());
            context.assertEquals("Message with key 'key_2' failed.", event.cause().getMessage());
            async.complete();
        });

        ArgumentCaptor<KafkaProducerRecord> recordCaptor = ArgumentCaptor.forClass(KafkaProducerRecord.class);
        Mockito.verify(producer, times(3)).send(recordCaptor.capture());

        // verify only the first two messages was sent
        context.assertEquals(3, recordCaptor.getAllValues().size());
        context.assertEquals(records.get(0), recordCaptor.getAllValues().get(0));
        context.assertEquals(records.get(1), recordCaptor.getAllValues().get(1));
        context.assertEquals(records.get(2), recordCaptor.getAllValues().get(2));
    }

    private JsonObject buildSingleRecordPayload(String key){
        JsonObject payload = new JsonObject();
        JsonArray records = new JsonArray();
        payload.put("records", records);
        JsonObject record = new JsonObject();
        records.add(record);
        if(key != null) {
            record.put("key", key);
        }
        JsonObject value = new JsonObject();
        value.put("k1", "v1");
        value.put("k2", 99);
        record.put("value", value);
        return payload;
    }

    private JsonObject buildThreeRecordsPayload(String key, String key2, String key3){
        JsonObject payload = new JsonObject();
        JsonArray records = new JsonArray();
        payload.put("records", records);
        JsonObject record = new JsonObject();
        records.add(record);
        if(key != null) {
            record.put("key", key);
        }
        JsonObject value = new JsonObject();
        value.put("k1", "v1");
        value.put("k2", 99);
        record.put("value", value);

        JsonObject record2 = new JsonObject();
        records.add(record2);
        if(key2 != null) {
            record2.put("key", key2);
        }
        JsonObject value2 = new JsonObject();
        value2.put("k1", "v2");
        value2.put("k2", 55);
        record2.put("value", value2);

        JsonObject record3 = new JsonObject();
        records.add(record3);
        if(key3 != null) {
            record3.put("key", key3);
        }
        JsonObject value3 = new JsonObject();
        value3.put("k1", "v3");
        value3.put("k2", 22);
        record3.put("value", value3);

        return payload;
    }
}

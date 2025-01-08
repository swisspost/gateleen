package org.swisspush.gateleen.kafka;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.MultiMap;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import io.vertx.kafka.client.producer.KafkaProducerRecord;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.swisspush.gateleen.core.util.JsonObjectUtils;
import org.swisspush.gateleen.validation.ValidationException;

import java.util.List;

import static org.junit.Assert.assertThrows;
import static org.swisspush.gateleen.kafka.KafkaProducerRecordBuilder.buildRecords;

/**
 * Test class for the {@link KafkaProducerRecordBuilder}
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class KafkaProducerRecordBuilderTest {

    @Test
    public void buildRecordsInvalidJson(TestContext context) {
        Exception exception = assertThrows(ValidationException.class, () -> {
            buildRecords("myTopic", Buffer.buffer("notValidJson"));
        });

        context.assertEquals("Error while parsing payload", exception.getMessage());
    }

    @Test
    public void buildRecordsMissingRecordsArray(TestContext context) {
        Exception exception = assertThrows(ValidationException.class, () -> {
            buildRecords("myTopic", Buffer.buffer("{}"));
        });

        context.assertEquals("Missing 'records' array", exception.getMessage());
    }

    @Test
    public void buildRecordsNotArray(TestContext context) {
        Exception exception = assertThrows(ValidationException.class, () -> {
            buildRecords("myTopic", Buffer.buffer("{\"records\": \"shouldBeAnArray\"}"));
        });

        context.assertEquals("Property 'records' must be of type JsonArray holding JsonObject objects", exception.getMessage());
    }

    @Test
    public void buildRecordsInvalidRecordsType(TestContext context) {
        Exception exception = assertThrows(ValidationException.class, () -> {
            buildRecords("myTopic", Buffer.buffer("{\"records\": [123]}"));
        });

        context.assertEquals("Property 'records' must be of type JsonArray holding JsonObject objects", exception.getMessage());
    }

    @Test
    public void buildRecordsEmptyRecordsArray(TestContext context) throws ValidationException {
        final List<KafkaProducerRecord<String, String>> records =
                buildRecords("myTopic", Buffer.buffer("{\"records\": []}"));
        context.assertTrue(records.isEmpty());
    }

    @Test
    public void buildRecordsInvalidKeyType(TestContext context) {
        Exception exception = assertThrows(ValidationException.class, () -> {
            buildRecords("myTopic", Buffer.buffer("{\"records\": [{\"key\": 123,\"value\": {}}]}"));
        });

        context.assertEquals("Property 'key' must be of type String", exception.getMessage());
    }

    @Test
    public void buildRecordsInvalidValueType(TestContext context) {
        Exception exception = assertThrows(ValidationException.class, () -> {
            buildRecords("myTopic", Buffer.buffer("{\"records\":[{\"value\":123}]}"));
        });

        context.assertEquals("Property 'value' must be of type JsonObject", exception.getMessage());
    }

    @Test
    public void buildRecordsMissingValue(TestContext context) {
        Exception exception = assertThrows(ValidationException.class, () -> {
            buildRecords("myTopic", Buffer.buffer("{\"records\":[{}]}"));
        });

        context.assertEquals("Property 'value' is required", exception.getMessage());
    }

    @Test
    public void buildRecordsInvalidHeadersType(TestContext context) {
        Exception exception = assertThrows(ValidationException.class, () -> {
            buildRecords("myTopic", Buffer.buffer("{\"records\": [{\"value\":{},\"headers\": 123}]}"));
        });

        context.assertEquals("Property 'headers' must be of type JsonObject", exception.getMessage());
    }

    @Test
    public void buildRecordsInvalidHeadersValueType(TestContext context) {
        Exception exception = assertThrows(ValidationException.class, () -> {
            buildRecords("myTopic", Buffer.buffer("{\"records\": [{\"value\": {},\"headers\": {\"key\": 555}}]}"));
        });

        context.assertEquals("Property 'headers' must be of type JsonObject holding String values only", exception.getMessage());
    }

    @Test
    public void buildRecordsValidNoKeyNoHeaders(TestContext context) throws ValidationException {
        JsonObject payload = buildPayload(null, null);
        final List<KafkaProducerRecord<String, String>> records =
                buildRecords("myTopic", Buffer.buffer(payload.encode()));
        context.assertFalse(records.isEmpty());
        context.assertEquals(1, records.size());
        context.assertEquals("myTopic", records.get(0).topic());
        context.assertNull(records.get(0).key());
        context.assertEquals(payload.getJsonArray("records").getJsonObject(0).getJsonObject("value").encode(),
                records.get(0).value());
        context.assertTrue(records.get(0).headers().isEmpty());
    }

    @Test
    public void buildRecordsValidWithKeyNoHeaders(TestContext context) throws ValidationException {
        JsonObject payload = buildPayload("someKey", null);
        final List<KafkaProducerRecord<String, String>> records =
                buildRecords("myTopic", Buffer.buffer(payload.encode()));
        context.assertFalse(records.isEmpty());
        context.assertEquals(1, records.size());
        context.assertEquals("myTopic", records.get(0).topic());
        context.assertEquals("someKey", records.get(0).key());
        context.assertEquals(payload.getJsonArray("records").getJsonObject(0).getJsonObject("value").encode(),
                records.get(0).value());
        context.assertTrue(records.get(0).headers().isEmpty());
    }

    @Test
    public void buildRecordsValidWithKeyWithHeaders(TestContext context) throws ValidationException {
        JsonObject payload = buildPayload("someKey",
                MultiMap.caseInsensitiveMultiMap()
                        .add("header_1", "value_1")
                        .add("header_2", "value_2"));
        final List<KafkaProducerRecord<String, String>> records =
                buildRecords("myTopic", Buffer.buffer(payload.encode()));
        context.assertFalse(records.isEmpty());
        context.assertEquals(1, records.size());
        context.assertEquals("myTopic", records.get(0).topic());
        context.assertEquals("someKey", records.get(0).key());
        context.assertEquals(payload.getJsonArray("records").getJsonObject(0).getJsonObject("value").encode(),
                records.get(0).value());
        context.assertFalse(records.get(0).headers().isEmpty());
        context.assertEquals(2, records.get(0).headers().size());
        context.assertEquals("header_1", records.get(0).headers().get(0).key());
        context.assertEquals("value_1", records.get(0).headers().get(0).value().toString());
        context.assertEquals("header_2", records.get(0).headers().get(1).key());
        context.assertEquals("value_2", records.get(0).headers().get(1).value().toString());
    }

    private JsonObject buildPayload(String key, MultiMap headers){
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
        value.put("k2", new JsonArray().add(1).add(2).add(3));
        value.put("k3", 99);
        value.put("k3", new JsonObject().put("kk1", "vv1"));
        record.put("value", value);

        if(headers != null){
            record.put("headers", JsonObjectUtils.multiMapToJsonObject(headers));
        }

        return payload;
    }
}

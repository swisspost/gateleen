package org.swisspush.gateleen.kafka;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.MultiMap;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import io.vertx.kafka.client.producer.KafkaProducerRecord;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.swisspush.gateleen.core.util.JsonObjectUtils;
import org.swisspush.gateleen.validation.ValidationException;

import java.util.List;

import static org.swisspush.gateleen.kafka.KafkaProducerRecordBuilder.buildRecordsBlocking;

/**
 * Test class for the {@link KafkaProducerRecordBuilder}
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class KafkaProducerRecordBuilderTest {

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @Test
    public void buildRecordsInvalidJson() throws ValidationException {
        thrown.expect( ValidationException.class );
        thrown.expectMessage("Error while parsing payload");
        buildRecordsBlocking("myTopic", Buffer.buffer("notValidJson"));
    }

    @Test
    public void buildRecordsMissingRecordsArray() throws ValidationException {
        thrown.expect( ValidationException.class );
        thrown.expectMessage("Missing 'records' array");
        buildRecordsBlocking("myTopic", Buffer.buffer("{}"));
    }

    @Test
    public void buildRecordsNotArray() throws ValidationException {
        thrown.expect( ValidationException.class );
        thrown.expectMessage("Property 'records' must be of type JsonArray holding JsonObject objects");
        buildRecordsBlocking("myTopic", Buffer.buffer("{\"records\": \"shouldBeAnArray\"}"));
    }

    @Test
    public void buildRecordsInvalidRecordsType() throws ValidationException {
        thrown.expect( ValidationException.class );
        thrown.expectMessage("Property 'records' must be of type JsonArray holding JsonObject objects");
        buildRecordsBlocking("myTopic", Buffer.buffer("{\"records\": [123]}"));
    }

    @Test
    public void buildRecordsEmptyRecordsArray(TestContext context) throws ValidationException {
        final List<KafkaProducerRecord<String, String>> records =
                buildRecordsBlocking("myTopic", Buffer.buffer("{\"records\": []}"));
        context.assertTrue(records.isEmpty());
    }

    @Test
    public void buildRecordsInvalidKeyType() throws ValidationException {
        thrown.expect( ValidationException.class );
        thrown.expectMessage("Property 'key' must be of type String");
        buildRecordsBlocking("myTopic", Buffer.buffer("{\"records\": [{\"key\": 123,\"value\": {}}]}"));
    }

    @Test
    public void buildRecordsInvalidValueType() throws ValidationException {
        thrown.expect( ValidationException.class );
        thrown.expectMessage("Property 'value' must be of type JsonObject");
        buildRecordsBlocking("myTopic", Buffer.buffer("{\"records\":[{\"value\":123}]}"));
    }

    @Test
    public void buildRecordsMissingValue() throws ValidationException {
        thrown.expect( ValidationException.class );
        thrown.expectMessage("Property 'value' is required");
        buildRecordsBlocking("myTopic", Buffer.buffer("{\"records\":[{}]}"));
    }

    @Test
    public void buildRecordsInvalidHeadersType() throws ValidationException {
        thrown.expect( ValidationException.class );
        thrown.expectMessage("Property 'headers' must be of type JsonObject");
        buildRecordsBlocking("myTopic", Buffer.buffer("{\"records\": [{\"value\":{},\"headers\": 123}]}"));
    }

    @Test
    public void buildRecordsInvalidHeadersValueType() throws ValidationException {
        thrown.expect( ValidationException.class );
        thrown.expectMessage("Property 'headers' must be of type JsonObject holding String values only");
        buildRecordsBlocking("myTopic", Buffer.buffer("{\"records\": [{\"value\": {},\"headers\": {\"key\": 555}}]}"));
    }

    @Test
    public void buildRecordsValidNoKeyNoHeaders(TestContext context) throws ValidationException {
        JsonObject payload = buildPayload(null, null);
        final List<KafkaProducerRecord<String, String>> records =
                buildRecordsBlocking("myTopic", Buffer.buffer(payload.encode()));
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
                buildRecordsBlocking("myTopic", Buffer.buffer(payload.encode()));
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
                buildRecordsBlocking("myTopic", Buffer.buffer(payload.encode()));
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

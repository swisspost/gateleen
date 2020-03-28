package org.swisspush.gateleen.kafka;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.kafka.client.producer.KafkaProducerRecord;
import org.swisspush.gateleen.validation.ValidationException;

import java.util.ArrayList;
import java.util.List;

/**
 * Creates {@link KafkaProducerRecord}s by parsing the request payload.
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
class KafkaProducerRecordBuilder {

    private static final String RECORDS = "records";
    private static final String KEY = "key";
    private static final String VALUE = "value";
    private static final String HEADERS = "headers";

    /**
     * Builds a list of {@link KafkaProducerRecord}s based on the provided payload.
     * When the payload is not valid (because of missing properties, wrong types, etc.), a {@link ValidationException}
     * will be thrown holding a description of the error.
     *
     * @param topic the kafka topic
     * @param payload the payload to build the {@link KafkaProducerRecord}s from
     * @return A list of {@link KafkaProducerRecord}s created from the provided payload
     * @throws ValidationException when the payload is not valid (missing properties, wrong types, etc.)
     */
    static List<KafkaProducerRecord<String, String>> buildRecords(String topic, Buffer payload) throws ValidationException {
        List<KafkaProducerRecord<String, String>> kafkaProducerRecords = new ArrayList<>();
        JsonObject payloadObj;
        try {
            payloadObj = new JsonObject(payload);
        } catch (DecodeException de){
            throw new ValidationException("Error while parsing payload");
        }

        JsonArray recordsArray;
        try {
            recordsArray = payloadObj.getJsonArray(RECORDS);
            if(recordsArray == null){
                throw new ValidationException("Missing 'records' array");
            }
            for (int i = 0; i < recordsArray.size(); i++) {
                kafkaProducerRecords.add(fromRecordJsonObject(topic, recordsArray.getJsonObject(i)));
            }
        } catch (ClassCastException cce) {
            throw new ValidationException("Property '"+RECORDS+"' must be of type JsonArray holding JsonObject objects");
        }

        return kafkaProducerRecords;
    }

    private static KafkaProducerRecord<String, String> fromRecordJsonObject(String topic, JsonObject recordObj) throws ValidationException {
        // when key is null, messages are sent in a round robin fashion across all the partitions of the topic
        String key;
        try {
            key = recordObj.getString(KEY);
        } catch (ClassCastException cce){
            throw new ValidationException("Property '"+KEY+"' must be of type String");
        }

        JsonObject valueObj;
        try {
            valueObj = recordObj.getJsonObject(VALUE);
        } catch (ClassCastException cce) {
            throw new ValidationException("Property '"+VALUE+"' must be of type JsonObject");
        }

        if(valueObj == null){
            throw new ValidationException("Property '"+VALUE+"' is required");
        }
        String value = valueObj.encode();

        KafkaProducerRecord<String, String> record = KafkaProducerRecord.create(topic, key, value);

        JsonObject headers;
        try {
            headers = recordObj.getJsonObject(HEADERS);
        } catch (ClassCastException cce) {
            throw new ValidationException("Property '"+HEADERS+"' must be of type JsonObject");
        }

        if(headers != null) {
            try {
                for (String headerName : headers.fieldNames()) {
                    record.addHeader(headerName, headers.getString(headerName));
                }
            } catch (ClassCastException cce) {
                throw new ValidationException("Property '" + HEADERS + "' must be of type JsonObject holding String values only");
            }
        }
        return record;
    }
}

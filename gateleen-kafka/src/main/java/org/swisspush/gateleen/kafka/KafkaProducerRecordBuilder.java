package org.swisspush.gateleen.kafka;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.kafka.client.producer.KafkaProducerRecord;
import org.slf4j.Logger;
import org.swisspush.gateleen.core.exception.GateleenExceptionFactory;
import org.swisspush.gateleen.validation.ValidationException;

import java.util.ArrayList;
import java.util.List;

import static java.lang.System.currentTimeMillis;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Creates {@link KafkaProducerRecord}s by parsing the request payload.
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
class KafkaProducerRecordBuilder {

    private static final Logger log = getLogger(KafkaProducerRecordBuilder.class);
    private static final String RECORDS = "records";
    private static final String KEY = "key";
    private static final String VALUE = "value";
    private static final String HEADERS = "headers";
    private final Vertx vertx;
    private final GateleenExceptionFactory exceptionFactory;

    KafkaProducerRecordBuilder(
        Vertx vertx,
        GateleenExceptionFactory exceptionFactory
    ) {
        this.vertx = vertx;
        this.exceptionFactory = exceptionFactory;
    }

    /**
     * Builds a list of {@link KafkaProducerRecord}s based on the provided payload.
     * When the payload is not valid (because of missing properties, wrong types, etc.), a {@link ValidationException}
     * will be thrown holding a description of the error.
     *
     * @param topic   the kafka topic
     * @param payload the payload to build the {@link KafkaProducerRecord}s from
     * @return A list of {@link KafkaProducerRecord}s created from the provided payload
     * @throws ValidationException when the payload is not valid (missing properties, wrong types, etc.)
     */
    Future<List<KafkaProducerRecord<String, String>>> buildRecordsAsync(String topic, Buffer payload) {
        return Future.<Void>succeededFuture().compose((Void v) -> vertx.executeBlocking(() -> {
            long beginEpchMs = currentTimeMillis();
            JsonObject payloadObj;
            try {
                payloadObj = new JsonObject(payload);
            } catch (DecodeException de) {
                throw new ValidationException("Error while parsing payload", de);
            }
            JsonArray recordsArray;
            try {
                recordsArray = payloadObj.getJsonArray(RECORDS);
            } catch (ClassCastException cce) {
                throw new ValidationException("Property '" + RECORDS + "' must be of type JsonArray holding JsonObject objects");
            }
            if (recordsArray == null) {
                throw new ValidationException("Missing 'records' array");
            }
            List<KafkaProducerRecord<String, String>> kafkaProducerRecords = new ArrayList<>(recordsArray.size());
            for (int i = 0; i < recordsArray.size(); i++) {
                kafkaProducerRecords.add(fromRecordJsonObject(topic, recordsArray.getJsonObject(i)));
            }
            long durationMs = currentTimeMillis() - beginEpchMs;
            log.debug("Parsing and Serializing JSON did block thread for {}ms", durationMs);
            return kafkaProducerRecords;
        }));
    }

    /** @deprecated Use {@link #buildRecordsAsync(String, Buffer)}. */
    @Deprecated
    static List<KafkaProducerRecord<String, String>> buildRecords(String topic, Buffer payload) throws ValidationException {
        List<KafkaProducerRecord<String, String>> kafkaProducerRecords = new ArrayList<>();
        JsonObject payloadObj;
        try {
            payloadObj = new JsonObject(payload);
        } catch (DecodeException de) {
            throw new ValidationException("Error while parsing payload");
        }

        JsonArray recordsArray;
        try {
            recordsArray = payloadObj.getJsonArray(RECORDS);
            if (recordsArray == null) {
                throw new ValidationException("Missing 'records' array");
            }
            for (int i = 0; i < recordsArray.size(); i++) {
                kafkaProducerRecords.add(fromRecordJsonObject(topic, recordsArray.getJsonObject(i)));
            }
        } catch (ClassCastException cce) {
            throw new ValidationException("Property '" + RECORDS + "' must be of type JsonArray holding JsonObject objects");
        }

        return kafkaProducerRecords;
    }

    private static KafkaProducerRecord<String, String> fromRecordJsonObject(String topic, JsonObject recordObj) throws ValidationException {
        // when key is null, messages are sent in a round robin fashion across all the partitions of the topic
        String key;

        key = recordObj.getString(KEY);
        if (key != null && !(recordObj.getValue(KEY) instanceof String)) {
            throw new ValidationException("Property '" + KEY + "' must be of type String");
        }

        JsonObject valueObj;
        try {
            valueObj = recordObj.getJsonObject(VALUE);
        } catch (ClassCastException cce) {
            throw new ValidationException("Property '" + VALUE + "' must be of type JsonObject");
        }

        if (valueObj == null) {
            throw new ValidationException("Property '" + VALUE + "' is required");
        }
        String value = valueObj.encode();

        KafkaProducerRecord<String, String> record = KafkaProducerRecord.create(topic, key, value);

        JsonObject headers;
        try {
            headers = recordObj.getJsonObject(HEADERS);
        } catch (ClassCastException cce) {
            throw new ValidationException("Property '" + HEADERS + "' must be of type JsonObject");
        }

        if (headers != null) {
            for (String headerName : headers.fieldNames()) {
                String headerValue = headers.getString(headerName);
                if (headerValue != null && !(headers.getValue(headerName) instanceof String)) {
                    throw new ValidationException("Property '" + HEADERS + "' must be of type JsonObject holding String values only");
                }
                record.addHeader(headerName, headerValue);
            }
        }
        return record;
    }
}

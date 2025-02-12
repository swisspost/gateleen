package org.swisspush.gateleen.core.configuration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.ValidationMessage;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.swisspush.gateleen.core.json.JsonUtil;
import org.swisspush.gateleen.core.util.StringUtils;
import org.swisspush.gateleen.core.validation.ValidationResult;
import org.swisspush.gateleen.core.validation.ValidationStatus;

import java.io.IOException;
import java.util.Set;

import static org.slf4j.LoggerFactory.getLogger;

/**
 * Validates the configuration resources
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
class ConfigurationResourceValidator {

    private static final String SCHEMA_DECLARATION = "http://json-schema.org/draft-04/schema#";
    private static final Logger log = getLogger(ConfigurationResourceValidator.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final JsonSchemaFactory JSON_SCHEMA_FACTORY = JsonSchemaFactory.getInstance();

    private final Vertx vertx;

    public ConfigurationResourceValidator(Vertx vertx) {
        this.vertx = vertx;
    }

    public void validateConfigurationResource(Buffer configurationResource, String resourceSchema,
                                              Handler<AsyncResult<ValidationResult>> resultHandler){
        vertx.executeBlocking(future -> {
            if(!JsonUtil.isValidJson(configurationResource)){
                String message = "Unable to parse json";
                log.warn(message);
                future.complete(new ValidationResult(ValidationStatus.VALIDATED_NEGATIV, message));
                return;
            }

            if(StringUtils.isEmpty(resourceSchema)){
                log.info("validated positive since no schema was provided");
                future.complete(new ValidationResult(ValidationStatus.VALIDATED_POSITIV));
                return;
            }

            JsonObject schemaObject;
            try {
                schemaObject = new JsonObject(resourceSchema);
            } catch(DecodeException ex) {
                String message = "Unable to parse json schema";
                log.warn(message);
                future.complete(new ValidationResult(ValidationStatus.VALIDATED_NEGATIV, message));
                return;
            }

            if(SCHEMA_DECLARATION.equals(schemaObject.getString("$schema"))) {
                JsonSchema schema;
                try {
                    schema = JSON_SCHEMA_FACTORY.getSchema(resourceSchema);
                } catch (Exception e) {
                    String message = "Cannot load schema";
                    log.warn(message, e);
                    future.complete(new ValidationResult(ValidationStatus.VALIDATED_NEGATIV, message));
                    return;
                }
                try {
                    JsonNode jsonNode = OBJECT_MAPPER.readTree(configurationResource.toString());
                    final Set<ValidationMessage> valMsgs = schema.validate(jsonNode);
                    if(valMsgs.isEmpty()) {
                        log.info("validated positive");
                        future.complete(new ValidationResult(ValidationStatus.VALIDATED_POSITIV));
                    } else {
                        JsonArray validationDetails = extractMessagesAsJson(valMsgs);
                        future.complete(new ValidationResult(ValidationStatus.VALIDATED_NEGATIV, "Validation failed", validationDetails));
                    }
                } catch (IOException e) {
                    String message = "Cannot read JSON";
                    log.warn("{}", message, e);
                    future.complete(new ValidationResult(ValidationStatus.VALIDATED_NEGATIV, message));
                }
            } else {
                String message = "Invalid schema: Expected property '$schema' with content '"+SCHEMA_DECLARATION+"'";
                log.warn("{}", message);
                future.complete(new ValidationResult(ValidationStatus.VALIDATED_NEGATIV, message));
            }
        }, resultHandler);
    }

    private JsonArray extractMessagesAsJson(Set<ValidationMessage> valMsgs){
        JsonArray resultArray = new JsonArray();
        for (ValidationMessage msg : valMsgs) {
            log.warn("{}", msg);
            resultArray.add(JsonObject.mapFrom(msg));
        }
        return resultArray;
    }
}

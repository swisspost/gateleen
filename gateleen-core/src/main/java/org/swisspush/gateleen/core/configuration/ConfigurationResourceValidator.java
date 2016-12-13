package org.swisspush.gateleen.core.configuration;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.fge.jsonschema.exceptions.ProcessingException;
import com.github.fge.jsonschema.main.JsonSchema;
import com.github.fge.jsonschema.main.JsonSchemaFactory;
import com.github.fge.jsonschema.report.ProcessingMessage;
import com.github.fge.jsonschema.report.ProcessingReport;
import com.github.fge.jsonschema.util.JsonLoader;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.LoggerFactory;
import org.swisspush.gateleen.core.json.JsonUtil;
import org.swisspush.gateleen.core.util.StringUtils;

import java.io.IOException;
import java.util.Iterator;

/**
 * Validates the configuration resources
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
class ConfigurationResourceValidator {

    private static final String SCHEMA_DECLARATION = "http://json-schema.org/draft-04/schema#";
    private static JsonSchemaFactory factory = JsonSchemaFactory.byDefault();
    private io.vertx.core.logging.Logger log = LoggerFactory.getLogger(ConfigurationResourceValidator.class);

    private Vertx vertx;

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
                    schema = factory.getJsonSchema(JsonLoader.fromString(resourceSchema));
                } catch (ProcessingException | IOException e) {
                    String message = "Cannot load schema";
                    log.warn(message, e);
                    future.complete(new ValidationResult(ValidationStatus.VALIDATED_NEGATIV, message));
                    return;
                }
                try {
                    ProcessingReport report = schema.validateUnchecked(JsonLoader.fromString(configurationResource.toString()));
                    if(report.isSuccess()) {
                        log.info("validated positive");
                        future.complete(new ValidationResult(ValidationStatus.VALIDATED_POSITIV));
                    } else {
                        JsonArray validationDetails = extractMessagesAsJson(report);
                        for(ProcessingMessage message: report) {
                            log.warn(message.getMessage());
                        }
                        future.complete(new ValidationResult(ValidationStatus.VALIDATED_NEGATIV, "Validation failed", validationDetails));
                    }
                } catch (IOException e) {
                    String message = "Cannot read JSON";
                    log.warn(message, e.getMessage());
                    future.complete(new ValidationResult(ValidationStatus.VALIDATED_NEGATIV, message));
                }
            } else {
                String message = "Invalid schema: Expected property '$schema' with content '"+SCHEMA_DECLARATION+"'";
                log.warn(message);
                future.complete(new ValidationResult(ValidationStatus.VALIDATED_NEGATIV, message));
            }
        }, resultHandler);
    }

    private JsonArray extractMessagesAsJson(ProcessingReport report){
        JsonArray resultArray = new JsonArray();
        Iterator it = report.iterator();
        while (it.hasNext()) {
            ProcessingMessage msg = (ProcessingMessage) it.next();
            JsonNode node = msg.asJson();
            resultArray.add(new JsonObject(node.toString()));
        }
        return resultArray;
    }

    enum ValidationStatus {
        VALIDATED_POSITIV,VALIDATED_NEGATIV,COULD_NOT_VALIDATE
    }

    class ValidationResult {

        private ValidationStatus status;
        private String message;
        private JsonArray validationDetails;

        public ValidationResult(ValidationStatus status, String message){
            this(status, message, null);
        }

        public ValidationResult(ValidationStatus status, String message, JsonArray validationDetails){
            this.status = status;
            this.message = message;
            this.validationDetails = validationDetails;
        }

        public ValidationResult(ValidationStatus success){
            this(success, null);
        }

        public String getMessage() {
            return message;
        }

        public JsonArray getValidationDetails() { return validationDetails; }

        public ValidationStatus getValidationStatus() { return status; }

        public boolean isSuccess() {
            return ValidationStatus.VALIDATED_POSITIV.equals(status);
        }
    }
}

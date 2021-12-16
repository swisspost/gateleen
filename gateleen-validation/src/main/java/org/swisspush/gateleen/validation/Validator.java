package org.swisspush.gateleen.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import io.vertx.core.json.Json;
import org.swisspush.gateleen.core.http.RequestLoggerFactory;
import org.swisspush.gateleen.core.json.JsonUtil;
import org.swisspush.gateleen.core.storage.ResourceStorage;
import org.swisspush.gateleen.core.util.StringUtils;
import com.google.common.base.Joiner;
import org.slf4j.Logger;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.swisspush.gateleen.core.validation.ValidationResult;
import org.swisspush.gateleen.core.validation.ValidationStatus;

import java.io.IOException;
import java.util.*;

/**
 * @author https://github.com/lbovet [Laurent Bovet]
 */
public class Validator {

    private static final String SCHEMA_DECLARATION = "http://json-schema.org/draft-04/schema#";
    private String schemaRoot;
    private ResourceStorage storage;
    private ValidationSchemaProvider schemaProvider;

    public Validator(ResourceStorage storage, String schemaRoot, ValidationSchemaProvider schemaProvider) {
        this.storage = storage;
        this.schemaRoot = schemaRoot;
        this.schemaProvider = schemaProvider;
    }

    public void validate(HttpServerRequest req, String type, Buffer jsonBuffer, SchemaLocation schemaLocation, Handler<ValidationResult> callback) {
        if (schemaLocation == null) {
            validate(req, type, jsonBuffer, callback);
        } else {
            final Logger log = RequestLoggerFactory.getLogger(Validator.class, req);
            if (!req.path().startsWith(schemaRoot)) {
                log.debug("Validating request");
                schemaProvider.schemaFromLocation(schemaLocation).setHandler(event -> {
                    if (event.failed()) {
                        callback.handle(new ValidationResult(ValidationStatus.COULD_NOT_VALIDATE,
                                "Error while getting schema. Cause: " + event.cause().getMessage()));
                        return;
                    }

                    Optional<JsonSchema> schemaOptional = event.result();
                    if (schemaOptional.isEmpty()) {
                        callback.handle(new ValidationResult(ValidationStatus.COULD_NOT_VALIDATE,
                                "No schema found in location " + schemaLocation.schemaLocation()));
                        return;
                    }

                    JsonSchema jsonSchema = schemaOptional.get();
                    performValidation(jsonSchema, log, schemaLocation.schemaLocation(), jsonBuffer, type, req.path(), callback);
                });
            }
        }
    }

    public void validate(HttpServerRequest req, String type, Buffer jsonBuffer, Handler<ValidationResult> callback) {
        final Logger log = RequestLoggerFactory.getLogger(Validator.class, req);
        if (!req.path().startsWith(schemaRoot)) {
            log.debug("Validating request");
            doValidate(jsonBuffer, req.path(), schemaRoot, type, (req.path().replaceFirst("^/", "") + "/" + type).split("/"), log, callback);
        }
    }

    private void doValidate(final Buffer jsonBuffer, final String path, final String base, final String type,
                            final String[] segments, final Logger log, final Handler<ValidationResult> callback) {
        storage.get(base, buffer -> {
            if (buffer != null) {
                String dataString = buffer.toString();
                JsonObject data = new JsonObject(dataString);
                String[] newSegments = segments.length > 0 && segments[0].equals("") ? Arrays.copyOfRange(segments, 1, segments.length) : segments;
                if (newSegments.length == 0) {
                    performValidation(dataString, data, log, base, jsonBuffer, type, path, callback);
                } else {
                    validateRecursively(data, newSegments, base, jsonBuffer, path, type, log, callback);
                }
            } else {
                log.warn("Could not get path {}", base);
                if (base.replaceFirst("/$", "").endsWith(path)) {
                    log.info("try again with lowdash instead of last segment (variableId)");
                    String baseWithLowDash = base.replaceFirst("[^/]*/$", "") + "_/";
                    Validator.this.doValidate(jsonBuffer, path, baseWithLowDash, type, segments, log, callback);
                } else {
                    callback.handle(new ValidationResult(ValidationStatus.COULD_NOT_VALIDATE, "Could not get path "
                            + base + " (_ was used to try for schemas with variable ids)"));
                }
            }
        });
    }

    private void validateRecursively(JsonObject data, String[] newSegments, String base, Buffer jsonBuffer, String path,
                                     String type, Logger log, Handler<ValidationResult> callback) {
        String newBase = base;
        String baseWithoutTrailingSlash = base.replaceFirst("/$", "");
        String arrayName = baseWithoutTrailingSlash.substring(baseWithoutTrailingSlash.lastIndexOf('/') + 1);
        JsonArray array = data.getJsonArray(arrayName);
        if (array != null && array.size() > 0) {
            if (array.contains(newSegments[0] + "/") || array.contains(newSegments[0])) {
                newBase = newBase + newSegments[0] + (newSegments.length > 1 ? "/" : "");
                doValidate(jsonBuffer, path, newBase, type, Arrays.copyOfRange(newSegments, 1, newSegments.length), log, callback);
            } else if (array.contains("_/") || array.contains("_")) {
                newBase = newBase + newSegments[0] + "/";
                doValidate(jsonBuffer, path, newBase, type, Arrays.copyOfRange(newSegments, 1, newSegments.length), log, callback);
            } else {
                String message = "No schema for " + path + " (" + type + ") [2]";
                log.warn(message);
                callback.handle(new ValidationResult(ValidationStatus.VALIDATED_NEGATIV, message));
            }
        } else {
            String message = "No schema for " + path + " (" + type + ") [3]";
            log.warn(message);
            callback.handle(new ValidationResult(ValidationStatus.VALIDATED_NEGATIV, message));
        }
    }

    public static ValidationResult validateStatic(Buffer dataToBeValidated, String schemaAsString, Logger log) {
        if (!JsonUtil.isValidJson(dataToBeValidated)) {
            return new ValidationResult(ValidationStatus.VALIDATED_NEGATIV, "Unable to parse json");
        }

        JsonObject schemaObject = new JsonObject(schemaAsString);
        if (SCHEMA_DECLARATION.equals(schemaObject.getString("$schema"))) {
            JsonSchema schema;
            try {
                schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V4).getSchema(schemaAsString);
            } catch (RuntimeException e) {
                String message = "Cannot load schema";
                log.warn(message, e);
                return new ValidationResult(ValidationStatus.VALIDATED_NEGATIV, message);
            }
            try {
                JsonNode jsonNode = new ObjectMapper().readTree(dataToBeValidated.getBytes());
                final Set<ValidationMessage> valMsgs = schema.validate(jsonNode);
                if (valMsgs.isEmpty()) {
                    return new ValidationResult(ValidationStatus.VALIDATED_POSITIV);
                } else {
                    JsonArray validationDetails = extractMessagesAsJson(valMsgs, log);
                    return new ValidationResult(ValidationStatus.VALIDATED_NEGATIV, "Validation failed", validationDetails);
                }
            } catch (IOException e) {
                String message = "Cannot read JSON";
                log.warn(message, e.getMessage());
                return new ValidationResult(ValidationStatus.VALIDATED_NEGATIV, message);
            }
        } else {
            String message = "Invalid schema: Expected property '$schema' with content '" + SCHEMA_DECLARATION + "'";
            log.warn(message);
            return new ValidationResult(ValidationStatus.VALIDATED_NEGATIV, message);
        }
    }

    private static void performValidation(JsonSchema schema, Logger log, String base, Buffer jsonBuffer, String type,
                                          String path, Handler<ValidationResult> callback) {
        try {
            JsonNode jsonNode = new ObjectMapper().readTree(jsonBuffer.getBytes());
            if (jsonNode == null) {
                throw new IOException("no vaild JSON object: " + jsonBuffer.toString());
            }
            final Set<ValidationMessage> valMsgs = schema.validate(jsonNode);
            if (valMsgs.isEmpty()) {
                log.debug("Valid ({})", type);
                log.debug("Used schema: {}", base);
                callback.handle(new ValidationResult(ValidationStatus.VALIDATED_POSITIV));
            } else {
                JsonArray validationDetails = extractMessagesAsJson(valMsgs, log);
                String messages = StringUtils.getStringOrEmpty(extractMessages(valMsgs));
                StringBuilder msgBuilder = new StringBuilder();
                msgBuilder.append("Invalid JSON for ")
                        .append(path).append(" (")
                        .append(type).append("). Messages: ")
                        .append(messages)
                        .append(" | Report: ").append(getReportAsString(valMsgs));

                if (log.isDebugEnabled()) {
                    msgBuilder.append(" | Validated JSON: ").append(jsonBuffer.toString());
                }

                log.warn(msgBuilder.toString());

                callback.handle(new ValidationResult(ValidationStatus.VALIDATED_NEGATIV, msgBuilder.toString(), validationDetails));
                log.warn("Used schema: {}", base);
            }
        } catch (IOException e) {
            String message = "Cannot read JSON " + " (" + type + ")";
            log.warn(message, e.getMessage());
            callback.handle(new ValidationResult(ValidationStatus.VALIDATED_NEGATIV, message));
        }
    }

    private static void performValidation(String dataString, JsonObject data, Logger log, String base, Buffer jsonBuffer,
                                          String type, String path, Handler<ValidationResult> callback) {
        if (SCHEMA_DECLARATION.equals(data.getString("$schema"))) {
            JsonSchema schema;
            try {
                schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V4).getSchema(dataString);
            } catch (RuntimeException e) {
                String message = "Cannot load schema " + base;
                log.warn(message, e);
                callback.handle(new ValidationResult(ValidationStatus.VALIDATED_NEGATIV, message));
                return;
            }
            performValidation(schema, log, base, jsonBuffer, type, path, callback);
        } else {
            String message = "No schema for " + path + " (" + type + ") [1]";
            log.warn(message);
            callback.handle(new ValidationResult(ValidationStatus.VALIDATED_NEGATIV, message));
        }
    }

    private static JsonArray extractMessagesAsJson(Set<ValidationMessage> valMsgs, Logger log) {
        JsonArray resultArray = new JsonArray();
        valMsgs.forEach(msg -> {
            if (log != null) {
                log.warn(msg.toString());
            }
            resultArray.add(JsonObject.mapFrom(msg));
        });
        return resultArray;
    }

    private static String extractMessages(Set<ValidationMessage> valMsgs) {
        List<String> messages = new ArrayList<>();
        valMsgs.forEach(msg -> {
            if (StringUtils.isNotEmpty(msg.getMessage())) {
                messages.add(msg.getMessage());
            }
        });
        if (messages.isEmpty()) {
            return null;
        }
        Joiner joiner = Joiner.on("; ").skipNulls();
        return joiner.join(messages);
    }

    private static String getReportAsString(Set<ValidationMessage> valMsgs) {
        List<String> messages = new ArrayList<>();
        valMsgs.forEach(msg -> {
            if (StringUtils.isNotEmpty(msg.getMessage())) {
                messages.add(Json.encodePrettily(msg));
            }
        });
        if (messages.isEmpty()) {
            return "no report available";
        }
        Joiner joiner = Joiner.on("; ").skipNulls();
        return joiner.join(messages);
    }
}

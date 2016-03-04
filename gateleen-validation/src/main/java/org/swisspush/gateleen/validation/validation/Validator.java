package org.swisspush.gateleen.validation.validation;

import com.fasterxml.jackson.databind.JsonNode;
import org.swisspush.gateleen.core.http.RequestLoggerFactory;
import org.swisspush.gateleen.core.json.JsonUtil;
import org.swisspush.gateleen.core.storage.ResourceStorage;
import org.swisspush.gateleen.core.util.StringUtils;
import com.github.fge.jsonschema.exceptions.ProcessingException;
import com.github.fge.jsonschema.main.JsonSchema;
import com.github.fge.jsonschema.main.JsonSchemaFactory;
import com.github.fge.jsonschema.report.ProcessingMessage;
import com.github.fge.jsonschema.report.ProcessingReport;
import com.github.fge.jsonschema.util.JsonLoader;
import com.google.common.base.Joiner;
import org.slf4j.Logger;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

/**
 * @author https://github.com/lbovet [Laurent Bovet]
 */
public class Validator {

    private static final String SCHEMA_DECLARATION = "http://json-schema.org/draft-04/schema#";
    private String schemaRoot;
	private ResourceStorage storage;
	private static JsonSchemaFactory factory = JsonSchemaFactory.byDefault();

	public Validator(ResourceStorage storage, String schemaRoot) {
        this.storage = storage;
        this.schemaRoot = schemaRoot;
	}

	public void validate(HttpServerRequest req, String type, Buffer jsonBuffer, Handler<ValidationResult> callback) {
		final Logger log = RequestLoggerFactory.getLogger(Validator.class, req);
		if(!req.path().startsWith(schemaRoot)) {
			log.debug("Validating request");
			doValidate(jsonBuffer, req.path(), schemaRoot, type, (req.path().replaceFirst("^/", "")+"/"+type).split("/"), log, callback);
		}
	}

    private void doValidate(final Buffer jsonBuffer, final String path, final String base, final String type, final String[] segments, final Logger log, final Handler<ValidationResult> callback) {
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
                log.warn("Could not get path " + base);
                if (base.replaceFirst("/$", "").endsWith(path)) {
                    log.info("try again with lowdash instead of last segment (variableId)");
                    String baseWithLowDash = base.replaceFirst("[^/]*/$", "") + "_/";
                    Validator.this.doValidate(jsonBuffer, path, baseWithLowDash, type, segments, log, callback);
                } else {
                    callback.handle(new ValidationResult(ValidationStatus.COULD_NOT_VALIDATE, "Could not get path " + base + " (_ was used to try for schemas with variable ids)"));
                }
            }
        });
    }

    private void validateRecursively(JsonObject data, String[] newSegments, String base, Buffer jsonBuffer, String path, String type, Logger log, Handler<ValidationResult> callback) {
        String newBase = base;
        String baseWithoutTrailingSlash = base.replaceFirst("/$",  "");
        String arrayName = baseWithoutTrailingSlash.substring(baseWithoutTrailingSlash.lastIndexOf('/')+1);
        JsonArray array = data.getJsonArray(arrayName);
        if(array != null && array.size() > 0) {
            if(array.contains(newSegments[0]+"/") || array.contains(newSegments[0])) {
                newBase = newBase + newSegments[0]+ ( newSegments.length > 1 ? "/" : "");
                doValidate(jsonBuffer, path, newBase, type, Arrays.copyOfRange(newSegments, 1, newSegments.length), log, callback);
            } else if(array.contains("_/") || array.contains("_")) {
                newBase = newBase + newSegments[0]+"/";
                doValidate(jsonBuffer, path, newBase, type, Arrays.copyOfRange(newSegments, 1, newSegments.length), log, callback);
            } else {
                String message = "No schema for "+path+" ("+type+") [2]";
                log.warn(message);
                callback.handle(new ValidationResult(ValidationStatus.VALIDATED_NEGATIV, message));
            }
        } else {
            String message = "No schema for "+path+" ("+type+") [3]";
            log.warn(message);
            callback.handle(new ValidationResult(ValidationStatus.VALIDATED_NEGATIV, message));
        }
    }

    public static ValidationResult validateStatic(Buffer dataToBeValidated, String schemaAsString, Logger log){
        if(!JsonUtil.isValidJson(dataToBeValidated)){
            return new ValidationResult(ValidationStatus.VALIDATED_NEGATIV, "Unable to parse json");
        }

        JsonObject schemaObject = new JsonObject(schemaAsString);
        if(SCHEMA_DECLARATION.equals(schemaObject.getString("$schema"))) {
            JsonSchema schema;
            try {
                schema = factory.getJsonSchema(JsonLoader.fromString(schemaAsString));
            } catch (ProcessingException | IOException e) {
                String message = "Cannot load schema";
                log.warn(message, e);
                return new ValidationResult(ValidationStatus.VALIDATED_NEGATIV, message);
            }
            try {
                ProcessingReport report = schema.validateUnchecked(JsonLoader.fromString(dataToBeValidated.toString()));
                if(report.isSuccess()) {
                    return new ValidationResult(ValidationStatus.VALIDATED_POSITIV);
                } else {
                    JsonArray validationDetails = extractMessagesAsJson(report);
                    for(ProcessingMessage message: report) {
                        log.warn(message.getMessage());
                    }
                    return new ValidationResult(ValidationStatus.VALIDATED_NEGATIV, "Validation failed", validationDetails);
                }
            } catch (IOException e) {
                String message = "Cannot read JSON";
                log.warn(message, e.getMessage());
                return new ValidationResult(ValidationStatus.VALIDATED_NEGATIV, message);
            }
        } else {
            String message = "Invalid schema: Expected property '$schema' with content '"+SCHEMA_DECLARATION+"'";
            log.warn(message);
            return new ValidationResult(ValidationStatus.VALIDATED_NEGATIV, message);
        }
    }

    private static void performValidation(String dataString, JsonObject data, Logger log, String base, Buffer jsonBuffer, String type, String path, Handler<ValidationResult> callback) {
        if(SCHEMA_DECLARATION.equals(data.getString("$schema"))) {
            JsonSchema schema;
            try {
                schema = factory.getJsonSchema(JsonLoader.fromString(dataString));
            } catch (ProcessingException | IOException e) {
                String message = "Cannot load schema " + base;
                log.warn(message, e);
                callback.handle(new ValidationResult(ValidationStatus.VALIDATED_NEGATIV, message));
                return;
            }
            try {
                ProcessingReport report = schema.validateUnchecked(JsonLoader.fromString(jsonBuffer.toString()));
                if(report.isSuccess()) {
                    log.debug("Valid ("+type+")");
                    log.debug("Used schema: "+base);
                    callback.handle(new ValidationResult(ValidationStatus.VALIDATED_POSITIV));
                } else {
                    String messages = StringUtils.getStringOrEmpty(extractMessages(report));
                    StringBuilder msgBuilder = new StringBuilder();
                    msgBuilder.append("Invalid JSON for ").append(path).append(" (").append(type).append("). Messages: ").append(messages);
                    if(log.isDebugEnabled()){
                        msgBuilder.append(" | Report: ").append(getReportAsString(report));
                        msgBuilder.append(" | Validated JSON: ").append(jsonBuffer.toString());
                    }
                    callback.handle(new ValidationResult(ValidationStatus.VALIDATED_NEGATIV, msgBuilder.toString()));
                    for(ProcessingMessage message: report) {
                        log.warn(message.getMessage());
                    }
                    log.warn("Used schema: "+base);
                }
            } catch (IOException e) {
                String message = "Cannot read JSON " + " (" + type + ")";
                log.warn(message, e.getMessage());
                callback.handle(new ValidationResult(ValidationStatus.VALIDATED_NEGATIV, message));
            }
        } else {
            String message = "No schema for " + path + " (" + type + ") [1]";
            log.warn(message);
            callback.handle(new ValidationResult(ValidationStatus.VALIDATED_NEGATIV, message));
        }
    }

    private static JsonArray extractMessagesAsJson(ProcessingReport report){
        JsonArray resultArray = new JsonArray();
        Iterator it = report.iterator();
        while (it.hasNext()) {
            ProcessingMessage msg = (ProcessingMessage) it.next();
            JsonNode node = msg.asJson();
            resultArray.add(new JsonObject(node.toString()));
        }
        return resultArray;
    }

    private static String extractMessages(ProcessingReport report){
        List<String> messages = new ArrayList<>();
        Iterator it = report.iterator();
        while (it.hasNext()) {
            ProcessingMessage msg = (ProcessingMessage) it.next();
            if(StringUtils.isNotEmpty(msg.getMessage())){
                messages.add(msg.getMessage());
            }
        }
        if(messages.isEmpty()){
            return null;
        }
        Joiner joiner = Joiner.on("; ").skipNulls();
        return joiner.join(messages);
    }

    private static String getReportAsString(ProcessingReport report){
        List<String> messages = new ArrayList<>();
        Iterator it = report.iterator();
        while (it.hasNext()) {
            ProcessingMessage msg = (ProcessingMessage) it.next();
            if(StringUtils.isNotEmpty(msg.getMessage())){
                messages.add(msg.asJson().toString());
            }
        }
        if(messages.isEmpty()){
            return "no report available";
        }
        Joiner joiner = Joiner.on("; ").skipNulls();
        return joiner.join(messages);
    }
}

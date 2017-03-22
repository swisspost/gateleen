package org.swisspush.gateleen.validation;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.logging.LoggableResource;
import org.swisspush.gateleen.core.logging.RequestLogger;
import org.swisspush.gateleen.core.storage.ResourceStorage;
import org.swisspush.gateleen.core.util.ResourcesUtils;
import org.swisspush.gateleen.core.util.StatusCode;
import org.swisspush.gateleen.core.util.StringUtils;
import org.swisspush.gateleen.core.validation.ValidationResult;

import java.util.HashMap;
import java.util.Map;

public class ValidationResourceManager implements LoggableResource {

    private static final String UPDATE_ADDRESS = "gateleen.validation-updated";

    private final String validationUri;
    private final ResourceStorage storage;
    private final Logger log = LoggerFactory.getLogger(ValidationResourceManager.class);
    private final Vertx vertx;
    private ValidationResource validationResource;
    private String validationResourceSchema;
    private boolean logConfigurationResourceChanges = false;

    public ValidationResource getValidationResource() {
        if (validationResource == null) {
            validationResource = new ValidationResource();
        }
        return validationResource;
    }

    public ValidationResourceManager(Vertx vertx, final ResourceStorage storage, String validationUri) {
        this.storage = storage;
        this.vertx = vertx;
        this.validationUri = validationUri;

        this.validationResourceSchema = ResourcesUtils.loadResource("gateleen_validation_schema_validation", true);

        updateValidationResource();

        // Receive update notifications
        vertx.eventBus().consumer(UPDATE_ADDRESS, (Handler<Message<Boolean>>) event -> updateValidationResource());
    }

    @Override
    public void enableResourceLogging(boolean resourceLoggingEnabled) {
        this.logConfigurationResourceChanges = resourceLoggingEnabled;
    }

    private void updateValidationResource() {
        storage.get(validationUri, buffer -> {
            if (buffer != null) {
                try {
                    updateValidationResource(buffer);
                } catch (ValidationException e) {
                    log.warn("Could not reconfigure validation resource", e);
                }
            } else {
                log.warn("Could not get URL '" + (validationUri == null ? "<null>" : validationUri) + "'.");
            }
        });
    }

    private void updateValidationResource(Buffer buffer) throws ValidationException {
        extractValidationValues(buffer);
        for (Map<String, String> resourceToValidate : getValidationResource().getResources()) {
            log.info("Applying validation for resource: " + resourceToValidate);
        }
        if(getValidationResource().getResources().isEmpty()){
            log.info("No validation rules to apply!");
        }
    }

    public boolean handleValidationResource(final HttpServerRequest request) {
        if (request.uri().equals(validationUri) && HttpMethod.PUT == request.method()) {
            request.bodyHandler(validationResourceBuffer -> {
                try {
                    extractValidationValues(validationResourceBuffer);
                } catch (ValidationException validationException) {
                    updateValidationResource();
                    log.error("Could not parse validation resource: " + validationException.toString());
                    request.response().setStatusCode(StatusCode.BAD_REQUEST.getStatusCode());
                    request.response().setStatusMessage(StatusCode.BAD_REQUEST.getStatusMessage() + " " + validationException.getMessage());
                    if(validationException.getValidationDetails() != null){
                        request.response().headers().add("content-type", "application/json");
                        request.response().end(validationException.getValidationDetails().encode());
                    } else {
                        request.response().end(validationException.getMessage());
                    }
                    return;
                }
                storage.put(validationUri, validationResourceBuffer, status -> {
                    if (status == StatusCode.OK.getStatusCode()) {
                        if(logConfigurationResourceChanges){
                            RequestLogger.logRequest(vertx.eventBus(), request, status, validationResourceBuffer);
                        }
                        vertx.eventBus().publish(UPDATE_ADDRESS, true);
                    } else {
                        request.response().setStatusCode(status);
                    }
                    request.response().end();
                });
            });
            return true;
        }

        if (request.uri().equals(validationUri) && HttpMethod.DELETE == request.method()) {
            getValidationResource().reset();
            log.info("Reset ValidationResource");
        }

        return false;
    }

    private void extractValidationValues(Buffer validationResourceBuffer) throws ValidationException {
        ValidationResult validationResult = Validator.validateStatic(validationResourceBuffer, validationResourceSchema, log);
        if(!validationResult.isSuccess()){
            throw new ValidationException(validationResult);
        }

        try {
            JsonObject validationRes = new JsonObject(validationResourceBuffer.toString("UTF-8"));
            getValidationResource().reset();

            JsonArray resourcesArray = validationRes.getJsonArray("resources");
            for (Object resourceEntry : resourcesArray) {
                JsonObject resJSO = (JsonObject) resourceEntry;
                Map<String, String> resProperties = new HashMap<>();

                String url = resJSO.getString(ValidationResource.URL_PROPERTY);
                resProperties.put(ValidationResource.URL_PROPERTY, url);

                String method = resJSO.getString(ValidationResource.METHOD_PROPERTY);
                if(!StringUtils.isEmpty(method)){
                    resProperties.put(ValidationResource.METHOD_PROPERTY, method);
                } else {
                    resProperties.put(ValidationResource.METHOD_PROPERTY, "PUT");
                }

                getValidationResource().addResource(resProperties);
            }
        } catch (Exception ex) {
            getValidationResource().reset();
            throw new ValidationException(ex);
        }
    }
}

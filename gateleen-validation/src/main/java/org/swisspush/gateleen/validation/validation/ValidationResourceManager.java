package org.swisspush.gateleen.validation.validation;

import org.swisspush.gateleen.core.storage.ResourceStorage;
import org.swisspush.gateleen.core.util.StatusCode;
import org.swisspush.gateleen.core.util.StringUtils;
import com.google.common.base.Joiner;
import io.vertx.core.http.HttpMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.*;

public class ValidationResourceManager {

    private static final String UPDATE_ADDRESS = "gateleen.validation-updated";

    private final String validationUri;

    private final ResourceStorage storage;

    private final Logger log = LoggerFactory.getLogger(ValidationResourceManager.class);

    private final Vertx vertx;

    private ValidationResource validationResource;

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

        updateValidationResource();

        // Receive update notifications
        vertx.eventBus().consumer(UPDATE_ADDRESS, (Handler<Message<Boolean>>) event -> updateValidationResource());
    }

    private void updateValidationResource() {
        storage.get(validationUri, buffer -> {
            if (buffer != null) {
                try {
                    updateValidationResource(buffer);
                } catch (IllegalArgumentException e) {
                    log.warn("Could not reconfigure validation resource", e);
                }
            } else {
                log.warn("Could not get URL '" + (validationUri == null ? "<null>" : validationUri) + "'.");
            }
        });
    }

    private void updateValidationResource(Buffer buffer) {
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
                } catch (IllegalArgumentException e) {
                    updateValidationResource();
                    log.error("Could not parse validation resource", e);
                    request.response().setStatusCode(StatusCode.BAD_REQUEST.getStatusCode());
                    request.response().setStatusMessage(StatusCode.BAD_REQUEST.getStatusMessage());
                    request.response().end(e.getMessage());
                    return;
                }
                storage.put(validationUri, validationResourceBuffer, status -> {
                    if (status == StatusCode.OK.getStatusCode()) {
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

    private void extractValidationValues(Buffer validationResourceBuffer) {
        try {
            JsonObject validationRes = new JsonObject(validationResourceBuffer.toString("UTF-8"));
            getValidationResource().reset();

            JsonArray resourcesArray = validationRes.getJsonArray("resources");
            if (resourcesArray != null) {
                for (Object resourceEntry : resourcesArray) {
                    JsonObject resJSO = (JsonObject) resourceEntry;
                    Map<String, String> resProperties = new HashMap<>();

                    String notAllowedProperties = checkProperties(resJSO.fieldNames());
                    if(StringUtils.isNotEmpty(notAllowedProperties)){
                        log.warn("Unknown properties '" + notAllowedProperties + "' in validation resource found. Ignoring them!");
                    }

                    String url = resJSO.getString(ValidationResource.URL_PROPERTY);
                    if(!StringUtils.isEmpty(url)){
                        resProperties.put(ValidationResource.URL_PROPERTY, url);
                    } else {
                        throw new IllegalArgumentException("Property '"+ValidationResource.URL_PROPERTY+"' is not allowed to be missing (or empty)");
                    }

                    String method = resJSO.getString(ValidationResource.METHOD_PROPERTY);
                    if(!StringUtils.isEmpty(method)){
                        resProperties.put(ValidationResource.METHOD_PROPERTY, method);
                    } else {
                        resProperties.put(ValidationResource.METHOD_PROPERTY, "PUT");
                    }

                    getValidationResource().addResource(resProperties);
                }
            } else {
                getValidationResource().reset();
                throw new IllegalArgumentException("No resources property defined in validation resource");
            }
        } catch (Exception ex) {
            getValidationResource().reset();
            throw new IllegalArgumentException(ex);
        }
    }

    private String checkProperties(Set<String> fieldnames){
        String notAllowedProperties = "";
        List<String> notAllowed = new ArrayList<>();
        for (String fieldname : fieldnames) {
            if(!ValidationResource.ALLOWED_VALIDATION_PROPERTIES.contains(fieldname)){
                notAllowed.add(fieldname);
            }
        }
        if(!notAllowed.isEmpty()){
            return Joiner.on(",").join(notAllowed);
        }
        return notAllowedProperties;
    }
}

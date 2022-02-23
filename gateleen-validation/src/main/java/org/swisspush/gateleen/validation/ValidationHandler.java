package org.swisspush.gateleen.validation;

import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpServerRequest;
import org.slf4j.Logger;
import org.swisspush.gateleen.core.http.RequestLoggerFactory;
import org.swisspush.gateleen.core.storage.ResourceStorage;
import org.swisspush.gateleen.core.util.ExpansionDeltaUtil;
import org.swisspush.gateleen.core.util.ResponseStatusCodeLogUtil;
import org.swisspush.gateleen.core.util.StatusCode;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import static org.swisspush.gateleen.validation.ValidationUtil.matchingSchemaLocation;
import static org.swisspush.gateleen.validation.ValidationUtil.matchingValidationResourceEntry;

/**
 * Validates incoming and outgoing JSON and issues warnings in logs.
 *
 * @author https://github.com/lbovet [Laurent Bovet], https://github.com/mcweba [Marc-Andre Weber]
 */
public class ValidationHandler {
    public static final String HOOKS_LISTENERS_URI_PART = "/_hooks/listeners/";
    public static final String HOOKS_ROUTE_URI_PART = "/_hooks/route";

    public static final String ACCEPT = "accept";
    private HttpClient httpClient;
    private List<String> supportedMethods = Arrays.asList("PUT", "GET", "POST");
    private List<String> outMethods = Arrays.asList("GET", "POST");
    private List<String> inMethods = Arrays.asList("PUT", "POST");
    private static final String VALID_HEADER = "X-Valid";
    private static final String EXPAND_PARAM = "expand";
    private static final int TIMEOUT = 120000;
    private static final Pattern noExtension = Pattern.compile(".*/[^/\\.]*$");
    private Validator validator;
    private ValidationResourceManager validationResourceManager;
    private boolean failOnError = true;


    public ValidationHandler(ValidationResourceManager validationResourceManager, ValidationSchemaProvider validationSchemaProvider,
                             ResourceStorage storage, HttpClient httpClient, String schemaRoot) {
        this.validationResourceManager = validationResourceManager;
        this.httpClient = httpClient;
        this.validator = new Validator(storage, schemaRoot, validationSchemaProvider);
    }

    public ValidationHandler(ValidationResourceManager validationResourceManager, HttpClient httpClient, Validator validator) {
        this.validationResourceManager = validationResourceManager;
        this.httpClient = httpClient;
        this.validator = validator;
    }

    /**
     * Returns true when the {@link ValidationHandler} must be applied to this request.
     *
     * @param request request
     * @return boolean
     */
    public boolean isToValidate(HttpServerRequest request) {
        final Logger log = RequestLoggerFactory.getLogger(ValidationHandler.class, request);

        boolean doValidate = supportedMethods.contains(request.method().name()) &&
                isJsonRequest(request) &&
                !(request.headers().names().contains(VALID_HEADER)) &&
                !(request.params().names().contains(EXPAND_PARAM));
        if (!doValidate) {
            return false;
        }

        // Exclude Hooks from validation
        if (request.uri().contains(HOOKS_ROUTE_URI_PART) || request.uri().contains(HOOKS_LISTENERS_URI_PART)) {
            return false; // do not validate
        }

        return matchingValidationResourceEntry(validationResourceManager.getValidationResource(), request, log) != null;
    }

    private boolean isJsonRequest(HttpServerRequest request) {
        boolean jsonRequest = request.headers().get(ACCEPT) != null && request.headers().get(ACCEPT).contains("application/json");
        jsonRequest |= request.headers().get(ACCEPT) != null && request.headers().get(ACCEPT).contains("text/plain");
        jsonRequest |= request.headers().get("content-type") != null && request.headers().get("content-type").contains("application/json");
        jsonRequest |= noExtension.matcher(request.path()).matches();
        return jsonRequest;
    }

    /**
     * Performs validation.
     *
     * @param req
     */
    private void handleValidation(final HttpServerRequest req) {
        final Logger log = RequestLoggerFactory.getLogger(ValidationHandler.class, req);
        httpClient.request(req.method(), req.uri()).onComplete(asyncReqResult -> {
            if (asyncReqResult.failed()) {
                log.warn("Failed request to {}: {}", req.uri(), asyncReqResult.cause());
                return;
            }
            HttpClientRequest cReq = asyncReqResult.result();


            cReq.setTimeout(TIMEOUT);
            cReq.headers().setAll(req.headers());
            cReq.headers().set(VALID_HEADER, "0");
            Handler<AsyncResult<HttpClientResponse>> resultHandler = asyncResult -> {
                HttpClientResponse cRes = asyncResult.result();
                ResponseStatusCodeLogUtil.info(req, StatusCode.fromCode(cRes.statusCode()), ValidationHandler.class);
                req.response().setStatusCode(cRes.statusCode());
                req.response().setStatusMessage(cRes.statusMessage());
                req.response().headers().setAll(cRes.headers());

                cRes.bodyHandler(data -> {

                    if (req.response().getStatusCode() == StatusCode.OK.getStatusCode() && outMethods.contains(req.method().name()) && data.length() > 0) {
                        validator.validate(req, req.method() + "/out", data,
                                matchingSchemaLocation(validationResourceManager.getValidationResource(), req, log).orElse(null),
                                event -> {
                                    if (event.isSuccess()) {
                                        req.response().end(data);
                                    } else {
                                        if (isFailOnError()) {
                                            req.response().headers().clear();
                                            req.response().setStatusCode(StatusCode.BAD_REQUEST.getStatusCode());
                                            req.response().setStatusMessage(StatusCode.BAD_REQUEST.getStatusMessage());
                                            req.response().end();
                                        } else {
                                            req.response().end(data);
                                        }
                                        log.warn(event.getMessage());
                                    }
                                });
                    } else {
                        req.response().end(data);
                    }
                });
                cRes.exceptionHandler(ExpansionDeltaUtil.createResponseExceptionHandler(req, req.uri(), ValidationHandler.class));
            };
            req.bodyHandler(data -> {
                if (inMethods.contains(req.method().name())) {
                    validator.validate(req, req.method() + "/in", data,
                            matchingSchemaLocation(validationResourceManager.getValidationResource(), req, log).orElse(null),
                            event -> {
                                if (event.isSuccess()) {
                                    cReq.end(data);
                                } else {
                                    if (isFailOnError()) {
                                        req.response().setStatusCode(StatusCode.BAD_REQUEST.getStatusCode());
                                        req.response().setStatusMessage(StatusCode.BAD_REQUEST.getStatusMessage());
                                        if (event.getValidationDetails() != null) {
                                            req.response().headers().add("content-type", "application/json");
                                            req.response().end(event.getValidationDetails().encode());
                                        } else {
                                            req.response().end(event.getMessage());
                                        }
                                    } else {
                                        log.warn(event.getMessage());
                                        cReq.end(data);
                                    }
                                }
                            });
                } else {
                    cReq.end(data);
                }
            });

            cReq.exceptionHandler(ExpansionDeltaUtil.createRequestExceptionHandler(req, req.uri(), ValidationHandler.class));
        });
    }

    public void handle(final HttpServerRequest request) {
        handleValidation(request);
    }

    public boolean isFailOnError() {
        return failOnError;
    }

    public void setFailOnError(boolean failOnError) {
        this.failOnError = failOnError;
    }
}
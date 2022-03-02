package org.swisspush.gateleen.user;

import org.swisspush.gateleen.core.http.RequestLoggerFactory;
import org.swisspush.gateleen.core.json.JsonUtil;
import org.swisspush.gateleen.core.logging.LoggableResource;
import org.swisspush.gateleen.core.logging.RequestLogger;
import org.swisspush.gateleen.core.storage.ResourceStorage;
import org.swisspush.gateleen.core.util.ResponseStatusCodeLogUtil;
import org.swisspush.gateleen.core.util.StatusCode;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Pattern;

/**
 * @author https://github.com/lbovet [Laurent Bovet]
 */
public class RoleProfileHandler implements LoggableResource {

    public static final String UPDATE_ADDRESS = "gateleen.roleprofiles-updated";

    private ResourceStorage storage;
    private Logger log = LoggerFactory.getLogger(RoleProfileHandler.class);
    private boolean logRoleProfileChanges = false;
    private Pattern uriPattern;
    private Vertx vertx;
    private EventBus eb;

    public RoleProfileHandler(Vertx vertx, ResourceStorage storage, String roleProfileUriPattern) {
        this.storage = storage;
        this.vertx = vertx;
        uriPattern = Pattern.compile(roleProfileUriPattern);
        eb = vertx.eventBus();
    }

    public boolean isRoleProfileRequest(HttpServerRequest request) {
        return uriPattern.matcher(request.path()).matches();
    }

    @Override
    public void enableResourceLogging(boolean resourceLoggingEnabled) {
        this.logRoleProfileChanges = resourceLoggingEnabled;
    }

    public void handle(final HttpServerRequest request) {
        RequestLoggerFactory.getLogger(RoleProfileHandler.class, request).info("handling " + request.method() + " " + request.path());
        switch (request.method().name()) {
        case "GET":
            storage.get(request.path(), buffer -> {
                request.response().headers().set("Content-Type", "application/json");
                if (buffer != null) {
                    ResponseStatusCodeLogUtil.info(request, StatusCode.OK, RoleProfileHandler.class);
                    request.response().setStatusCode(StatusCode.OK.getStatusCode());
                    request.response().end(buffer);
                } else {
                    ResponseStatusCodeLogUtil.info(request, StatusCode.NOT_FOUND, RoleProfileHandler.class);
                    request.response().setStatusCode(StatusCode.NOT_FOUND.getStatusCode());
                    request.response().end();
                }
            });
            break;
        case "PUT":
            request.bodyHandler(newBuffer -> {
                JsonObject roleProfileProperties = new JsonObject(newBuffer.toString("UTF-8"));
                if (JsonUtil.containsNoNestedProperties(roleProfileProperties)) {
                    storage.put(request.uri() + "?merge=true", newBuffer, status -> {
                        if (status == StatusCode.OK.getStatusCode()) {
                            if(logRoleProfileChanges){
                                RequestLogger.logRequest(vertx.eventBus(), request, status, newBuffer);
                            }
                            scheduleUpdate();
                        } else {
                            request.response().setStatusCode(status);
                        }
                        ResponseStatusCodeLogUtil.info(request, StatusCode.fromCode(status), RoleProfileHandler.class);
                        request.response().end();
                    });
                } else {
                    ResponseStatusCodeLogUtil.info(request, StatusCode.BAD_REQUEST, RoleProfileHandler.class);
                    request.response().setStatusCode(StatusCode.BAD_REQUEST.getStatusCode());
                    request.response().setStatusMessage(StatusCode.BAD_REQUEST.getStatusMessage());
                    request.response().end("role profiles can't take nested property values");
                }
            });
            break;
        case "DELETE":
            storage.delete(request.path(), status -> {
                if (status == StatusCode.OK.getStatusCode()) {
                    eb.publish(UPDATE_ADDRESS, "*");
                } else {
                    log.warn("Could not delete '" + (request.uri() == null ? "<null>" : request.uri()) + "'. Error code is '" + (status == null ? "<null>" : status) + "'.");
                    request.response().setStatusCode(status);
                }
                ResponseStatusCodeLogUtil.info(request, StatusCode.fromCode(status), RoleProfileHandler.class);
                request.response().end();
            });
            break;
        default:
            ResponseStatusCodeLogUtil.info(request, StatusCode.METHOD_NOT_ALLOWED, RoleProfileHandler.class);
            request.response().setStatusCode(StatusCode.METHOD_NOT_ALLOWED.getStatusCode());
            request.response().end(StatusCode.METHOD_NOT_ALLOWED.getStatusMessage());
        }
    }

    private long updateTimerId = -1;

    private void scheduleUpdate() {
        vertx.cancelTimer(updateTimerId);
        updateTimerId = vertx.setTimer(3000, id -> eb.publish(UPDATE_ADDRESS, "*"));
    }
}

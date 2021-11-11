package org.swisspush.gateleen.core.cors;

import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import org.slf4j.Logger;
import org.swisspush.gateleen.core.http.RequestLoggerFactory;
import org.swisspush.gateleen.core.util.StatusCode;

/**
 * Handles Cross-Origin Resource Sharing (CORS) requests.
 * Set property 'org.swisspush.gateleen.addcorsheaders' to "true" to activate.
 * 
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class CORSHandler {

    private boolean addCORSheaders;

    public boolean isOptionsRequest(HttpServerRequest request) {
        return HttpMethod.OPTIONS == request.method();
    }

    public CORSHandler() {
        addCORSheaders = Boolean.parseBoolean(System.getProperty("org.swisspush.gateleen.addcorsheaders"));
    }

    public void handle(final HttpServerRequest request) {
        Logger log = RequestLoggerFactory.getLogger(CORSHandler.class, request);
        addCORSHeaders(request, log);
        if (isOptionsRequest(request)) {
            log.info("Got OPTIONS request. Respond with statusCode 200");
            request.response().setStatusCode(StatusCode.OK.getStatusCode());
            request.response().end();
        }
    }

    /**
     * Add headers for Cross-Origin Resource Sharing (CORS) to Response when not in PROD environment
     * 
     * @param request
     */
    private void addCORSHeaders(HttpServerRequest request, Logger log) {
        String originHeader = request.headers().get("Origin");
        if (addCORSheaders && originHeader != null) {
            request.response().headers().set("Access-Control-Allow-Origin", originHeader);
            request.response().headers().set("Access-Control-Allow-Credentials", "true");
            request.response().headers().set("Access-Control-Allow-Methods", "GET, PATCH, POST, OPTIONS, PUT, DELETE");
            if (HttpMethod.OPTIONS == request.method()) {
                request.response().headers().set("Access-Control-Allow-Headers", request.headers().get("Access-Control-Request-Headers"));
            }

            log.debug("Setting Access-Control-Allow-Origin headers");
        }
    }
}

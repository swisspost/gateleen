package org.swisspush.gateleen.core.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpServerRequest;

/**
 * Provides logger with request uniqueid.
 *
 * @author https://github.com/lbovet [Laurent Bovet]
 */
public final class RequestLoggerFactory {

    private RequestLoggerFactory() {
    }

    public static Logger getLogger(Class<?> clazz, HttpServerRequest request) {
        return getLogger(clazz, request.headers());
    }

    public static Logger getLogger(Class<?> clazz, MultiMap headers) {

        String uid = null;
        if (headers != null) {
            String rid;
            rid = headers.get("x-request-id");
            if (rid != null) {
                uid = rid;
            }
            rid = headers.get("x-rp-unique_id");
            if (rid != null) {
                if (uid != null) {
                    uid = uid + " " + rid;
                } else {
                    uid = rid;
                }
            }
        }

        if (uid != null) {
            return new RequestLoggerWrapper(LoggerFactory.getLogger(clazz), uid);
        } else {
            return LoggerFactory.getLogger(clazz);
        }
    }

}

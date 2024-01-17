package org.swisspush.gateleen.routing;

import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.http.HttpServerRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handler to respond with a custom status code
 * @author henningo
 */
public class CustomHttpResponseHandler {

    private final Logger LOG = LoggerFactory.getLogger(CustomHttpResponseHandler.class);

    private final String path;
    private static final String SLASH = "/";
    private static final String EMPTY = "";

    public CustomHttpResponseHandler(String rootPath) {
        this.path = rootPath;
        LOG.info("listening to {}", rootPath);
    }

    public boolean handle(HttpServerRequest request) {
        if (!request.uri().startsWith(path)) {
            return false;
        }
        HttpResponseStatus rs;
        String info = "";
        try {
            String code = request.uri().substring(path.length());

            // remove leading slashes
            while (code.startsWith(SLASH)) {
                code = code.substring(1);
            }

            // find additional path segments
            int index = code.indexOf(SLASH);
            if(index != -1) {
                code = code.substring(0, index); // remove additional path segments
            }

            int codeAsInt = Integer.parseInt(code);
            rs = HttpResponseStatus.valueOf(codeAsInt);
        } catch (Exception ex) {
            LOG.warn("can't parse wanted response code from {}", request.uri(), ex);
            rs = HttpResponseStatus.BAD_REQUEST;
            info = ": missing, wrong or non-numeric status-code in request URL";
        }
        request.response().setStatusCode(rs.code()).setStatusMessage(rs.reasonPhrase()).end(rs + info);
        return true;
    }
}

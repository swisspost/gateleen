package org.swisspush.gateleen.routing;

import com.google.common.base.Splitter;
import com.google.common.collect.Lists;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.http.HttpServerRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

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

            List<String> pathSegments = Lists.newArrayList(Splitter.on(SLASH).omitEmptyStrings().limit(2).split(code));
            code = pathSegments.isEmpty() ? EMPTY : pathSegments.get(0);

            int codeAsInt = Integer.parseInt(code);
            rs = HttpResponseStatus.valueOf(codeAsInt);
        } catch (Exception ex) {
            LOG.warn("can't parse wanted response code from {}", request.uri(), ex);
            rs = HttpResponseStatus.BAD_REQUEST;
            info = ": missing, wrong or non-numeric status-code in request URL";
        }
        request.response().setStatusCode(rs.code()).setStatusMessage(rs.reasonPhrase()).end(rs.toString() + info);
        return true;
    }
}

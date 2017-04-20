package org.swisspush.gateleen.core.util;

import io.vertx.core.http.HttpServerRequest;

/**
 * Class HttpServerRequestUtil.
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class HttpServerRequestUtil {

    private HttpServerRequestUtil(){}

    public static boolean isRemoteAddressLoopbackAddress(HttpServerRequest request){
        String host = request.remoteAddress().host();
        if(StringUtils.isEmpty(host)){
            return false;
        }
        return host.startsWith("127") || host.equalsIgnoreCase("localhost");
    }

    /**
     * Increases the value of the {@link HttpRequestHeader#X_HOPS} header. Adds the header when not already present.
     *
     * @param request the request to increase the header value
     */
    public static void increaseRequestHops(HttpServerRequest request){
        Integer hops = HttpRequestHeader.getInteger(request.headers(), HttpRequestHeader.X_HOPS, 0);
        hops = hops + 1;
        request.headers().set(HttpRequestHeader.X_HOPS.getName(), String.valueOf(hops));
    }
}

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

    /**
     * Checks whether the request hops limit is reached based on the {@link HttpRequestHeader#X_HOPS} header.
     *
     * @param request the request to check
     * @param hopsLimit the request hops limit
     * @return returns true when the {@link HttpRequestHeader#X_HOPS} header value is greater than the hopsLimit parameter
     */
    public static boolean isRequestHopsLimitExceeded(HttpServerRequest request, Integer hopsLimit){
        if(hopsLimit == null){
            return false;
        }
        Integer hops = HttpRequestHeader.getInteger(request.headers(), HttpRequestHeader.X_HOPS, 0);
        return hops > hopsLimit;
    }
}

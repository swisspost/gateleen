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
}

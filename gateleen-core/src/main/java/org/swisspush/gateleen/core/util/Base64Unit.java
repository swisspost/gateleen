package org.swisspush.gateleen.core.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Base64;

public class Base64Unit {
    private static Logger log = LoggerFactory.getLogger(Base64Unit.class);
    private static final Base64.Decoder BASE64_LEGACY_ENCODER = Base64.getDecoder();
    private static final Base64.Decoder BASE64_DECODER = Base64.getUrlDecoder();

    /**
     * because we need move from Vert.x 3.x legacy mode, so need a decoder can decode all format
     *
     * @param input base64 string
     * @return decoded data
     */
    public static byte[] decodeBase64Safe(String input) {
        if (StringUtils.isEmpty(input)) {
            return new byte[0];
        }
        if ("legacy".equalsIgnoreCase(System.getProperty("vertx.json.base64"))) {
            try {
                return BASE64_LEGACY_ENCODER.decode(input);
            } catch (IllegalArgumentException e) {
                log.debug("Unable to parse base64 encoded string with 'legacy' decoder, will try with 'new' decoder", e);
                return BASE64_DECODER.decode(input);
            }
        } else {
            try {
                return BASE64_DECODER.decode(input);
            } catch (IllegalArgumentException e) {
                log.debug("Unable to parse base64 encoded string with 'new' decoder, will try with 'legacy' decoder", e);
                return BASE64_LEGACY_ENCODER.decode(input);
            }
        }
    }
}

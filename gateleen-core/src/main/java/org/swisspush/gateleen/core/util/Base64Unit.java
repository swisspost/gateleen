package org.swisspush.gateleen.core.util;

import java.util.Base64;

public class Base64Unit {
    /**
     * because we need move from Vert.x 3.x legacy mode, so need a decoder can decode all format
     * @param input base64 string
     * @return decoded data
     */
    public static byte[] decodeBase64Safe(String input) {
        StringBuilder sb = new StringBuilder(input.length() + 4);

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '+') sb.append('-');
            else if (c == '/') sb.append('_');
            else sb.append(c);
        }

        while (sb.length() % 4 != 0) {
            sb.append('=');
        }
        return Base64.getUrlDecoder().decode(sb.toString());
    }
}

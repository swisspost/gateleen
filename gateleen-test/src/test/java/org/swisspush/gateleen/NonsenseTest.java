package org.swisspush.gateleen;

import org.junit.Test;

/**
 * Class NonsenseTest.
 * This represents TODO.
 *
 * @author ljucam
 * @version $$Revision$$
 * @see <script>links('$$HeadURL$$');</script>
 */
public class NonsenseTest {
    private static final String ZIP_RESOURCE_FLAG = ".zip/";
    private static final int DEFAULT_TIMEOUT = 120000;

    @Test
    public void test() {
        String url = "http://blah/lola/rennt.zip/path/das/ist";
        int index = url.lastIndexOf(ZIP_RESOURCE_FLAG) + ZIP_RESOURCE_FLAG.length() - 1;
        System.out.println(url.substring(index));
        System.out.println(url.substring(0, index));
    }
}

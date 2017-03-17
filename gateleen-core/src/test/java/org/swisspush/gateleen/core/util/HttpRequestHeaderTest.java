package org.swisspush.gateleen.core.util;

import io.vertx.core.MultiMap;
import io.vertx.core.http.CaseInsensitiveHeaders;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.swisspush.gateleen.core.util.HttpRequestHeader.*;

/**
 * <p>
 * Tests for the {@link HttpRequestHeader} class
 * </p>
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class HttpRequestHeaderTest {

    MultiMap headers;

    @Before
    public void setUp() {
        headers = new CaseInsensitiveHeaders();
    }

    @Test
    public void testContainsHeader(TestContext context){
        context.assertFalse(containsHeader(null, CONTENT_LENGTH));
        context.assertFalse(containsHeader(headers, CONTENT_LENGTH));

        headers.set("Content-Length", "99");
        context.assertTrue(containsHeader(headers, CONTENT_LENGTH));

        headers.clear();
        headers.set("content-length", "99");
        context.assertTrue(containsHeader(headers, CONTENT_LENGTH));

        headers.clear();
        headers.set("CONTENT-LENGTH", "99");
        context.assertTrue(containsHeader(headers, CONTENT_LENGTH));

        headers.clear();
        headers.set("contentlength", "99");
        context.assertFalse(containsHeader(headers, CONTENT_LENGTH));
    }

    @Test
    public void testGetInteger(TestContext context){
        headers.set("Content-Length", "99");
        context.assertEquals(99, getInteger(headers, HttpRequestHeader.CONTENT_LENGTH));

        headers.set("Content-Length", "444");
        context.assertEquals(444, getInteger(headers, HttpRequestHeader.CONTENT_LENGTH));

        headers.set("Content-Length", "0");
        context.assertEquals(0, getInteger(headers, HttpRequestHeader.CONTENT_LENGTH));

        headers.set("Content-Length", "9999999999999999999");
        context.assertNull(getInteger(headers, HttpRequestHeader.CONTENT_LENGTH));

        headers.set("Content-Length", "");
        context.assertNull(getInteger(headers, HttpRequestHeader.CONTENT_LENGTH));

        headers.set("Content-Length", "xyz");
        context.assertNull(getInteger(headers, HttpRequestHeader.CONTENT_LENGTH));

        headers.clear();
        context.assertNull(getInteger(headers, HttpRequestHeader.CONTENT_LENGTH));

        context.assertNull(getInteger(null, HttpRequestHeader.CONTENT_LENGTH));
    }

    @Test
    public void testGetString(TestContext context){
        headers.set("Content-Length", "99");
        context.assertEquals("99", getString(headers, HttpRequestHeader.CONTENT_LENGTH));

        headers.set("Content-Length", "444");
        context.assertEquals("444", getString(headers, HttpRequestHeader.CONTENT_LENGTH));

        headers.set("Content-Length", "0");
        context.assertEquals("0", getString(headers, HttpRequestHeader.CONTENT_LENGTH));

        headers.set("Content-Length", "9999999999999999999");
        context.assertEquals("9999999999999999999", getString(headers, HttpRequestHeader.CONTENT_LENGTH));

        headers.set("Content-Length", "");
        context.assertEquals("", getString(headers, HttpRequestHeader.CONTENT_LENGTH));

        headers.set("Content-Length", "xyz");
        context.assertEquals("xyz", getString(headers, HttpRequestHeader.CONTENT_LENGTH));

        headers.clear();
        context.assertNull(getString(headers, HttpRequestHeader.CONTENT_LENGTH));

        context.assertNull(getString(null, HttpRequestHeader.CONTENT_LENGTH));
    }
}

package org.swisspush.gateleen.logging;

import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.swisspush.gateleen.core.http.DummyHttpServerRequest;

import java.util.regex.Pattern;

/**
 * Tests for the {@link RequestPropertyFilter} class
 *
 * Updated to use precompiled regex patterns.
 *
 * @author https://github.com/mcweba
 */
@RunWith(VertxUnitRunner.class)
public class RequestPropertyFilterTest {

    private final String METHOD_PUT = "PUT";
    private final String PUT_REQUEST_REGEX = "/playground/server/.*";

    @Test
    public void testPropertyUrlFilterRequest(TestContext context) {
        context.assertEquals(FilterResult.FILTER,
                RequestPropertyFilter.filterProperty(new PUTRequest(), RequestPropertyFilter.URL, Pattern.compile(PUT_REQUEST_REGEX), false));
    }

    @Test
    public void testPropertyUrlRejectRequest(TestContext context) {
        context.assertEquals(FilterResult.REJECT,
                RequestPropertyFilter.filterProperty(new PUTRequest(), RequestPropertyFilter.URL, Pattern.compile(PUT_REQUEST_REGEX), true));
    }

    @Test
    public void testPropertyUrlNoMatchRequest(TestContext context) {
        String OTHER_PUT_REQUEST_URI = "/playground/server/some_other_resource";
        context.assertEquals(FilterResult.NO_MATCH,
                RequestPropertyFilter.filterProperty(new PUTRequest(), RequestPropertyFilter.URL, Pattern.compile(OTHER_PUT_REQUEST_URI), true));
    }

    @Test
    public void testPropertyMethodFilterRequest(TestContext context) {
        context.assertEquals(FilterResult.FILTER,
                RequestPropertyFilter.filterProperty(new PUTRequest(), RequestPropertyFilter.METHOD, Pattern.compile(METHOD_PUT), false));

        String METHOD_PUT_POST_REGEX = "PUT|POST";
        context.assertEquals(FilterResult.FILTER,
                RequestPropertyFilter.filterProperty(new PUTRequest(), RequestPropertyFilter.METHOD, Pattern.compile(METHOD_PUT_POST_REGEX), false));
    }

    @Test
    public void testPropertyMethodRejectRequest(TestContext context) {
        context.assertEquals(FilterResult.REJECT,
                RequestPropertyFilter.filterProperty(new PUTRequest(), RequestPropertyFilter.METHOD, Pattern.compile(METHOD_PUT), true));
    }

    @Test
    public void testPropertyMethodNoMatchRequest(TestContext context) {
        String METHOD_GET = "GET";
        context.assertEquals(FilterResult.NO_MATCH,
                RequestPropertyFilter.filterProperty(new PUTRequest(), RequestPropertyFilter.METHOD, Pattern.compile(METHOD_GET), false));

        // check again with reject = true
        context.assertEquals(FilterResult.NO_MATCH,
                RequestPropertyFilter.filterProperty(new PUTRequest(), RequestPropertyFilter.METHOD, Pattern.compile(METHOD_GET), true));
    }

    @Test
    public void testPropertyHeaderFilterRequest(TestContext context) {
        PUTRequest request = new PUTRequest();
        String headerName = "some_fancy_header";
        String headerValue = "a_fancy_value";
        request.addHeader(headerName, headerValue);

        context.assertEquals(FilterResult.FILTER,
                RequestPropertyFilter.filterProperty(request, headerName, Pattern.compile(headerValue), false));
    }

    @Test
    public void testPropertyHeaderRejectRequest(TestContext context) {
        PUTRequest request = new PUTRequest();
        String headerName = "some_fancy_header";
        String headerValue = "a_fancy_value";
        request.addHeader(headerName, headerValue);

        context.assertEquals(FilterResult.REJECT,
                RequestPropertyFilter.filterProperty(request, headerName, Pattern.compile(headerValue), true));
    }

    @Test
    public void testPropertyHeaderNotMatchingRequest(TestContext context) {
        PUTRequest request = new PUTRequest();
        String headerName = "some_fancy_header";
        String headerValue = "a_fancy_value";
        request.addHeader(headerName, headerValue);

        // reject = true
        context.assertEquals(FilterResult.REJECT,
                RequestPropertyFilter.filterProperty(request, headerName, Pattern.compile("another_fancy_value"), true));
        context.assertEquals(FilterResult.REJECT,
                RequestPropertyFilter.filterProperty(request, "another_fancy_header", Pattern.compile(headerValue), true));

        // reject = false
        context.assertEquals(FilterResult.REJECT,
                RequestPropertyFilter.filterProperty(request, headerName, Pattern.compile("another_fancy_value"), false));
        context.assertEquals(FilterResult.REJECT,
                RequestPropertyFilter.filterProperty(request, "another_fancy_header", Pattern.compile(headerValue), false));
    }

    class PUTRequest extends DummyHttpServerRequest {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();

        @Override
        public HttpMethod method() {
            return HttpMethod.PUT;
        }

        @Override
        public String uri() {
            return "/playground/server/some_resource";
        }

        @Override
        public MultiMap headers() {
            return headers;
        }

        public void addHeader(String headerName, String headerValue) {
            headers.add(headerName, headerValue);
        }
    }
}

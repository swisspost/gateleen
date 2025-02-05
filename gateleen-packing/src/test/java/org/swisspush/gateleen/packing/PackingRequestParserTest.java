package org.swisspush.gateleen.packing;

import io.vertx.core.buffer.Buffer;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.swisspush.gateleen.core.http.HttpRequest;
import org.swisspush.gateleen.core.util.Result;

import java.util.List;

/**
 * Test class for the {@link PackingRequestParser}
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class PackingRequestParserTest {

    @Test
    public void parseRequestsInvalid(TestContext context) {
        Buffer data = Buffer.buffer("Invalid data");
        Result<List<HttpRequest>, String> result = PackingRequestParser.parseRequests(data);
        context.assertTrue(result.isErr());

        data = Buffer.buffer("{}");
        result = PackingRequestParser.parseRequests(data);
        context.assertTrue(result.isErr());
    }

    @Test
    public void parseRequestsEmptyArray(TestContext context) {
        Buffer data = Buffer.buffer("{\"requests\": []}");
        Result<List<HttpRequest>, String> result = PackingRequestParser.parseRequests(data);
        context.assertTrue(result.isOk());
        context.assertNotNull(result.ok());
        context.assertEquals(0, result.ok().size());
    }

    @Test
    public void parseRequestsInvalidRequest(TestContext context) {
        Buffer data = Buffer.buffer("{\n" +
                "    \"requests\": {\n" +
                "        \"uri\": \"/playground/some/url\",\n" +
                "        \"method\": \"PUT\",\n" +
                "        \"headers\": [\n" +
                "            1,\n" +
                "            2,\n" +
                "            3\n" +
                "        ]\n" +
                "    }\n" +
                "}");
        Result<List<HttpRequest>, String> result = PackingRequestParser.parseRequests(data);
        context.assertTrue(result.isErr());

        data = Buffer.buffer("{\n" +
                "  \"requests\": [\n" +
                "    {\n" +
                "      \"uri\": \"/playground/some/url\",\n" +
                "      \"foo\": \"PUT\",\n" +
                "      \"payload\": {\n" +
                "        \"key\": 1,\n" +
                "        \"key2\": [1,2,3]\n" +
                "      },\n" +
                "      \"headers\": [[\"x-foo\", \"bar\"], [\"x-bar\", \"foo\"]]\n" +
                "    }\n" +
                "  ]\n" +
                "}");

        result = PackingRequestParser.parseRequests(data);
        context.assertTrue(result.isErr());
    }

    @Test
    public void parseRequestsValid(TestContext context) {
        Buffer data = Buffer.buffer("{\n" +
                "  \"requests\": [\n" +
                "    {\n" +
                "      \"uri\": \"/playground/some/url\",\n" +
                "      \"method\": \"PUT\",\n" +
                "      \"payload\": {\n" +
                "        \"key\": 1,\n" +
                "        \"key2\": [1,2,3]\n" +
                "      },\n" +
                "      \"headers\": [[\"x-foo\", \"bar\"], [\"x-bar\", \"foo\"]]\n" +
                "    }\n" +
                "  ]\n" +
                "}");
        Result<List<HttpRequest>, String> result = PackingRequestParser.parseRequests(data);
        context.assertTrue(result.isOk());

        List<HttpRequest> requests = result.ok();

        context.assertNotNull(requests);
        context.assertEquals(1, requests.size());

        HttpRequest request = requests.get(0);
        context.assertEquals("PUT", request.getMethod().name());
        context.assertEquals("/playground/some/url", request.getUri());
        context.assertEquals("{\"key\":1,\"key2\":[1,2,3]}", new String(request.getPayload()));
        context.assertEquals(3, request.getHeaders().size());
        context.assertEquals("bar", request.getHeaders().get("x-foo"));
        context.assertEquals("foo", request.getHeaders().get("x-bar"));
        context.assertEquals("24", request.getHeaders().get("content-length"));
    }

    @Test
    public void parseRequestsValidNoHeaders(TestContext context) {
        Buffer data = Buffer.buffer("{\n" +
                "  \"requests\": [\n" +
                "    {\n" +
                "      \"uri\": \"/playground/some/url\",\n" +
                "      \"method\": \"PUT\",\n" +
                "      \"payload\": {\n" +
                "        \"key\": 1,\n" +
                "        \"key2\": [1,2,3]\n" +
                "      }\n" +
                "    }\n" +
                "  ]\n" +
                "}");
        Result<List<HttpRequest>, String> result = PackingRequestParser.parseRequests(data);
        context.assertTrue(result.isOk());

        List<HttpRequest> requests = result.ok();

        context.assertNotNull(requests);
        context.assertEquals(1, requests.size());

        HttpRequest request = requests.get(0);
        context.assertEquals("PUT", request.getMethod().name());
        context.assertEquals("/playground/some/url", request.getUri());
        context.assertEquals("{\"key\":1,\"key2\":[1,2,3]}", new String(request.getPayload()));
        context.assertEquals(1, request.getHeaders().size());
        context.assertEquals("24", request.getHeaders().get("content-length"));
    }

    @Test
    public void parseRequestsValidNoPayload(TestContext context) {
        Buffer data = Buffer.buffer("{\n" +
                "  \"requests\": [\n" +
                "    {\n" +
                "      \"uri\": \"/playground/some/url\",\n" +
                "      \"method\": \"PUT\"\n" +
                "    }\n" +
                "  ]\n" +
                "}");
        Result<List<HttpRequest>, String> result = PackingRequestParser.parseRequests(data);
        context.assertTrue(result.isOk());

        List<HttpRequest> requests = result.ok();

        context.assertNotNull(requests);
        context.assertEquals(1, requests.size());

        HttpRequest request = requests.get(0);
        context.assertEquals("PUT", request.getMethod().name());
        context.assertEquals("/playground/some/url", request.getUri());
        context.assertTrue(request.getHeaders().isEmpty());
    }

    @Test
    public void parseRequestsHeadersNoPayload(TestContext context) {
        Buffer data = Buffer.buffer("{\n" +
                "  \"requests\": [\n" +
                "    {\n" +
                "      \"uri\": \"/playground/some/url\",\n" +
                "      \"method\": \"POST\",\n" +
                "      \"headers\": [[\"x-foo\", \"bar\"], [\"x-bar\", \"foo\"]]\n" +
                "    }\n" +
                "  ]\n" +
                "}");
        Result<List<HttpRequest>, String> result = PackingRequestParser.parseRequests(data);
        context.assertTrue(result.isOk());

        List<HttpRequest> requests = result.ok();

        context.assertNotNull(requests);
        context.assertEquals(1, requests.size());

        HttpRequest request = requests.get(0);
        context.assertEquals("POST", request.getMethod().name());
        context.assertEquals("/playground/some/url", request.getUri());
        context.assertEquals(2, request.getHeaders().size());
        context.assertEquals("bar", request.getHeaders().get("x-foo"));
        context.assertEquals("foo", request.getHeaders().get("x-bar"));
    }
}

package org.swisspush.gateleen.delegate;

import io.vertx.core.MultiMap;

import io.vertx.core.http.impl.headers.HeadersMultiMap;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.swisspush.gateleen.core.json.transform.JoltSpec;
import org.swisspush.gateleen.core.json.transform.JoltSpecBuilder;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tests for the {@link TransformPayloadInputBuilder} class
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class TransformPayloadInputBuilderTest {

    private Matcher matcher;

    private String specValid = "[\n" +
            "  {\n" +
            "    \"operation\": \"shift\",\n" +
            "    \"spec\": {\n" +
            "      \"@\": \"\"\n" +
            "    }\n" +
            "  }\n" +
            "]";

    private static final String SIMPLE_PAYLOAD = "{\n" +
            "\t\"key\": \"value\",\n" +
            "\t\"key2\": 344\n" +
            "}";

    @Before
    public void setUp() throws Exception {
        Pattern pattern = Pattern.compile(".*/([^/]+.*)");
        matcher = pattern.matcher("/some/test/url");
    }

    @Test
    public void testBuildNoMetadata(TestContext context) throws Exception {
        JoltSpec spec = JoltSpecBuilder.buildSpec(specValid, false);
        MultiMap headers = new HeadersMultiMap();

        String builtInput = TransformPayloadInputBuilder.build(spec, SIMPLE_PAYLOAD, headers, matcher);
        context.assertEquals(SIMPLE_PAYLOAD, builtInput);
    }

    @Test
    public void testBuildWithMetadata(TestContext context) throws Exception {
        JoltSpec spec = JoltSpecBuilder.buildSpec(specValid, true);
        MultiMap headers = new HeadersMultiMap();
        headers.add("x-abc", "x");
        headers.add("x-def", "y");
        headers.add("x-def", "y2");
        headers.add("x-ghi", "z");
        headers.add("x-ghi", "z2");
        headers.add("x-ghi", "z3");

        String builtInput = TransformPayloadInputBuilder.build(spec, SIMPLE_PAYLOAD, headers, matcher);
        String expected = "{\n" +
                "\t\"urlParts\": [\"/some/test/url\", \"url\"],\n" +
                "\t\"headers\": {\n" +
                "\t\t\"x-abc\": \"x\",\n" +
                "\t\t\"x-def\": \"y,y2\",\n" +
                "\t\t\"x-ghi\": \"z,z2,z3\"\n" +
                "\t},\n" +
                "\t\"payload\": {\n" +
                "\t\t\"key\": \"value\",\n" +
                "\t\t\"key2\": 344\n" +
                "\t}\n" +
                "}";

        context.assertEquals(new JsonObject(expected), new JsonObject(builtInput));
    }
}

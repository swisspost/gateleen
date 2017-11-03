package org.swisspush.gateleen.core.json.transform;

import com.bazaarvoice.jolt.exception.JsonUnmarshalException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.swisspush.gateleen.core.util.ResourcesUtils;

/**
 * Tests for the {@link JoltTransformer} class
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class JoltTransformerTest {

    private final String IDENTITY_SPEC = "[\n" +
            "  {\n" +
            "    \"operation\": \"shift\",\n" +
            "    \"spec\": {\n" +
            "      \"@1\": {\n" +
            "        \"@\": \"\"\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "]";

    private final String COPY_TO_ARRAY_SPEC = "[\n" +
            "  {\n" +
            "    \"operation\": \"shift\",\n" +
            "    \"spec\": {\n" +
            "      \"@\": \"records[0].value\"\n" +
            "    }\n" +
            "  }\n" +
            "]";

    private JoltSpec identitySpec;
    private JoltSpec copyToArraySpec;

    private final String COMPLEX_INPUT_JSON = ResourcesUtils.loadResource("complex_input_json", true);

    @Before
    public void setUp(TestContext context) throws Exception {
        Async async = context.async();
        JoltSpecBuilder.buildSpec(IDENTITY_SPEC).setHandler(spec -> {
            identitySpec = spec.result();
            JoltSpecBuilder.buildSpec(COPY_TO_ARRAY_SPEC).setHandler(spec2 -> {
                copyToArraySpec = spec2.result();
                async.complete();
            });
        });
    }

    @Test
    public void testTransformInputNull(TestContext context) {
        JoltTransformer.transform(null, identitySpec).setHandler(transform -> {
            context.assertFalse(transform.succeeded());
            context.assertNull(transform.result());
            context.assertEquals(NullPointerException.class, transform.cause().getClass());
        });
    }

    @Test
    public void testTransformInputEmptyString(TestContext context) {
        JoltTransformer.transform("", identitySpec).setHandler(transform -> {
            context.assertFalse(transform.succeeded());
            context.assertNull(transform.result());
            context.assertEquals(JsonUnmarshalException.class, transform.cause().getClass());
        });
    }

    @Test
    public void testTransformInputNotJsonString(TestContext context) {
        JoltTransformer.transform("abcd", identitySpec).setHandler(transform -> {
            context.assertFalse(transform.succeeded());
            context.assertNull(transform.result());
            context.assertEquals(JsonUnmarshalException.class, transform.cause().getClass());
        });
    }

    @Test
    public void testTransformInputEmptyJsonObject(TestContext context) {
        String input = "{}";

        context.assertEquals(new JsonObject(input), JoltTransformer.transform(input, identitySpec).result(),
                "the output is expected to be equal to the input");
    }

    @Test
    public void testTransformInputSpecNull(TestContext context) {
        JoltTransformer.transform("{}", null).setHandler(transform -> {
            context.assertFalse(transform.succeeded());
            context.assertNull(transform.result());
            context.assertEquals(NullPointerException.class, transform.cause().getClass());
        });
    }

    @Test
    public void testTransformInputSpecInvalid(TestContext context) {
        JoltTransformer.transform("{}", new JoltSpec(null)).setHandler(transform -> {
            context.assertFalse(transform.succeeded());
            context.assertNull(transform.result());
            context.assertEquals(NullPointerException.class, transform.cause().getClass());
        });
    }

    @Test
    public void testTransformIdentityFunction(TestContext context) {

        String input = "{\n" +
                "  \"rating\": {\n" +
                "    \"primary\": {\n" +
                "      \"value\": 3\n" +
                "    }\n" +
                "  }\n" +
                "}";

        context.assertEquals(new JsonObject(input), JoltTransformer.transform(input, identitySpec).result(),
                "the output is expected to be equal to the input");
    }

    @Test
    public void testTransformCopyToArray(TestContext context) {

        String input = "{\n" +
                "  \"rating\": {\n" +
                "    \"primary\": {\n" +
                "      \"value\": 3\n" +
                "    }\n" +
                "  }\n" +
                "}";

        JsonObject output = buildRecordsOutput(new JsonObject(input));
        context.assertEquals(output, JoltTransformer.transform(input, copyToArraySpec).result());

        String anotherInput = "{\"test\": 234, \"foo\": \"bar\"}";
        JsonObject anotherOutput = buildRecordsOutput(new JsonObject(anotherInput));
        context.assertEquals(anotherOutput, JoltTransformer.transform(anotherInput, copyToArraySpec).result());

        JsonObject complexOutput = buildRecordsOutput(new JsonObject(COMPLEX_INPUT_JSON));
        context.assertEquals(complexOutput, JoltTransformer.transform(COMPLEX_INPUT_JSON, copyToArraySpec).result());
    }

    private JsonObject buildRecordsOutput(JsonObject content){
        JsonObject template = new JsonObject();
        JsonArray records = new JsonArray();
        template.put("records", records);
        JsonObject item1 = new JsonObject();
        item1.put("value", content);
        records.add(item1);
        return template;
    }
}

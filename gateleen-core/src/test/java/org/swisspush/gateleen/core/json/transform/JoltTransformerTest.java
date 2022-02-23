package org.swisspush.gateleen.core.json.transform;

import com.bazaarvoice.jolt.exception.JsonUnmarshalException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
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
            "      \"@\": \"\"\n" +
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

    private final String TRANSFORM_WITH_METADATA_SPEC = "[\n" +
            "  {\n" +
            "    \"operation\": \"shift\",\n" +
            "    \"spec\": {\n" +
            "      \"urlParts\": {\n" +
            "        \"1\": \"records[0].value.metadata.techId\"\n" +
            "      },\n" +
            "      \"headers\": {\n" +
            "        \"x-abc\": \"records[0].value.metadata.x-abc\",\n" +
            "        \"x-def\": \"records[0].value.metadata.x-def\"\n" +
            "      },\n" +
            "      \"payload\": {\n" +
            "        \"@\": \"records[0].value.dummyEvent\",\n" +
            "        \"sending\": {\n" +
            "          \"id\": [\"records[0].key\", \"records[0].value.&\"]\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "]";

    private JoltSpec identitySpec;
    private JoltSpec copyToArraySpec;
    private JoltSpec transformWithMetadataSpec;

    private final String COMPLEX_INPUT_JSON = ResourcesUtils.loadResource("complex_input_json", true);
    private final String TRANSFORM_WITH_METADATA_INPUT_JSON = ResourcesUtils.loadResource("transform_with_metadata_input_json", true);
    private final String TRANSFORM_WITH_METADATA_EXPECTED_OUTPUT_JSON = ResourcesUtils.loadResource("transform_with_metadata_expected_output_json", true);

    @Before
    public void setUp(TestContext context) throws Exception {
        identitySpec = JoltSpecBuilder.buildSpec(IDENTITY_SPEC);
        copyToArraySpec = JoltSpecBuilder.buildSpec(COPY_TO_ARRAY_SPEC);
        transformWithMetadataSpec = JoltSpecBuilder.buildSpec(TRANSFORM_WITH_METADATA_SPEC, true);
    }

    @Test
    public void testTransformInputNull(TestContext context) {
        JoltTransformer.transform(null, identitySpec).onComplete(transform -> {
            context.assertFalse(transform.succeeded());
            context.assertNull(transform.result());
            context.assertEquals(NullPointerException.class, transform.cause().getClass());
        });
    }

    @Test
    public void testTransformInputEmptyString(TestContext context) {
        JoltTransformer.transform("", identitySpec).onComplete(transform -> {
            context.assertFalse(transform.succeeded());
            context.assertNull(transform.result());
            context.assertEquals(JsonUnmarshalException.class, transform.cause().getClass());
        });
    }

    @Test
    public void testTransformInputNotJsonString(TestContext context) {
        JoltTransformer.transform("abcd", identitySpec).onComplete(transform -> {
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
        JoltTransformer.transform("{}", null).onComplete(transform -> {
            context.assertFalse(transform.succeeded());
            context.assertNull(transform.result());
            context.assertEquals(NullPointerException.class, transform.cause().getClass());
        });
    }

    @Test
    public void testTransformInputSpecInvalid(TestContext context) {
        JoltTransformer.transform("{}", new JoltSpec(null)).onComplete(transform -> {
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

    @Test
    public void testTransformWithMetadata(TestContext context) {
        JoltTransformer.transform(TRANSFORM_WITH_METADATA_INPUT_JSON, transformWithMetadataSpec).onComplete(transform -> {
            context.assertTrue(transform.succeeded());
            context.assertNotNull(transform.result());
            context.assertEquals(new JsonObject(TRANSFORM_WITH_METADATA_EXPECTED_OUTPUT_JSON), transform.result());
        });
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

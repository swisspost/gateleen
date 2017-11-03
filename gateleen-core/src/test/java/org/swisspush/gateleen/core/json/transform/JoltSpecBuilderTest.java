package org.swisspush.gateleen.core.json.transform;

import com.bazaarvoice.jolt.exception.JsonUnmarshalException;
import com.bazaarvoice.jolt.exception.SpecException;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for the {@link JoltSpecBuilder} class
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class JoltSpecBuilderTest {

    @Test
    public void testBuildSpecNull(TestContext context) {
        JoltSpecBuilder.buildSpec(null).setHandler(event -> {
            context.assertFalse(event.succeeded());
            context.assertNull(event.result());
            context.assertEquals(NullPointerException.class, event.cause().getClass());
        });
    }

    @Test
    public void testBuildSpecEmptyString(TestContext context) {
        JoltSpecBuilder.buildSpec("").setHandler(event -> {
            context.assertFalse(event.succeeded());
            context.assertNull(event.result());
            context.assertEquals(JsonUnmarshalException.class, event.cause().getClass());
        });
    }

    @Test
    public void testBuildSpecEmptyJson(TestContext context) {
        JoltSpecBuilder.buildSpec("{}").setHandler(event -> {
            context.assertFalse(event.succeeded());
            context.assertNull(event.result());
            context.assertEquals(JsonUnmarshalException.class, event.cause().getClass());
        });
    }

    @Test
    public void testBuildSpecNoJsonArray(TestContext context) {
        JoltSpecBuilder.buildSpec("{\"abc\":234}").setHandler(event -> {
            context.assertFalse(event.succeeded());
            context.assertNull(event.result());
            context.assertEquals(JsonUnmarshalException.class, event.cause().getClass());
        });
    }

    @Test
    public void testBuildSpecMissingOperation(TestContext context){
        JoltSpecBuilder.buildSpec("[{\"abc\":234}]").setHandler(event -> {
            context.assertFalse(event.succeeded());
            context.assertNull(event.result());
            context.assertEquals(SpecException.class, event.cause().getClass());
        });
    }

    @Test
    public void testBuildSpecInvalidOperation(TestContext context){
        JoltSpecBuilder.buildSpec("[{\"operation\":\"xx\"}]").setHandler(event -> {
            context.assertFalse(event.succeeded());
            context.assertNull(event.result());
            context.assertEquals(SpecException.class, event.cause().getClass());
        });
    }

    @Test
    public void testBuildSpecMissingSpec(TestContext context){
        JoltSpecBuilder.buildSpec("[{\"operation\":\"shift\"}]").setHandler(event -> {
            context.assertFalse(event.succeeded());
            context.assertNull(event.result());
            context.assertEquals(SpecException.class, event.cause().getClass());
        });
    }

    @Test
    public void testBuildSpecInvalidSpec(TestContext context){
        String specWrongType = "[\n" +
                "  {\n" +
                "    \"operation\": \"shift\",\n" +
                "    \"spec\": 123\n" +
                "  }\n" +
                "]";
        JoltSpecBuilder.buildSpec(specWrongType).setHandler(event -> {
            context.assertFalse(event.succeeded());
            context.assertNull(event.result());
            context.assertEquals(SpecException.class, event.cause().getClass());
        });

        String specEmptyObject = "[\n" +
                "  {\n" +
                "    \"operation\": \"shift\",\n" +
                "    \"spec\": {}\n" +
                "  }\n" +
                "]";
        JoltSpecBuilder.buildSpec(specEmptyObject).setHandler(event -> {
            context.assertFalse(event.succeeded());
            context.assertNull(event.result());
            context.assertEquals(SpecException.class, event.cause().getClass());
        });

        String specWildcardNotAllowed = "[\n" +
                "  {\n" +
                "    \"operation\": \"shift\",\n" +
                "    \"spec\": {\n" +
                "      \"@1\": {\n" +
                "        \"@\": \"*\"\n" +
                "      }\n" +
                "    }\n" +
                "  }\n" +
                "]";
        JoltSpecBuilder.buildSpec(specWildcardNotAllowed).setHandler(event -> {
            context.assertFalse(event.succeeded());
            context.assertNull(event.result());
            context.assertEquals(SpecException.class, event.cause().getClass());
        });
    }

    @Test
    public void testBuildSpecValidSpec(TestContext context){
        String specWildcardNotAllowed = "[\n" +
                "  {\n" +
                "    \"operation\": \"shift\",\n" +
                "    \"spec\": {\n" +
                "      \"@1\": {\n" +
                "        \"@\": \"\"\n" +
                "      }\n" +
                "    }\n" +
                "  }\n" +
                "]";
        JoltSpecBuilder.buildSpec(specWildcardNotAllowed).setHandler(event -> {
            context.assertTrue(event.succeeded());
            context.assertNotNull(event.result());
            context.assertNotNull(event.result().getChainr());
        });
    }
}

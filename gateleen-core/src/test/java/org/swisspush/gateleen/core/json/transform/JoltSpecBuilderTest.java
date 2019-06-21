package org.swisspush.gateleen.core.json.transform;

import com.bazaarvoice.jolt.exception.JsonUnmarshalException;
import com.bazaarvoice.jolt.exception.SpecException;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.hamcrest.core.IsInstanceOf;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;

/**
 * Tests for the {@link JoltSpecBuilder} class
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class JoltSpecBuilderTest {

    @Rule
    public ExpectedException thrown= ExpectedException.none();

    @Test
    public void testBuildSpecNull(TestContext context) throws JoltSpecException {
        thrown.expect( JoltSpecException.class );
        thrown.expectCause(IsInstanceOf.instanceOf(NullPointerException.class));
        JoltSpecBuilder.buildSpec(null);
    }

    @Test
    public void testBuildSpecEmptyString(TestContext context) throws JoltSpecException {
        thrown.expect( JoltSpecException.class );
        thrown.expectCause(IsInstanceOf.instanceOf(JsonUnmarshalException.class));
        JoltSpecBuilder.buildSpec("");
    }

    @Test
    public void testBuildSpecEmptyJson(TestContext context) throws JoltSpecException {
        thrown.expect( JoltSpecException.class );
        thrown.expectCause(IsInstanceOf.instanceOf(JsonUnmarshalException.class));
        JoltSpecBuilder.buildSpec("{}");
    }

    @Test
    public void testBuildSpecNoJsonArray(TestContext context) throws JoltSpecException {
        thrown.expect( JoltSpecException.class );
        thrown.expectCause(IsInstanceOf.instanceOf(JsonUnmarshalException.class));
        JoltSpecBuilder.buildSpec("{\"abc\":234}");
    }

    @Test
    public void testBuildSpecMissingOperation(TestContext context) throws JoltSpecException {
        thrown.expect( JoltSpecException.class );
        thrown.expectCause(IsInstanceOf.instanceOf(SpecException.class));
        JoltSpecBuilder.buildSpec("[{\"abc\":234}]");
    }

    @Test
    public void testBuildSpecInvalidOperation(TestContext context) throws JoltSpecException {
        thrown.expect( JoltSpecException.class );
        thrown.expectCause(IsInstanceOf.instanceOf(SpecException.class));
        JoltSpecBuilder.buildSpec("[{\"operation\":\"xx\"}]");
    }

    @Test
    public void testBuildSpecMissingSpec(TestContext context) throws JoltSpecException {
        thrown.expect( JoltSpecException.class );
        thrown.expectCause(IsInstanceOf.instanceOf(SpecException.class));
        JoltSpecBuilder.buildSpec("[{\"operation\":\"shift\"}]");
    }

    @Test
    public void testBuildSpecInvalidSpecWrongType(TestContext context) throws JoltSpecException {
        thrown.expect( JoltSpecException.class );
        thrown.expectCause(IsInstanceOf.instanceOf(SpecException.class));

        String specWrongType = "[\n" +
                "  {\n" +
                "    \"operation\": \"shift\",\n" +
                "    \"spec\": 123\n" +
                "  }\n" +
                "]";
        JoltSpecBuilder.buildSpec(specWrongType);
    }

    @Test
    public void testBuildSpecInvalidSpecEmptyObject(TestContext context) throws JoltSpecException {
        thrown.expect( JoltSpecException.class );
        thrown.expectCause(IsInstanceOf.instanceOf(SpecException.class));

        String specEmptyObject = "[\n" +
                "  {\n" +
                "    \"operation\": \"shift\",\n" +
                "    \"spec\": {}\n" +
                "  }\n" +
                "]";
        JoltSpecBuilder.buildSpec(specEmptyObject);
    }

    @Test
    public void testBuildSpecInvalidSpecWildcardNotAllowed(TestContext context) throws JoltSpecException {
        thrown.expect( JoltSpecException.class );
        thrown.expectCause(IsInstanceOf.instanceOf(SpecException.class));

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
        JoltSpecBuilder.buildSpec(specWildcardNotAllowed);
    }

    @Test
    public void testBuildSpecValidSpec(TestContext context) throws JoltSpecException {
        String specValid = "[\n" +
                "  {\n" +
                "    \"operation\": \"shift\",\n" +
                "    \"spec\": {\n" +
                "      \"@\": \"\"\n" +
                "    }\n" +
                "  }\n" +
                "]";
        JoltSpec joltSpec = JoltSpecBuilder.buildSpec(specValid);
        context.assertNotNull(joltSpec);
        context.assertNotNull(joltSpec.getChainr());
    }

    @Test
    public void testBuildSpecValidSpecWithMetadata(TestContext context) throws JoltSpecException {
        String specValid = "[\n" +
                "  {\n" +
                "    \"operation\": \"shift\",\n" +
                "    \"spec\": {\n" +
                "      \"@\": \"\"\n" +
                "    }\n" +
                "  }\n" +
                "]";
        JoltSpec joltSpec = JoltSpecBuilder.buildSpecWithMetadata(specValid, true);
        context.assertNotNull(joltSpec);
        context.assertNotNull(joltSpec.getChainr());
        context.assertTrue(joltSpec.isWithMetadata());

        JoltSpec joltSpec2 = JoltSpecBuilder.buildSpecWithMetadata(specValid, false);
        context.assertNotNull(joltSpec2);
        context.assertNotNull(joltSpec2.getChainr());
        context.assertFalse(joltSpec2.isWithMetadata());
    }
}

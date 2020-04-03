package org.swisspush.gateleen.security.authorization;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.swisspush.gateleen.core.util.ResourcesUtils;
import org.swisspush.gateleen.validation.ValidationException;

import java.util.*;

/**
 * Tests for the {@link RoleMapperFactory} class
 */
@RunWith(VertxUnitRunner.class)
public class RoleMapperFactoryTest {

    private RoleMapperFactory roleMapperFactory;


    @Rule
    public ExpectedException thrown = ExpectedException.none();

    private final String INVALID_MAPPER_JSON = ResourcesUtils.loadResource("testresource_invalid_mapper_json", true);
    private final String VALID_MAPPER_RESOURCE = ResourcesUtils.loadResource("testresource_valid_mapper_resource", true);
    private final String ADDITIONAL_PROP_MAPPER_RESOURCE = ResourcesUtils.loadResource("testresource_additionalproperties_mapper_resource", true);
    private final String EMPTY_PROP_MAPPER_RESOURCE = ResourcesUtils.loadResource("testresource_emptyproperties_mapper_resource", true);


    @Before
    public void setUp() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("STAGE", "int");
        roleMapperFactory = new RoleMapperFactory(properties);
    }

    @Test
    public void testInvalidMapperJson() throws ValidationException {
        thrown.expect(ValidationException.class);
        thrown.expectMessage("Unable to parse json");
        roleMapperFactory.parseRoleMapper(Buffer.buffer(INVALID_MAPPER_JSON));
    }

    @Test
    public void testValidRoleMapperConfig(TestContext context) throws ValidationException {
        List<RoleMapperHolder> result = roleMapperFactory.parseRoleMapper(Buffer.buffer(VALID_MAPPER_RESOURCE));
        context.assertNotNull(result);
        context.assertEquals(1, result.size());
        RoleMapperHolder holder = result.get(0);
        context.assertNotNull(holder.getPattern());
        context.assertNotNull(holder.getRole());
    }

    @Test
    public void testAdditionalMapperPropertiesNotAllowed(TestContext context) {
        try {
            roleMapperFactory.parseRoleMapper(Buffer.buffer(ADDITIONAL_PROP_MAPPER_RESOURCE));
            context.fail("Should have thrown a ValidationException since 'notAllowedProperty' property is not allowed");
        } catch (ValidationException ex) {
            context.assertNotNull(ex.getValidationDetails());
            context.assertEquals(1, ex.getValidationDetails().size());
            for (Object obj : ex.getValidationDetails()) {
                JsonObject jsonObject = (JsonObject) obj;
                if ("additionalProperties".equalsIgnoreCase(jsonObject.getString("keyword"))) {
                    context.assertEquals("notAllowedProperty", jsonObject.getJsonArray("unwanted").getString(0));
                }
            }
        }
    }

    @Test
    public void testEmptyMapperPropertiesNotAllowed(TestContext context) {
        try {
            roleMapperFactory.parseRoleMapper(Buffer.buffer(EMPTY_PROP_MAPPER_RESOURCE));
            context.fail("Should have thrown a ValidationException since 'minLength' is set to 1 in the schema");
        } catch (ValidationException ex) {
            context.assertNotNull(ex.getValidationDetails());
            // validate that both attributes are reported to be invalid
            context.assertEquals(2, ex.getValidationDetails().size());
            for (Object obj : ex.getValidationDetails()) {
                JsonObject jsonObject = (JsonObject) obj;
                context.assertEquals("minLength", jsonObject.getString("type"));
            }
        }
    }

}

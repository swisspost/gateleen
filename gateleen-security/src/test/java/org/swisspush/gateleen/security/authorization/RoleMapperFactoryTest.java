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
 * Tests for the {@link AclFactory} class
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class RoleMapperFactoryTest {

    private RoleMapperFactory roleMapperFactory;

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    private final String INVALID_MAPPER_JSON = ResourcesUtils.loadResource("testresource_invalid_mapper_json", true);
    private final String VALID_MAPPER_RESOURCE = ResourcesUtils.loadResource("testresource_valid_mapper_resource", true);
    private final String ADDITIONAL_PROP_MAPPER_RESOURCE = ResourcesUtils.loadResource("testresource_additionalproperties_mapper_resource", true);


    @Before
    public void setUp() {
        roleMapperFactory = new RoleMapperFactory();
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
        checkAdditionalProperties(context, Buffer.buffer(ADDITIONAL_PROP_MAPPER_RESOURCE));
    }

    private void checkAdditionalProperties(TestContext context, Buffer buffer) {
        try {
            roleMapperFactory.parseRoleMapper(buffer);
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


}

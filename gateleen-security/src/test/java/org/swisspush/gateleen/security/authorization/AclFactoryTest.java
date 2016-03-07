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
import org.swisspush.gateleen.validation.validation.ValidationException;

import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * Tests for the {@link AclFactory} class
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class AclFactoryTest {

    private AclFactory aclFactory;

    @Rule
    public ExpectedException thrown= ExpectedException.none();

    private String schedulersSchema = ResourcesUtils.loadResource("gateleen_security_schema_acl", true);

    private final String INVALID_JSON = ResourcesUtils.loadResource("testresource_invalid_json", true);
    private final String VALID_ACL_RESOURCE = ResourcesUtils.loadResource("testresource_valid_acl_resource", true);
    private final String ADDITIONAL_PROP_ACL_RESOURCE = ResourcesUtils.loadResource("testresource_additionalproperties_acl_resource", true);

    @Before
    public void setUp(){
        aclFactory = new AclFactory(schedulersSchema);
    }

    @Test
    public void testInvalidJson() throws ValidationException {
        thrown.expect( ValidationException.class );
        thrown.expectMessage("Unable to parse json");
        aclFactory.parseAcl(Buffer.buffer(INVALID_JSON));
    }

    @Test
    public void testValidAclConfig(TestContext context) throws ValidationException {
        Map<PatternHolder, Set<String>> result = aclFactory.parseAcl(Buffer.buffer(VALID_ACL_RESOURCE));
        context.assertNotNull(result);
        context.assertEquals(1, result.size());
        Collection<Set<String>> values = result.values();
        for (Set<String> value : values) {
            context.assertTrue(value.containsAll(Arrays.asList("POST", "GET", "DELETE")));
            context.assertFalse(value.contains("PUT"));
        }
    }

    @Test
    public void testAdditionalPropertiesNotAllowed(TestContext context) throws ValidationException {
        try {
            aclFactory.parseAcl(Buffer.buffer(ADDITIONAL_PROP_ACL_RESOURCE));
            context.fail("Should have thrown a ValidationException since 'notAllowedProperty' property is not allowed");
        } catch(ValidationException ex){
            context.assertNotNull(ex.getValidationDetails());
            context.assertEquals(1, ex.getValidationDetails().size());
            for(Object obj : ex.getValidationDetails()){
                JsonObject jsonObject = (JsonObject) obj;
                if("additionalProperties".equalsIgnoreCase(jsonObject.getString("keyword"))){
                    context.assertEquals("notAllowedProperty", jsonObject.getJsonArray("unwanted").getString(0));
                }
            }
        }
    }
}

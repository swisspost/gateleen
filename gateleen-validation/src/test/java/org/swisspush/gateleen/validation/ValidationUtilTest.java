package org.swisspush.gateleen.validation;

import io.vertx.core.http.HttpMethod;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.slf4j.Logger;

import java.util.Map;
import java.util.Optional;

import static org.swisspush.gateleen.validation.ValidationUtil.matchingValidationResourceEntry;

/**
 * Test class for the ValidationUtil
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class ValidationUtilTest extends AbstractTest {

    private Logger log;

    @Before
    public void setUp() {
        log = Mockito.mock(Logger.class);
    }

    @Test
    public void testMatchingValidationResourceEntry(TestContext context) {
        ValidationResource validationResource = new ValidationResource();
        validationResource.addResource(Map.of(ValidationResource.METHOD_PROPERTY, "PUT", ValidationResource.URL_PROPERTY, "/some/other/resource"));

        Map<String, String> entryMap = matchingValidationResourceEntry(validationResource,
                new CustomHttpServerRequest(HttpMethod.PUT, "/some/other/resource"), log);
        context.assertNotNull(entryMap);
        context.assertEquals(entryMap.get(ValidationResource.METHOD_PROPERTY), "PUT");
        context.assertEquals(entryMap.get(ValidationResource.URL_PROPERTY), "/some/other/resource");

        entryMap = matchingValidationResourceEntry(validationResource,
                new CustomHttpServerRequest(HttpMethod.GET, "/some/other/resource"), log);
        context.assertNull(entryMap);

        entryMap = matchingValidationResourceEntry(validationResource,
                new CustomHttpServerRequest(HttpMethod.PUT, "/foo/bar/resource"), log);
        context.assertNull(entryMap);
    }

    @Test
    public void testMatchingSchemaLocation(TestContext context) {
        ValidationResource validationResource = new ValidationResource();
        validationResource.addResource(Map.of(
                ValidationResource.METHOD_PROPERTY, "PUT",
                ValidationResource.URL_PROPERTY, "/some/other/resource",
                ValidationResource.SCHEMA_LOCATION_PROPERTY, "/path/to/schema",
                ValidationResource.SCHEMA_KEEP_INMEMORY_PROPERTY, "120"
        ));

        Optional<SchemaLocation> optionalSchemaLocation = ValidationUtil.matchingSchemaLocation(validationResource,
                new CustomHttpServerRequest(HttpMethod.PUT, "/some/other/resource"), log);
        context.assertTrue(optionalSchemaLocation.isPresent());
        context.assertEquals("/path/to/schema", optionalSchemaLocation.get().schemaLocation());
        context.assertEquals(120, optionalSchemaLocation.get().keepInMemory());

        optionalSchemaLocation = ValidationUtil.matchingSchemaLocation(validationResource,
                new CustomHttpServerRequest(HttpMethod.GET, "/some/other/resource"), log);
        context.assertFalse(optionalSchemaLocation.isPresent());

        optionalSchemaLocation = ValidationUtil.matchingSchemaLocation(validationResource,
                new CustomHttpServerRequest(HttpMethod.PUT, "/foo/bar/resource"), log);
        context.assertFalse(optionalSchemaLocation.isPresent());

        validationResource.addResource(Map.of(
                ValidationResource.METHOD_PROPERTY, "PUT",
                ValidationResource.URL_PROPERTY, "/foo/bar/resource",
                ValidationResource.SCHEMA_LOCATION_PROPERTY, "/path/to/other/schema"
        ));

        optionalSchemaLocation = ValidationUtil.matchingSchemaLocation(validationResource,
                new CustomHttpServerRequest(HttpMethod.PUT, "/foo/bar/resource"), log);
        context.assertTrue(optionalSchemaLocation.isPresent());
        context.assertEquals("/path/to/other/schema", optionalSchemaLocation.get().schemaLocation());
        context.assertNull(optionalSchemaLocation.get().keepInMemory());

        ValidationResource validationResourceWithoutSchemaLocation = new ValidationResource();
        validationResourceWithoutSchemaLocation.addResource(Map.of(
                ValidationResource.METHOD_PROPERTY, "PUT",
                ValidationResource.URL_PROPERTY, "/some/other/resource"
        ));
        optionalSchemaLocation = ValidationUtil.matchingSchemaLocation(validationResourceWithoutSchemaLocation,
                new CustomHttpServerRequest(HttpMethod.PUT, "/some/other/resource"), log);
        context.assertFalse(optionalSchemaLocation.isPresent());
    }
}

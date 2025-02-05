package org.swisspush.gateleen.packing.validation;

import io.vertx.core.buffer.Buffer;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.swisspush.gateleen.core.validation.ValidationResult;

/**
 * Tests for the {@link PackingValidatorImpl} class
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class PackingValidatorImplTest {

    private PackingValidatorImpl packingValidator;

    @Before
    public void setUp() {
        packingValidator = new PackingValidatorImpl();
    }

    @Test
    public void invalidData_returnsNegativeValidationResult(TestContext context) {
        Buffer data = Buffer.buffer("{\"invalid\": \"data\"}");
        ValidationResult validationResult = packingValidator.validatePackingPayload(data);
        context.assertFalse(validationResult.isSuccess());
        context.assertEquals("Validation failed", validationResult.getMessage());

        data = Buffer.buffer("{}");
        validationResult = packingValidator.validatePackingPayload(data);
        context.assertFalse(validationResult.isSuccess());
        context.assertEquals("Validation failed", validationResult.getMessage());

        data = Buffer.buffer("some string");
        validationResult = packingValidator.validatePackingPayload(data);
        context.assertFalse(validationResult.isSuccess());
        context.assertEquals("Unable to parse json", validationResult.getMessage());

        validationResult = packingValidator.validatePackingPayload(null);
        context.assertFalse(validationResult.isSuccess());
        context.assertEquals("Unable to parse json", validationResult.getMessage());
    }

    @Test
    public void validateEmptyArray(TestContext context) {
        Buffer data = Buffer.buffer("{\"requests\": []}");
        ValidationResult validationResult = packingValidator.validatePackingPayload(data);
        context.assertTrue(validationResult.isSuccess());
    }

    @Test
    public void validateValid(TestContext context) {
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
        ValidationResult validationResult = packingValidator.validatePackingPayload(data);
        context.assertTrue(validationResult.isSuccess());

        Buffer dataNoHeaders = Buffer.buffer("{\n" +
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
        validationResult = packingValidator.validatePackingPayload(dataNoHeaders);
        context.assertTrue(validationResult.isSuccess());

        Buffer dataMultipleRequests = Buffer.buffer("{\n" +
                "  \"requests\": [\n" +
                "    {\n" +
                "      \"uri\": \"/playground/some/url\",\n" +
                "      \"method\": \"PUT\",\n" +
                "      \"payload\": {\n" +
                "        \"key\": 1,\n" +
                "        \"key2\": [1,2,3]\n" +
                "      },\n" +
                "      \"headers\": [[\"x-foo\", \"bar\"], [\"x-bar\", \"foo\"]]\n" +
                "    },\n" +
                "    {\n" +
                "      \"uri\": \"/playground/some/other/url\",\n" +
                "      \"method\": \"POST\",\n" +
                "      \"payload\": {\n" +
                "      }\n" +
                "    }    \n" +
                "  ]\n" +
                "}");
        validationResult = packingValidator.validatePackingPayload(dataMultipleRequests);
        context.assertTrue(validationResult.isSuccess());
    }

    @Test
    public void validateInValid(TestContext context) {
        Buffer dataWrongMethod = Buffer.buffer("{\n" +
                "  \"requests\": [\n" +
                "    {\n" +
                "      \"uri\": \"/playground/some/url\",\n" +
                "      \"method\": \"FOO\",\n" +
                "      \"payload\": {\n" +
                "        \"key\": 1,\n" +
                "        \"key2\": [1,2,3]\n" +
                "      },\n" +
                "      \"headers\": [[\"x-foo\", \"bar\"], [\"x-bar\", \"foo\"]]\n" +
                "    }\n" +
                "  ]\n" +
                "}");
        ValidationResult validationResult = packingValidator.validatePackingPayload(dataWrongMethod);
        context.assertFalse(validationResult.isSuccess());
        context.assertEquals("Validation failed", validationResult.getMessage());
        context.assertTrue(validationResult.getValidationDetails().encode().contains("$.requests[0].method: does not have a value in the enumeration"));

        Buffer dataMissingMethod = Buffer.buffer("{\n" +
                "  \"requests\": [\n" +
                "    {\n" +
                "      \"uri\": \"/playground/some/url\",\n" +
                "      \"payload\": {\n" +
                "        \"key\": 1,\n" +
                "        \"key2\": [1,2,3]\n" +
                "      },\n" +
                "      \"headers\": [[\"x-foo\", \"bar\"], [\"x-bar\", \"foo\"]]\n" +
                "    }\n" +
                "  ]\n" +
                "}");
        validationResult = packingValidator.validatePackingPayload(dataMissingMethod);
        context.assertFalse(validationResult.isSuccess());
        context.assertEquals("Validation failed", validationResult.getMessage());
        context.assertTrue(validationResult.getValidationDetails().encode().contains("$.requests[0].method: is missing but it is required"));

        Buffer dataMissingUri = Buffer.buffer("{\n" +
                "  \"requests\": [\n" +
                "    {\n" +
                "      \"method\": \"PUT\",\n" +
                "      \"payload\": {\n" +
                "        \"key\": 1,\n" +
                "        \"key2\": [1,2,3]\n" +
                "      },\n" +
                "      \"headers\": [[\"x-foo\", \"bar\"], [\"x-bar\", \"foo\"]]\n" +
                "    }\n" +
                "  ]\n" +
                "}");
        validationResult = packingValidator.validatePackingPayload(dataMissingUri);
        context.assertFalse(validationResult.isSuccess());
        context.assertEquals("Validation failed", validationResult.getMessage());
        context.assertTrue(validationResult.getValidationDetails().encode().contains("$.requests[0].uri: is missing but it is required"));

        Buffer dataAdditionalProperty = Buffer.buffer("{\n" +
                "  \"requests\": [\n" +
                "    {\n" +
                "      \"uri\": \"/playground/some/url\",\n" +
                "      \"notAllowed\": \"foobar\",\n" +
                "      \"method\": \"PUT\",\n" +
                "      \"payload\": {\n" +
                "        \"key\": 1,\n" +
                "        \"key2\": [1,2,3]\n" +
                "      },\n" +
                "      \"headers\": [[\"x-foo\", \"bar\"], [\"x-bar\", \"foo\"]]\n" +
                "    }\n" +
                "  ]\n" +
                "}");
        validationResult = packingValidator.validatePackingPayload(dataAdditionalProperty);
        context.assertFalse(validationResult.isSuccess());
        context.assertEquals("Validation failed", validationResult.getMessage());
        context.assertTrue(validationResult.getValidationDetails().encode()
                .contains("$.requests[0].notAllowed: is not defined in the schema and the schema does not allow additional properties"));

        Buffer dataWrongHeadersType = Buffer.buffer("{\n" +
                "  \"requests\": [\n" +
                "    {\n" +
                "      \"uri\": \"/playground/some/url\",\n" +
                "      \"method\": \"PUT\",\n" +
                "      \"headers\": \"foo\"\n" +
                "    },\n" +
                "    {\n" +
                "      \"uri\": \"/playground/some/other/url\",\n" +
                "      \"method\": \"POST\",\n" +
                "      \"payload\": {\n" +
                "      }\n" +
                "    }    \n" +
                "  ]\n" +
                "}");
        validationResult = packingValidator.validatePackingPayload(dataWrongHeadersType);
        context.assertFalse(validationResult.isSuccess());
        context.assertEquals("Validation failed", validationResult.getMessage());
        context.assertTrue(validationResult.getValidationDetails().encode().contains("$.requests[0].headers: string found, array expected"));

        Buffer dataWrongHeadersType2 = Buffer.buffer("{\n" +
                "  \"requests\": [\n" +
                "    {\n" +
                "      \"uri\": \"/playground/some/url\",\n" +
                "      \"method\": \"PUT\",\n" +
                "      \"headers\": [1,2,3]\n" +
                "    },\n" +
                "    {\n" +
                "      \"uri\": \"/playground/some/other/url\",\n" +
                "      \"method\": \"POST\",\n" +
                "      \"payload\": {\n" +
                "      }\n" +
                "    }    \n" +
                "  ]\n" +
                "}");
        validationResult = packingValidator.validatePackingPayload(dataWrongHeadersType2);
        context.assertFalse(validationResult.isSuccess());
        context.assertEquals("Validation failed", validationResult.getMessage());
        context.assertTrue(validationResult.getValidationDetails().encode().contains("$.requests[0].headers[0]: integer found, array expected"));
    }
}

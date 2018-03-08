package org.swisspush.gateleen.hook;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.fge.jsonschema.main.JsonSchema;
import com.github.fge.jsonschema.main.JsonSchemaFactory;
import com.github.fge.jsonschema.util.JsonLoader;
import com.networknt.schema.ValidationMessage;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import org.apache.log4j.BasicConfigurator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.util.ResourcesUtils;

import java.util.Set;

public class JsonSchemaValidatorPerformanceCompare {

    private final Logger log = LoggerFactory.getLogger(JsonSchemaValidatorPerformanceCompare.class);

    public static void main(String[] args) throws Exception {
        BasicConfigurator.configure();
        String hookSchema = ResourcesUtils.loadResource("gateleen_hooking_schema_hook", true);
        final JsonSchema schemaFGE = JsonSchemaFactory.byDefault().getJsonSchema(JsonLoader.fromString(hookSchema));
        JsonObject schemaObject = new JsonObject(hookSchema);

        // FGE-Lib: 34.3 us
        //  NT-Lib:  3.8 us
        String jsonString = "{" +
                "  'methods': ['PUT','POST','DELETE']," +
                "  'destination':'/go/somewhere'," +
                "  'filter':'.*'," +
                "  'headers': [{'header':'x-y', 'value':'gugus'}]," +
                "  'queueExpireAfter':30" +
                "}";
        jsonString = jsonString.replace('\'', '"');
        final JsonNode json = JsonLoader.fromString(jsonString);
        final Buffer buffer = Buffer.buffer(jsonString);


        final com.networknt.schema.JsonSchema schemaNT = com.networknt.schema.JsonSchemaFactory.getInstance().getSchema(hookSchema);


        while (true) {
            long t0 = System.nanoTime();
            for (int i = 0; i < 100_000; i++) {
                // Gateleen's own validator (based on FGE)
//                ValidationResult validationResult = Validator.validateStatic(buffer, hookSchema, log);

                // bare FGE with preparsed schema instance
//                ProcessingReport processingMessages = schemaFGE.validateUnchecked(JsonLoader.fromString(jsonString));
//                ProcessingReport processingMessages = schemaFGE.validateUnchecked(json);
//                Assert.assertTrue(processingMessages.isSuccess());

                // NetworkNT's validator - 10x faster than FGE and still under active development
                final Set<ValidationMessage> validateMessages = schemaNT.validate(JsonLoader.fromString(jsonString));
//                final Set<ValidationMessage> validateMessages = schema.validate(json);
//                Assert.assertEquals(0, validateMessages.size());
            }
            long t = (System.nanoTime() - t0) / 100_000;
            System.out.println((t / 1000.0) + " us");
        }
    }


}

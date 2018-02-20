package org.swisspush.gateleen.hook;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.fge.jsonschema.util.JsonLoader;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.ValidationMessage;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.URL;
import java.util.Set;

public class HookSchemaTest {

    JsonSchema schema;

    @Before
    public void before() {
        URL url = HookSchemaTest.class.getResource("/gateleen_hooking_schema_hook");
        schema = JsonSchemaFactory.getInstance().getSchema(url);
    }

    @Test
    public void validMaximalHook() {
        JsonNode json = parse("{" +
                "  'methods': ['OPTIONS','GET','HEAD','POST','PUT','DELETE','PATCH']," +
                "  'filter':'.*'," +
                "  'headers': [{'header':'x-y', 'value':'gugus', 'mode':'complete'}]," +
                "  'destination':'/go/somewhere'," +
                "  'expireAfter':30," +
                "  'queueExpireAfter':30," +
                "  'type':'after'," +
                "  'fullUrl':true," +
                "  'queueingStrategy':{'type':'reducedPropagation','intervalMs':1000}" +
                "}");

        Set<ValidationMessage> valMsgs = schema.validate(json);
        dumpValidationMessages(valMsgs);
        Assert.assertEquals("No validation messages", 0, valMsgs.size());
    }

    @Test
    public void validMinimalHook() {
        JsonNode json = parse("{'destination':'/'}");
        Set<ValidationMessage> valMsgs = schema.validate(json);
        dumpValidationMessages(valMsgs);
        Assert.assertEquals("No validation messages", 0, valMsgs.size());
    }

    @Test
    public void invalidMissingDestination() {
        JsonNode json = parse("{}");
        Set<ValidationMessage> valMsgs = schema.validate(json);
        dumpValidationMessages(valMsgs);
        Assert.assertEquals("One validation messages", 1, valMsgs.size());
    }

    @Test
    public void illegalAdditionalProperty() {
        JsonNode json = parse("{'destination':'/', 'illegal':0}");
        Set<ValidationMessage> valMsgs = schema.validate(json);
        dumpValidationMessages(valMsgs);
        Assert.assertEquals("One validation messages", 1, valMsgs.size());
    }

    @Test
    public void totallyWrongTypes() {
        JsonNode json = parse("{" +
                "  'methods': true," +
                "  'filter':true," +
                "  'headers': true," +
                "  'destination':true," +
                "  'expireAfter':true," +
                "  'queueExpireAfter':true," +
                "  'type':true," +
                "  'fullUrl':'notBoolean'," +
                "  'queueingStrategy':true" +
                "}");
        Set<ValidationMessage> valMsgs = schema.validate(json);
        dumpValidationMessages(valMsgs);
        Assert.assertEquals("One validation messages", 10, valMsgs.size());
    }

    @Test
    public void nearlyValidButSmallDeviations() {
        JsonNode json = parse("{" +
                "  'methods': ['INVALID-HTTP-METHOD']," +
                "  'headers': [{'header':'x-y', 'value':'gugus', 'mode':'INVALID-MODE'}]," +
                "  'destination':'/go/somewhere'," +
                "  'expireAfter':-9999," +
                "  'queueExpireAfter':30.5," +
                "  'type':'INVALID-TYPE'," +
                "  'fullUrl':true," +
                "  'queueingStrategy':{'type':'INVALID-TYPE'}" +
                "}");

        Set<ValidationMessage> valMsgs = schema.validate(json);
        dumpValidationMessages(valMsgs);
        Assert.assertEquals("No validation messages", 6, valMsgs.size());
    }

    private JsonNode parse(String s) {
        s = s.replace('\'', '"');
        try {
            return JsonLoader.fromString(s);
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    private void dumpValidationMessages(Set<ValidationMessage> valMsgs) {
        valMsgs.forEach(vm -> System.out.println(vm.toString()));
    }
}

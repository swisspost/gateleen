package org.swisspush.gateleen.hook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.ValidationMessage;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.swisspush.gateleen.core.util.ResourcesUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Set;

public class HookSchemaTest {

    JsonSchema schema;

    @Before
    public void before() {
        String hookSchema = ResourcesUtils.loadResource("gateleen_hooking_schema_hook", true);
        schema = JsonSchemaFactory.getInstance().getSchema(hookSchema);
    }

    @Test
    public void validMaximalHook() {
        JsonNode json = parse("{" +
                "  'methods': ['OPTIONS','GET','HEAD','POST','PUT','DELETE','PATCH']," +
                "  'filter':'.*'," +
                "  'headers': [{'header':'x-y', 'value':'gugus', 'mode':'complete'}]," +
                "  'headersFilter':'x-foo.*'," +
                "  'destination':'/go/somewhere'," +
                "  'expireAfter':30," +
                "  'queueExpireAfter':30," +
                "  'type':'after'," +
                "  'fullUrl':true," +
                "  'queueingStrategy':{'type':'reducedPropagation','intervalMs':1000}," +
                "  'collection':false," +
                "  'listable':true," +
                "  'proxyOptions':{'type':'HTTP', 'host':'someHost', 'port':1234, 'username':'johndoe', 'password':'secret'}" +
                "}");

        Set<ValidationMessage> valMsgs = schema.validate(json);
        dumpValidationMessages(valMsgs);
        Assert.assertEquals("No validation messages", 0, valMsgs.size());
    }

    @Test
    public void invalidProxyOptions() {
        JsonNode json = parse("{" +
                "  'destination':'/go/somewhere'," +
                "  'proxyOptions':{'type':'unknown', 'host':'someHost', 'port':1234, 'username':'johndoe', 'password':'secret'}" +
                "}");

        Set<ValidationMessage> valMsgs = schema.validate(json);
        dumpValidationMessages(valMsgs);
        Assert.assertEquals("One validation message", 1, valMsgs.size());
        Assert.assertEquals("$.proxyOptions.type: does not have a value in the enumeration [HTTP, SOCKS4, SOCKS5]", new ArrayList<>(valMsgs).get(0).getMessage());
    }

    @Test
    public void validWithLegacyStaticHeaders() {
        JsonNode json = parse("{" +
                "  'destination':'/go/somewhere'," +
                "  'staticHeaders':{" +
                "    'header-a':'value-a'," +
                "    'header-b':true" +            // implementation allows any type, especially boolean ...
                "  }" +
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
    public void invalidWhenMixingHeadersAndStaticHeaders() {
        JsonNode json = parse("{" +
                "  'destination':'/go/somewhere'," +
                "  'staticHeaders':{}," +
                "  'headers':[]" +
                "}");

        Set<ValidationMessage> valMsgs = schema.validate(json);
        dumpValidationMessages(valMsgs);
        Assert.assertEquals("One validation messages", 1, valMsgs.size());
    }

    @Test
    public void invalidWhenHeadersFilterContainsEmptyPattern() {
        JsonNode json = parse("{" +
                "  'destination':'/go/somewhere'," +
                "  'headersFilter':''" +
                "}");

        Set<ValidationMessage> valMsgs = schema.validate(json);
        dumpValidationMessages(valMsgs);
        Assert.assertEquals("One validation messages", 1, valMsgs.size());
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

    @Test
    public void validWithTranslateStatus() {
        JsonNode json = parse("{" +
                "  'destination':'/go/somewhere'," +
                "  'translateStatus':{" +
                "    '400':200," +
                "    '404':200" +
                "  }" +
                "}");

        Set<ValidationMessage> valMsgs = schema.validate(json);
        dumpValidationMessages(valMsgs);
        Assert.assertEquals("No validation messages", 0, valMsgs.size());
    }

    @Test
    public void invalidTranslateStatusValues() {
        JsonNode jsonNotNumber = parse("{" +
                "  'destination':'/go/somewhere'," +
                "  'translateStatus':{" +
                "    '400':'not a number'," +
                "    '404':200" +
                "  }" +
                "}");

        Set<ValidationMessage> valMsgs = schema.validate(jsonNotNumber);
        dumpValidationMessages(valMsgs);
        Assert.assertEquals("One validation message", 1, valMsgs.size());
        for (ValidationMessage valMsg : valMsgs) {
            Assert.assertEquals("$.translateStatus.400: string found, integer expected", valMsg.getMessage());
        }

        JsonNode jsonNotInteger = parse("{" +
                "  'destination':'/go/somewhere'," +
                "  'translateStatus':{" +
                "    '400':200," +
                "    '404':200.99" +
                "  }" +
                "}");

        valMsgs = schema.validate(jsonNotInteger);
        dumpValidationMessages(valMsgs);
        Assert.assertEquals("One validation message", 1, valMsgs.size());
        for (ValidationMessage valMsg : valMsgs) {
            Assert.assertEquals("$.translateStatus.404: number found, integer expected", valMsg.getMessage());
        }
    }

    private JsonNode parse(String s) {
        s = s.replace('\'', '"');
        try {
            return new ObjectMapper().readTree(s);
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    private void dumpValidationMessages(Set<ValidationMessage> valMsgs) {
        valMsgs.forEach(vm -> System.out.println(vm.toString()));
    }
}

package org.swisspush.gateleen.core.util;

import com.google.common.collect.ImmutableMap;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.testng.Assert;

import java.util.HashMap;
import java.util.Map;

/**
 * Tests for the {@link StringUtils} class
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class StringUtilsTest {

    private String text = "Test";
    private String def = "Default";
    private String empty = "";
    private String nullText = null;

    @Rule
    public ExpectedException thrown= ExpectedException.none();

    @Test
    public void testIsEmpty(TestContext context) {
        context.assertTrue(StringUtils.isEmpty(nullText));
        context.assertTrue(StringUtils.isEmpty(""));
        context.assertFalse(StringUtils.isEmpty("  "));
        context.assertFalse(StringUtils.isEmpty(text));
        context.assertFalse(StringUtils.isEmpty(" " + text + " "));
    }

    @Test
    public void testIsNotEmpty(TestContext context) {
        context.assertFalse(StringUtils.isNotEmpty(nullText));
        context.assertFalse(StringUtils.isNotEmpty(""));
        context.assertTrue(StringUtils.isNotEmpty("  "));
        context.assertTrue(StringUtils.isNotEmpty(text));
        context.assertTrue(StringUtils.isNotEmpty(" " + text + " "));
    }

    @Test
    public void testIsNotEmptyTrimmed(TestContext context) {
        context.assertFalse(StringUtils.isNotEmptyTrimmed(nullText));
        context.assertFalse(StringUtils.isNotEmptyTrimmed(""));
        context.assertFalse(StringUtils.isNotEmptyTrimmed("  "));
        context.assertTrue(StringUtils.isNotEmptyTrimmed(text));
        context.assertTrue(StringUtils.isNotEmptyTrimmed(" " + text + " "));
    }

    @Test
    public void testGetStringOrEmpty(TestContext context){
        context.assertEquals(empty, StringUtils.getStringOrEmpty(null));
        context.assertEquals(empty, StringUtils.getStringOrEmpty(""));
        context.assertEquals(empty, StringUtils.getStringOrEmpty(" "));
        context.assertEquals(text, StringUtils.getStringOrEmpty(text));
        context.assertEquals(text, StringUtils.getStringOrEmpty(" " + text + " "));
    }

    @Test
    public void testGetStringOrDefault(TestContext context){
        context.assertEquals(def, StringUtils.getStringOrDefault(null, def));
        context.assertEquals(def, StringUtils.getStringOrDefault(empty, def));
        context.assertEquals(def, StringUtils.getStringOrDefault(" ", def));
        context.assertEquals(text, StringUtils.getStringOrDefault(text, def));
        context.assertEquals(text, StringUtils.getStringOrDefault(" " + text + " ", def));
    }

    @Test
    public void testReplaceWildcardConfigs(TestContext context){
        String contentWithWildcards = "This is a very ${adjective} helper method";
        Map<String, Object> properties = ImmutableMap.of("adjective", "nice");

        context.assertEquals("This is a very nice helper method", StringUtils.replaceWildcardConfigs(contentWithWildcards, properties));
        context.assertEquals("This is a very ${adjective} helper method", StringUtils.replaceWildcardConfigs(contentWithWildcards, null));
        context.assertNull(null, StringUtils.replaceWildcardConfigs(contentWithWildcards, properties));
        context.assertNull(null, StringUtils.replaceWildcardConfigs(contentWithWildcards, null));

        contentWithWildcards = "This is text does not contain any wildcard";
        context.assertEquals("This is text does not contain any wildcard", StringUtils.replaceWildcardConfigs(contentWithWildcards, properties));
        context.assertEquals("This is text does not contain any wildcard", StringUtils.replaceWildcardConfigs(contentWithWildcards, new HashMap<>()));
    }

    @Test
    public void testReplaceWildcardConfigsWithEmptyProperties(TestContext context){
        thrown.expect( IllegalArgumentException.class );
        thrown.expectMessage("Could not resolve adjective");

        String contentWithWildcards = "This is a very ${adjective} helper method";
        StringUtils.replaceWildcardConfigs(contentWithWildcards, new HashMap<>());
    }


    @Test
    public void testReplaceJSONAttributeWildcardConfigs(TestContext context){
        Map<String, Object> properties = ImmutableMap.of("wildcard", "value");
        String expectedResult = "\"attribute\" : value";

        // check that the old stuff with normal tag (invalid json) still works
        String contentWithWildcards = "\"attribute\" : ${wildcard}";
        context.assertEquals( expectedResult, StringUtils.replaceWildcardConfigs(contentWithWildcards, properties));

        // now check that the new special tag (valid json) does it's job properly as well, the result must be the same
        // but this time, the given JSON is valid
        contentWithWildcards = "\"attribute\" : \"$${wildcard}\"";
        context.assertEquals(expectedResult, StringUtils.replaceWildcardConfigs(contentWithWildcards, properties));

    }

    @Test
    public void testApplyRoutingRuleWithEscapes(TestContext context) throws Exception {
        Map<String, Object> properties = ImmutableMap.of("dummy", "value");
        String test = "/playground/[^/]*\\\\.html";
        String expected = "/playground/[^/]*\\.html";
        String result = StringUtils.replaceWildcardConfigs(test, properties);
        context.assertEquals(expected, result);
        context.assertTrue("/playground/test.html".matches(result));
    }

    @Test
    public void testApplyPlaygroundRoutingRules(TestContext context) throws Exception {
        Map<String, Object> properties = ImmutableMap.of("pathVariable", "my/test/path","portVariable","123");
        URL url = getClass().getClassLoader().getResource("tokenReplacementTest.json");
        Path path = Paths.get(url.toURI());
        String rules = new String(Files.readAllBytes(path));
        String result =  StringUtils.replaceWildcardConfigs(rules, properties);
        context.assertTrue(result.indexOf("/playground/([^/]*\\\\.html)")>=0);
        context.assertTrue(result.indexOf("\"path\": \"my/test/path\"")>=0);
        context.assertTrue(result.indexOf("\"port\": 123")>=0);
    }

    /**
     * Within 10000 invocations and 10 threads we normally see it fail consistently if there is threading issue.
     */
    @org.testng.annotations.Test(threadPoolSize = 10, invocationCount = 10000, timeOut = 10000)
    public void testConcurrentAccessToJmteEngine() {
        String test = "/playground/[^/]*\\\\.html";
        String expected = "/playground/[^/]*\\.html";
        String result = StringUtils.replaceWildcardConfigs(test, new HashMap<>());
        Assert.assertEquals(expected, result);
    }

}

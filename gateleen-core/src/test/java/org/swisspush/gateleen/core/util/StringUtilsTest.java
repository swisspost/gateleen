package org.swisspush.gateleen.core.util;

import com.google.common.collect.ImmutableMap;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;

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
}

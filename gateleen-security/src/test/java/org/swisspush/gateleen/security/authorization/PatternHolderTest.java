package org.swisspush.gateleen.security.authorization;

import io.vertx.core.MultiMap;

import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.swisspush.gateleen.security.PatternHolder;

import java.util.regex.Pattern;

/**
 * Tests for the {@link PatternHolder} class
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class PatternHolderTest {

    @Test
    public void testEquals(TestContext context) {
        PatternHolder pH = new PatternHolder("(mouse|cat|dog|wolf|bear|human)");
        PatternHolder pH2 = new PatternHolder("(mouse|cat|dog|wolf|bear|human)");
        PatternHolder pH3 = new PatternHolder("(wolf|bear|human)");
        context.assertFalse(pH.equals(1));
        context.assertTrue(pH.equals(pH2));
        context.assertFalse(pH.equals(pH3));

        PatternHolder pHW = new PatternHolder("(mouse|cat|dog|<foo>|bear|human)");
        PatternHolder pHW2 = new PatternHolder("(mouse|cat|dog|<foo>|bear|human)");
        PatternHolder pHW3 = new PatternHolder("(mouse|cat|dog|<bar>|bear|human)");
        PatternHolder pHW4 = new PatternHolder("(mouse|cat|dog|human)");
        context.assertTrue(pHW.equals(pHW2));
        context.assertFalse(pHW2.equals(pHW3));
        context.assertFalse(pHW3.equals(pHW4));
    }

    @Test
    public void testHashCode(TestContext context) {
        PatternHolder pH = new PatternHolder("(mouse|cat|dog|wolf|bear|human)");
        PatternHolder pH2 = new PatternHolder("(mouse|cat|dog|wolf|bear|human)");
        PatternHolder pH3 = new PatternHolder("(wolf|bear|human)");
        context.assertEquals(pH.hashCode(), pH2.hashCode());
        context.assertNotEquals(pH.hashCode(), pH3.hashCode());

        PatternHolder pHW = new PatternHolder("(mouse|cat|dog|<foo>|bear|human)");
        PatternHolder pHW2 = new PatternHolder("(mouse|cat|dog|<foo>|bear|human)");
        PatternHolder pHW3 = new PatternHolder("(mouse|cat|dog|<bar>|bear|human)");
        PatternHolder pHW4 = new PatternHolder("(mouse|cat|dog|human)");
        context.assertEquals(pHW.hashCode(), pHW2.hashCode());
        context.assertNotEquals(pHW2.hashCode(), pHW3.hashCode());
        context.assertNotEquals(pHW3.hashCode(), pHW4.hashCode());
    }

    @Test
    public void testToString(TestContext context) {
        PatternHolder pH = new PatternHolder("(mouse|cat|dog|wolf|bear|human)");
        String patternStr = "PatternHolder{pattern=(mouse|cat|dog|wolf|bear|human), patternStr='null'}";
        context.assertEquals(patternStr, pH.toString());

        pH = new PatternHolder("(mouse|cat|dog|<foo>|bear|human)");
        patternStr = "PatternHolder{pattern=null, patternStr='(mouse|cat|dog|<foo>|bear|human)'}";
        context.assertEquals(patternStr, pH.toString());
    }

    @Test
    public void testGetPatternNoWildcards(TestContext context) {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        String pattern = "(mouse|cat|dog|wolf|bear|human)";
        PatternHolder pH = new PatternHolder(pattern);
        context.assertNotNull(pH.getPattern(headers));
        context.assertEquals(pattern, pH.getPattern(headers).pattern());

        headers.add("x-foo", "bar");
        context.assertEquals(pattern, pH.getPattern(headers).pattern(),
                "Pattern should not change when unused headers are present");
    }

    @Test
    public void testGetPatternWithWildcardsNoHeaders(TestContext context) {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        String patternWithWildcard = "/gateleen/<foo>/resources/.*";
        PatternHolder pH = new PatternHolder(patternWithWildcard);
        context.assertNotNull(pH.getPattern(headers));

        context.assertEquals(patternWithWildcard, pH.getPattern(headers).pattern(),
                "wildcard <foo> should not have been replaced");
    }

    @Test
    public void testGetPatternWithWildcards(TestContext context) {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        headers.add("foo", "bar");

        String patternWithWildcard = "/gateleen/<foo>/resources/.*";
        PatternHolder pH = new PatternHolder(patternWithWildcard);
        context.assertNotNull(pH.getPattern(headers));
        context.assertTrue(pH.getPattern(headers).matcher("/gateleen/bar/resources/item").matches());
        context.assertFalse(pH.getPattern(headers).matcher("/gateleen/other/resources/item").matches());
    }

    @Test
    public void testGetPatternWithSameWildcardTwice(TestContext context) {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        headers.add("foo", "bar");

        String patternWithSameWildcardTwice = "/gateleen/<foo>/resources/<foo>/.*";
        PatternHolder pH = new PatternHolder(patternWithSameWildcardTwice);
        context.assertTrue(pH.getPattern(headers).matcher("/gateleen/bar/resources/bar/item").matches());
        context.assertFalse(pH.getPattern(headers).matcher("/gateleen/bar/resources/other/item").matches());
    }

    @Test
    public void testGetPatternWithMultipleWildcards(TestContext context) {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        headers.add("foo", "bar");
        headers.add("zzz", "yyy");

        String patternWithMultipleWildcards = "/gateleen/<foo>/resources/<zzz>/.*";
        PatternHolder pH = new PatternHolder(patternWithMultipleWildcards);
        context.assertTrue(pH.getPattern(headers).matcher("/gateleen/bar/resources/yyy/item").matches());
        context.assertFalse(pH.getPattern(headers).matcher("/gateleen/bar/resources/other/item").matches());
    }

    @Test
    public void testWildcardHeaderValuesAreRegexLiterals(TestContext context) {
        PatternHolder holder = new PatternHolder("/resources/userspecific/<x-user>/info");
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();

        for (String value : new String[]{".*", "x|.*|x", "user.name", "[user]", "$1", "${user}",
                "user\\name", "user\\", "\\E.*\\Q"}) {
            headers.set("x-user", value);
            Pattern pattern = holder.getPattern(headers);
            context.assertTrue(pattern.matcher("/resources/userspecific/" + value + "/info").matches(),
                    "Header value must match literally: " + value);
            context.assertFalse(pattern.matcher("/resources/userspecific/batman/info").matches(),
                    "Header value must not match another user: " + value);
            context.assertFalse(pattern.matcher("/some/totally/unrelated/secret").matches(),
                    "Header value must not match an unrelated path: " + value);
        }
    }

    @Test
    public void testLiteralWildcardPreservesAclRegexGroups(TestContext context) {
        PatternHolder holder = new PatternHolder("/resources/userspecific/<x-user>/items/([0-9]{6})/foo");
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        headers.set("x-user", "user.name");

        Pattern pattern = holder.getPattern(headers);
        context.assertTrue(pattern.matcher("/resources/userspecific/user.name/items/123456/foo").matches());
        context.assertFalse(pattern.matcher("/resources/userspecific/userXname/items/123456/foo").matches());
        context.assertFalse(pattern.matcher("/resources/userspecific/user.name/items/abcdef/foo").matches());
    }

    @Test
    public void testHeaderWildcardValueCannotReplaceLaterWildcard(TestContext context) {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        headers.add("foo", "<bar>.*\\Qx");
        headers.add("bar", "victim");

        PatternHolder patternHolder = new PatternHolder("/private/<foo>/<bar>");
        java.util.regex.Pattern pattern = patternHolder.getPattern(headers);

        context.assertTrue(pattern.matcher("/private/<bar>.*\\Qx/victim").matches());
        context.assertFalse(pattern.matcher("/private/\\QvictimEVILx/victim").matches());
    }
}

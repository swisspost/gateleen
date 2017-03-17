package org.swisspush.gateleen.security.authorization;

import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;

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
        PatternHolder pH = new PatternHolder(Pattern.compile("(mouse|cat|dog|wolf|bear|human)"));
        PatternHolder pH2 = new PatternHolder(Pattern.compile("(mouse|cat|dog|wolf|bear|human)"));
        PatternHolder pH3 = new PatternHolder(Pattern.compile("(wolf|bear|human)"));
        context.assertFalse(pH.equals(new Integer(1)));
        context.assertTrue(pH.equals(pH2));
        context.assertFalse(pH.equals(pH3));
    }

    @Test
    public void testHashCode(TestContext context) {
        PatternHolder pH = new PatternHolder(Pattern.compile("(mouse|cat|dog|wolf|bear|human)"));
        String patternStr = "(mouse|cat|dog|wolf|bear|human)";
        context.assertEquals(patternStr.hashCode(), pH.hashCode());
    }

    @Test
    public void testToString(TestContext context) {
        PatternHolder pH = new PatternHolder(Pattern.compile("(mouse|cat|dog|wolf|bear|human)"));
        String patternStr = "(mouse|cat|dog|wolf|bear|human)";
        context.assertEquals(patternStr, pH.toString());
    }

    @Test
    public void testGetPattern(TestContext context) {
        PatternHolder pH = new PatternHolder(Pattern.compile("(mouse|cat|dog|wolf|bear|human)"));
        context.assertNotNull(pH.getPattern());
    }
}

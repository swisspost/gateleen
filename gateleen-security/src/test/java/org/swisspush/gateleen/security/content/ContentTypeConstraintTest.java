package org.swisspush.gateleen.security.content;

import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.swisspush.gateleen.security.PatternHolder;

import java.util.Arrays;
import java.util.Collections;

/**
 * Test class for the {@link ContentTypeConstraint}
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class ContentTypeConstraintTest {

    @Test
    public void testEqualsAndHashcode(TestContext context) {

        ContentTypeConstraint constraint_0 = new ContentTypeConstraint(new PatternHolder("/gateleen/contacts/zips/(.*)"),
                Arrays.asList(new PatternHolder("image/png"), new PatternHolder("image/bmp")));

        ContentTypeConstraint constraint_1 = new ContentTypeConstraint(new PatternHolder("/gateleen/contacts/zips/(.*)"),
                Arrays.asList(new PatternHolder("image/png"), new PatternHolder("image/bmp")));

        context.assertEquals(constraint_0, constraint_1);
        context.assertEquals(constraint_0.hashCode(), constraint_1.hashCode());

        ContentTypeConstraint constraint_2 = new ContentTypeConstraint(new PatternHolder("/gateleen/contacts/zips/(.*)"),
                Arrays.asList(new PatternHolder("image/png"), new PatternHolder("video/mp4")));

        context.assertNotEquals(constraint_1, constraint_2, "AllowedTypes do not match");
        context.assertNotEquals(constraint_1.hashCode(), constraint_2.hashCode(), "AllowedTypes do not match");

        ContentTypeConstraint constraint_3 = new ContentTypeConstraint(new PatternHolder("/gateleen/foobar/(.*)"),
                Arrays.asList(new PatternHolder("image/png"), new PatternHolder("image/bmp")));

        context.assertNotEquals(constraint_1, constraint_3, "urlPattern is not equal");
        context.assertNotEquals(constraint_1.hashCode(), constraint_3.hashCode(), "urlPattern is not equal");

        ContentTypeConstraint constraint_4 = new ContentTypeConstraint(new PatternHolder("/gateleen/contacts/zips/(.*)"),
                Collections.singletonList(new PatternHolder("image/png")));

        context.assertNotEquals(constraint_1, constraint_4, "Count of allowedTypes matters");
        context.assertNotEquals(constraint_1.hashCode(), constraint_4.hashCode(), "Count of allowedTypes matters");

        ContentTypeConstraint constraint_5 = new ContentTypeConstraint(new PatternHolder("/gateleen/contacts/zips/(.*)"),
                Arrays.asList(new PatternHolder("image/bmp"), new PatternHolder("image/png")));

        context.assertNotEquals(constraint_1, constraint_5, "Order of allowedTypes matters");
        context.assertNotEquals(constraint_1.hashCode(), constraint_5.hashCode(), "Order of allowedTypes matters");
    }
}

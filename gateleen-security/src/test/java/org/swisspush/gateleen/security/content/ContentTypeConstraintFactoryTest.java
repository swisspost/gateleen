package org.swisspush.gateleen.security.content;

import io.vertx.core.buffer.Buffer;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.swisspush.gateleen.core.util.ResourcesUtils;
import org.swisspush.gateleen.security.PatternHolder;

import java.util.List;

/**
 * Test class for the {@link ContentTypeConstraintFactory}
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class ContentTypeConstraintFactoryTest {

    private final String VALID_CONFIG = ResourcesUtils.loadResource("testresource_valid_contenttype_constraint_resource", true);
    private final String INVALID_PATTERNS_CONFIG = ResourcesUtils.loadResource("testresource_invalid_patterns_contenttype_constraint_resource", true);

    @Test
    public void createAllValid(TestContext context) {
        List<ContentTypeConstraint> constraints = ContentTypeConstraintFactory.create(Buffer.buffer(VALID_CONFIG));
        context.assertEquals(2, constraints.size());

        // Note that the order of the parsed configurations matters!
        ContentTypeConstraint constraint_0 = constraints.get(0);
        context.assertEquals(new PatternHolder("/gateleen/contacts/zips/(.*)"), constraint_0.urlPattern());

        List<PatternHolder> allowedTypes_0 = constraint_0.allowedTypes();
        context.assertEquals(1, allowedTypes_0.size());
        context.assertEquals(new PatternHolder("image/.*"), allowedTypes_0.get(0));

        ContentTypeConstraint constraint_1 = constraints.get(1);
        context.assertEquals(new PatternHolder("/gateleen/contacts/storage/(.*)"), constraint_1.urlPattern());

        List<PatternHolder> allowedTypes_1 = constraint_1.allowedTypes();
        context.assertEquals(3, allowedTypes_1.size());
        context.assertEquals(new PatternHolder("image/png"), allowedTypes_1.get(0));
        context.assertEquals(new PatternHolder("image/bmp"), allowedTypes_1.get(1));
        context.assertEquals(new PatternHolder("video/mp4"), allowedTypes_1.get(2));
    }

    @Test
    public void ignoreInvalidPatterns(TestContext context) {
        List<ContentTypeConstraint> constraints = ContentTypeConstraintFactory.create(Buffer.buffer(INVALID_PATTERNS_CONFIG));
        context.assertEquals(1, constraints.size());

        ContentTypeConstraint constraint_0 = constraints.get(0);
        context.assertEquals(new PatternHolder("/gateleen/contenttype/test/2/(.*)"), constraint_0.urlPattern());

        List<PatternHolder> allowedTypes_0 = constraint_0.allowedTypes();
        context.assertEquals(2, allowedTypes_0.size());
        context.assertEquals(new PatternHolder("image/png"), allowedTypes_0.get(0));
        context.assertEquals(new PatternHolder("video/mp4"), allowedTypes_0.get(1));
    }
}

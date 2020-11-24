package org.swisspush.gateleen.security.content;

import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.swisspush.gateleen.security.PatternHolder;

import java.util.*;

/**
 * Test class for the {@link ContentTypeConstraintRepository}
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class ContentTypeConstraintRepositoryTest extends ContentTypeConstraintTestBase {

    private ContentTypeConstraintRepository repository;

    @Before
    public void setUp() {
        repository = Mockito.spy(new ContentTypeConstraintRepository());
    }

    @Test
    public void findMatchingContentTypeConstraintWithEmptyConstraints(TestContext context) {
        context.assertFalse(repository.findMatchingContentTypeConstraint("/gateleen/some/url").isPresent());

        repository.setConstraints(Collections.singletonList(createConstraint("/gateleen/some/(.*)",
                Arrays.asList("image/png", "image/bmp"))));

        context.assertTrue(repository.findMatchingContentTypeConstraint("/gateleen/some/url").isPresent());

        repository.clearConstraints();
        context.assertFalse(repository.findMatchingContentTypeConstraint("/gateleen/some/url").isPresent());
    }

    @Test
    public void findMatchingContentTypeConstraint(TestContext context) {
        List<ContentTypeConstraint> constraints = Arrays.asList(
                createConstraint("/gateleen/test/abc/(.*)", Arrays.asList("image/png", "image/bmp")),
                createConstraint("/gateleen/test/(.*)", Arrays.asList("video/mp4")),
                createConstraint("/gateleen/some/(.*)", Arrays.asList("application/zip"))
        );
        repository.setConstraints(constraints);

        context.assertTrue(repository.findMatchingContentTypeConstraint("/gateleen/some/url").isPresent());
        context.assertFalse(repository.findMatchingContentTypeConstraint("/gateleen/other/url").isPresent());

        Optional<ContentTypeConstraint> optConstraint = repository.findMatchingContentTypeConstraint("/gateleen/test/xxx/1");
        context.assertTrue(optConstraint.isPresent());
        assertAllowedTypes(context, optConstraint.get(), Arrays.asList("video/mp4"));

        Optional<ContentTypeConstraint> optConstraint2 = repository.findMatchingContentTypeConstraint("/gateleen/test/abc/1");
        context.assertTrue(optConstraint2.isPresent());
        assertAllowedTypes(context, optConstraint2.get(), Arrays.asList("image/png", "image/bmp"));
    }

    private void assertAllowedTypes(TestContext context, ContentTypeConstraint contentTypeConstraint, List<String> allowedTypes){
        List<PatternHolder> allowedTypesList = new ArrayList<>();
        for (String allowedType : allowedTypes) {
            allowedTypesList.add(new PatternHolder(allowedType));
        }
        context.assertEquals(allowedTypesList, contentTypeConstraint.allowedTypes());
    }
}

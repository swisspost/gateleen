package org.swisspush.gateleen.core.util;

import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * <p>
 * Tests for the {@link ResourcesUtils} class
 * </p>
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class ResourcesUtilsTest {

    @Test
    public void testLoadExisitingResourceNoException(TestContext context){
        String content = ResourcesUtils.loadResource("ResourcesUtilsTest_TestResource", false);
        context.assertNotNull(content, "Resource should have been correctly loaded");
        context.assertTrue(content.contains("This is a test resource for the ResourcesUtilsTest class"), "Content of resource not as expected");
    }

    @Test
    public void testLoadMissingResourceNoException(TestContext context){
        String content = ResourcesUtils.loadResource("SomeUnknownResource", false);
        context.assertNull(content, "Resource should not have been correctly loaded");
    }

    @Test(expected = RuntimeException.class)
    public void testLoadMissingResourceThrowingException(){
        ResourcesUtils.loadResource("SomeUnknownResource", true);
    }

}

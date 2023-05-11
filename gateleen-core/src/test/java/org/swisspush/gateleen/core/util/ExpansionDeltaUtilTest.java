package org.swisspush.gateleen.core.util;

import io.vertx.core.MultiMap;
import io.vertx.core.http.impl.headers.HeadersMultiMap;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(VertxUnitRunner.class)
public class ExpansionDeltaUtilTest {

    @Test
    public void testMapToDelimetedString(TestContext context){

        MultiMap input = new HeadersMultiMap()
                .set("k1", "v1")
                .set("k2", "v2")
                .set("k3", "v3");

        String output = ExpansionDeltaUtil.mapToDelimetedString(input, "+");
        context.assertEquals("k1=v1+k2=v2+k3=v3", output);
    }

    @Test
    public void testRemoveFromEndOfString(TestContext context) {
        String input = "/some/path/";
        String output = ExpansionDeltaUtil.removeFromEndOfString(input, "/");
        context.assertEquals("/some/path", output);

        output = ExpansionDeltaUtil.removeFromEndOfString(input, "+");
        context.assertEquals("/some/path/", output);

        output = ExpansionDeltaUtil.removeFromEndOfString(input, null);
        context.assertEquals("/some/path/", output);
    }

    @Test
    public void testExtractCollectionFromPath(TestContext context) {
        context.assertEquals("foobar", ExpansionDeltaUtil.extractCollectionFromPath("/path/to/collection/foobar/"));
        context.assertEquals("foobar", ExpansionDeltaUtil.extractCollectionFromPath("/path/to/collection/foobar"));
        context.assertEquals("bar", ExpansionDeltaUtil.extractCollectionFromPath("/path/to/collection/foo/bar"));
    }
}

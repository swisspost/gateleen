package org.swisspush.gateleen.core.util;

import io.vertx.core.MultiMap;
import io.vertx.core.http.CaseInsensitiveHeaders;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * <p>
 * Tests for the {@link JsonObjectUtils} class
 * </p>
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class JsonObjectUtilsTest {

    @Test
    public void testMultiMapToJsonObject(TestContext context) {
        JsonObject converted = JsonObjectUtils.multiMapToJsonObject(null);
        context.assertEquals(new JsonObject(), converted);

        CaseInsensitiveHeaders map = new CaseInsensitiveHeaders();
        JsonObject expected = new JsonObject();

        context.assertEquals(expected, JsonObjectUtils.multiMapToJsonObject(map));

        map.add("key_1", "value_1");
        map.add("key_2", "value_2");
        expected.put("key_1", "value_1").put("key_2", "value_2");

        context.assertEquals(expected, JsonObjectUtils.multiMapToJsonObject(map));
    }

    @Test
    public void testJsonObjectToMultiMap(TestContext context) {
        context.assertTrue(JsonObjectUtils.jsonObjectToMultiMap(null).isEmpty());
        context.assertTrue(JsonObjectUtils.jsonObjectToMultiMap(new JsonObject()).isEmpty());

        JsonObject jso = new JsonObject().put("key_1", "value_1").put("key_2", "value_2");
        MultiMap entries = JsonObjectUtils.jsonObjectToMultiMap(jso);
        context.assertEquals(2, entries.size());
        context.assertEquals("value_1", entries.get("key_1"));
        context.assertEquals("value_2", entries.get("key_2"));

        JsonObject jso2 = new JsonObject().put("key_1", "value_1").put("key_2", 99).put("key_3", new JsonObject().put("abc", 123));
        entries = JsonObjectUtils.jsonObjectToMultiMap(jso2);
        context.assertEquals(3, entries.size());
        context.assertEquals("value_1", entries.get("key_1"));
        context.assertEquals("99", entries.get("key_2"));
        context.assertEquals("{\"abc\":123}", entries.get("key_3"));
    }
}

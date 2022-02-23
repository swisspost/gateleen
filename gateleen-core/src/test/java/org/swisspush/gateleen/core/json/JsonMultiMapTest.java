package org.swisspush.gateleen.core.json;

import io.vertx.core.MultiMap;

import io.vertx.core.json.JsonArray;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashSet;

import static org.junit.Assert.assertArrayEquals;

@RunWith(VertxUnitRunner.class)
public class JsonMultiMapTest {

	@Test
	public void testInAndOut(TestContext context) {
		MultiMap m = MultiMap.caseInsensitiveMultiMap();
		
		m.add("hello", "world");
		m.add("holà", "mundo");
		m.add("hallo", "Welt");
		m.add("hallo", "wereld");
		
		JsonArray json = JsonMultiMap.toJson(m);
		MultiMap m2 = JsonMultiMap.fromJson(json);
		
		String[] names1 = new HashSet<>(m.names()).toArray(new String[0]);
		String[] names2 = new HashSet<>(m2.names()).toArray(new String[0]);
		assertArrayEquals(names1, names2);
		context.assertEquals(3, names2.length);
		context.assertEquals("[Welt, wereld]", m2.getAll("hallo").toString());
	}

}

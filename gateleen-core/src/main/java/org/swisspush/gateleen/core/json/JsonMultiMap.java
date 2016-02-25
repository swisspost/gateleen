package org.swisspush.gateleen.core.json;

import java.util.Iterator;
import java.util.Map;

import io.vertx.core.MultiMap;
import io.vertx.core.http.CaseInsensitiveHeaders;
import io.vertx.core.json.JsonArray;

public final class JsonMultiMap {

    private JsonMultiMap(){}

	public static JsonArray toJson(MultiMap multiMap) {
		JsonArray result = new JsonArray();
		for(Map.Entry<String, String> entry: multiMap.entries()) {
			result.add(new JsonArray().add(entry.getKey()).add(entry.getValue()));
		}
		return result;
	}
	
	public static MultiMap fromJson(JsonArray json) {
		MultiMap result = new CaseInsensitiveHeaders();
		Iterator<Object> it = json.iterator();
		while(it.hasNext()) {
			Object next = it.next();
			if(next instanceof JsonArray) {
				JsonArray pair = (JsonArray)next;
				if(pair.size() == 2) {
					result.add(pair.getString(0), pair.getString(1));
				}
			}
		}
		return result;
	}
}

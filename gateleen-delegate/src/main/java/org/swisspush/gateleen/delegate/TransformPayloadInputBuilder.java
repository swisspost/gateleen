package org.swisspush.gateleen.delegate;

import com.google.common.base.Joiner;
import io.vertx.core.MultiMap;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.swisspush.gateleen.core.json.transform.JoltSpec;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

class TransformPayloadInputBuilder {

    private static final String HEADERS = "headers";
    private static final String PAYLOAD = "payload";
    private static final String URL_PARTS = "urlParts";

    public static String build(JoltSpec joltSpec, String delegateExecutionRequestJsonPayload, MultiMap headers, final Matcher matcher){
        if(joltSpec.isWithMetadata()){
            JsonObject withMetadata = new JsonObject();
            withMetadata.put(URL_PARTS, buildUrlParts(matcher));
            withMetadata.put(HEADERS, buildHeaders(headers));
            withMetadata.put(PAYLOAD, new JsonObject(delegateExecutionRequestJsonPayload));
            return withMetadata.encode();
        } else {
            return delegateExecutionRequestJsonPayload;
        }
    }

    private static JsonArray buildUrlParts(Matcher matcher){
        final int groupCount = matcher.groupCount();
        JsonArray arr = new JsonArray();
        for (int i = 0; i <= groupCount; i++) {
            arr.add("$"+i);
        }
        String encoded = matcher.replaceAll(arr.encode());
        return new JsonArray(encoded);
    }

    private static JsonObject buildHeaders(MultiMap headers){
        JsonObject headerObject = new JsonObject();

        for (Map.Entry<String, String> header : headers) {
            final List<String> all = headers.getAll(header.getKey());
            headerObject.put(header.getKey(), Joiner.on(",").join(all));
        }
        return headerObject;
    }
}

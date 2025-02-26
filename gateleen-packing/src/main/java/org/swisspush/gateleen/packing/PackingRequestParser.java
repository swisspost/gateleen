package org.swisspush.gateleen.packing;

import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.impl.headers.HeadersMultiMap;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.swisspush.gateleen.core.http.HttpRequest;
import org.swisspush.gateleen.core.util.Result;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class PackingRequestParser {

    private static final String HEADERS = "headers";
    private static final String PAYLOAD = "payload";
    private static final String REQUESTS = "requests";
    private static final String CONTENT_LENGTH = "content-length";
    private static final String UNIQUE_ID = "x-rp-unique_id";
    private static final String UNIQUE_ID_DASH = "x-rp-unique-id";
    private static final String PACK_HEADER = "x-packed";
    private static final String COPY_ORIGINAL_HEADERS = "copy_original_headers";

    public static Result<List<HttpRequest>, String> parseRequests(Buffer data, MultiMap originalHeaders, String groupRequestHeader) {
        List<HttpRequest> requests = new ArrayList<>();

        try {
            JsonObject dataObject = new JsonObject(data.toString());
            for (int i = 0; i < (dataObject.getJsonArray(REQUESTS)).size(); i++) {
                requests.add(requestFromJsonObject((JsonObject) dataObject.getJsonArray(REQUESTS).getValue(i), originalHeaders, groupRequestHeader));
            }
        } catch (Exception ex) {
            return Result.err("Error while parsing requests payload. Cause: " + ex.getMessage());
        }

        return Result.ok(requests);
    }

    private static HttpRequest requestFromJsonObject(JsonObject requestObj, MultiMap originalHeaders, String groupRequestHeader) {
        return new HttpRequest(prepare(requestObj, originalHeaders, groupRequestHeader));
    }

    private static JsonObject prepare(JsonObject httpRequestJsonObject, MultiMap originalHeaders, String groupRequestHeader) {
        String payloadStr;
        try {
            if (httpRequestJsonObject.getJsonObject(PAYLOAD) != null) {
                payloadStr = httpRequestJsonObject.getJsonObject(PAYLOAD).encode();
            } else {
                payloadStr = null;
            }
        } catch (ClassCastException e) {
            payloadStr = httpRequestJsonObject.getString(PAYLOAD);
        }

        MultiMap headersCleared = headersCopiedCleared(originalHeaders, httpRequestJsonObject);

        JsonArray headers = httpRequestJsonObject.getJsonArray(HEADERS);
        MultiMap headersMap = multiMapFromJsonArray(headers);

        for (Map.Entry<String, String> entry : headersMap) {
            headersCleared.set(entry.getKey(), entry.getValue());
        }

        // assure to use original group headers only
        headersCleared.remove(groupRequestHeader);
        headersCleared.set(groupRequestHeader, originalHeaders.get(groupRequestHeader));

        JsonArray headersJsonArray = new JsonArray();
        for (Map.Entry<String, String> entry : headersCleared) {
            headersJsonArray.add(new JsonArray(Arrays.asList(entry.getKey(), entry.getValue())));
        }

        httpRequestJsonObject.put(HEADERS, headersJsonArray);

        if (payloadStr != null) {
            byte[] payload = payloadStr.getBytes(StandardCharsets.UTF_8);
            httpRequestJsonObject.getJsonArray(HEADERS).add(new JsonArray(Arrays.asList(CONTENT_LENGTH, "" + payload.length)));
            httpRequestJsonObject.put(PAYLOAD, payload);
        }
        return httpRequestJsonObject;
    }

    private static MultiMap headersCopiedCleared(MultiMap originalHeaders, JsonObject httpRequestJsonObject) {
        MultiMap headersCleared = new HeadersMultiMap();
        boolean copyHeaders = httpRequestJsonObject.getBoolean(COPY_ORIGINAL_HEADERS, false);
        if (copyHeaders) {
            headersCleared.addAll(originalHeaders);
            headersCleared.remove(CONTENT_LENGTH);
            headersCleared.remove(UNIQUE_ID);
            headersCleared.remove(UNIQUE_ID_DASH);
            headersCleared.remove(PACK_HEADER);
        }
        return headersCleared;
    }

    public static MultiMap multiMapFromJsonArray(JsonArray jsonArray) {
        MultiMap multiMap = new HeadersMultiMap();
        if (jsonArray == null) {
            return multiMap;
        }
        for (int i = 0; i < jsonArray.size(); i++) {
            JsonArray header = jsonArray.getJsonArray(i);
            multiMap.add(header.getString(0), header.getString(1));
        }
        return multiMap;
    }
}

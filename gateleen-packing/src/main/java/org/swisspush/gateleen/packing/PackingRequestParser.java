package org.swisspush.gateleen.packing;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.swisspush.gateleen.core.http.HttpRequest;
import org.swisspush.gateleen.core.util.Result;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class PackingRequestParser {

    private static final String HEADERS = "headers";
    private static final String PAYLOAD = "payload";
    private static final String REQUESTS = "requests";

    public static Result<List<HttpRequest>, String> parseRequests(Buffer data) {
        List<HttpRequest> requests = new ArrayList<>();

        try {
            JsonObject dataObject = new JsonObject(data.toString());
            for (int i = 0; i < (dataObject.getJsonArray(REQUESTS)).size(); i++) {
                requests.add(requestFromJsonObject((JsonObject) dataObject.getJsonArray(REQUESTS).getValue(i)));
            }
        } catch (Exception ex) {
            return Result.err("Error while parsing requests payload. Cause: " + ex.getMessage());
        }

        return Result.ok(requests);
    }

    private static HttpRequest requestFromJsonObject(JsonObject requestObj) {
        return new HttpRequest(prepare(requestObj));
    }

    private static JsonObject prepare(JsonObject httpRequestJsonObject) {
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

        JsonArray headers = httpRequestJsonObject.getJsonArray(HEADERS);
        if (headers != null) {
            httpRequestJsonObject.put(HEADERS, headers);
        } else {
            httpRequestJsonObject.put(HEADERS, new JsonArray());
        }

        if (payloadStr != null) {
            byte[] payload = payloadStr.getBytes(StandardCharsets.UTF_8);
            httpRequestJsonObject.getJsonArray(HEADERS).add(new JsonArray(Arrays.asList("content-length", "" + payload.length)));
            httpRequestJsonObject.put(PAYLOAD, payload);
        }
        return httpRequestJsonObject;
    }
}

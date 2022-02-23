package org.swisspush.gateleen.validation;

import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import io.vertx.core.*;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.impl.headers.HeadersMultiMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.http.ClientRequestCreator;
import org.swisspush.gateleen.core.util.StatusCode;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class DefaultValidationSchemaProvider implements ValidationSchemaProvider {

    private final ClientRequestCreator clientRequestCreator;
    private final Logger log = LoggerFactory.getLogger(DefaultValidationSchemaProvider.class);

    private final Map<String, SchemaEntry> cachedSchemas;
    private final HeadersMultiMap defaultRequestHeaders;

    private static final int TIMEOUT_MS = 30000;
    private static final String CONTENT_TYPE_HEADER = "Content-Type";
    private static final String CONTENT_TYPE_JSON = "application/json";
    private static final String SELF_REQUEST_HEADER = "x-self-request";

    /**
     * Constructor for {@link DefaultValidationSchemaProvider}
     *
     * @param vertx                the Vert.x instance
     * @param clientRequestCreator the {@link ClientRequestCreator} to fetch the schema
     * @param cacheCleanupInterval interval to define the cached schema cleanup
     */
    public DefaultValidationSchemaProvider(Vertx vertx, ClientRequestCreator clientRequestCreator, Duration cacheCleanupInterval) {
        this(vertx, clientRequestCreator, cacheCleanupInterval, new HashMap<>());
    }

    /**
     * Constructor for {@link DefaultValidationSchemaProvider}
     *
     * @param vertx                 the Vert.x instance
     * @param clientRequestCreator  the {@link ClientRequestCreator} to fetch the schema
     * @param cacheCleanupInterval  interval to define the cached schema cleanup
     * @param defaultRequestHeaders default request headers to add to every schema fetch request
     */
    public DefaultValidationSchemaProvider(Vertx vertx, ClientRequestCreator clientRequestCreator, Duration cacheCleanupInterval,
                                           Map<String, String> defaultRequestHeaders) {
        this.clientRequestCreator = clientRequestCreator;
        this.cachedSchemas = new HashMap<>();
        this.defaultRequestHeaders = new HeadersMultiMap();
        this.defaultRequestHeaders.addAll(defaultRequestHeaders);

        vertx.setPeriodic(cacheCleanupInterval.toMillis(), event -> cleanupCachedSchemas());
    }

    private void cleanupCachedSchemas() {
        log.debug("About to clear cached schemas");
        cachedSchemas.entrySet().removeIf(entry -> entry.getValue().expiration().isBefore(Instant.now()));
    }

    private HeadersMultiMap defaultRequestHeaders() {
        return defaultRequestHeaders;
    }

    @Override
    public Future<Optional<JsonSchema>> schemaFromLocation(SchemaLocation schemaLocation) {
        Promise<Optional<JsonSchema>> promise = Promise.promise();

        SchemaEntry schemaEntry = cachedSchemas.get(schemaLocation.schemaLocation());
        if (schemaEntry != null) {
            if (schemaEntry.expiration.isAfter(Instant.now())) {
                promise.complete(Optional.of(schemaEntry.jsonSchema()));
                return promise.future();
            } else {
                cachedSchemas.remove(schemaLocation.schemaLocation());
            }
        }

        HeadersMultiMap headers = defaultRequestHeaders();
        headers.add("Accept", "application/json");
        headers.add(SELF_REQUEST_HEADER, "true");

        clientRequestCreator.createClientRequest(
                HttpMethod.GET,
                schemaLocation.schemaLocation(),
                headers,
                TIMEOUT_MS,
                event -> {
                    log.warn("Got an error while fetching schema", event);
                    promise.complete(Optional.empty());
                }).onComplete(asyncResult -> {
            HttpClientRequest fetchSchemaRequest = asyncResult.result();
            fetchSchemaRequest.setChunked(true);
            fetchSchemaRequest.send(responseAsyncResult -> {
                HttpClientResponse cRes = responseAsyncResult.result();
                cRes.bodyHandler(data -> {
                    if (StatusCode.OK.getStatusCode() == cRes.statusCode()) {
                        String contentType = cRes.getHeader(CONTENT_TYPE_HEADER);
                        if (contentType != null && !contentType.contains(CONTENT_TYPE_JSON)) {
                            log.warn("Content-Type {} is not supported", contentType);
                            promise.complete(Optional.empty());
                            return;
                        }
                        promise.complete(parseSchema(schemaLocation, data));
                    } else {
                        StatusCode statusCode = StatusCode.fromCode(cRes.statusCode());
                        if (statusCode != null) {
                            log.warn("Got status code {} while fetching schema", cRes.statusCode());
                        } else {
                            log.warn("Got unknown status code while fetching schema");
                        }
                        promise.complete(Optional.empty());
                    }
                });
            });
        });
        return promise.future();
    }

    private Optional<JsonSchema> parseSchema(SchemaLocation schemaLocation, Buffer data) {
        JsonSchema schema = null;
        try {
            schema = JsonSchemaFactory.getInstance().getSchema(data.toString());
            if (schemaLocation.keepInMemory() != null) {
                cachedSchemas.put(schemaLocation.schemaLocation(), new SchemaEntry(schema, Instant.now().plusSeconds(schemaLocation.keepInMemory())));
            }
        } catch (RuntimeException e) {
            log.warn("Error while parsing schema", e);
        }

        return Optional.ofNullable(schema);
    }

    private static class SchemaEntry {

        private final JsonSchema jsonSchema;
        private final Instant expiration;

        public SchemaEntry(JsonSchema jsonSchema, Instant expiration) {
            this.jsonSchema = jsonSchema;
            this.expiration = expiration;
        }

        public JsonSchema jsonSchema() {
            return jsonSchema;
        }

        public Instant expiration() {
            return expiration;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            SchemaEntry that = (SchemaEntry) o;

            if (!jsonSchema.equals(that.jsonSchema)) return false;
            return expiration.equals(that.expiration);
        }

        @Override
        public int hashCode() {
            int result = jsonSchema.hashCode();
            result = 31 * result + expiration.hashCode();
            return result;
        }
    }
}

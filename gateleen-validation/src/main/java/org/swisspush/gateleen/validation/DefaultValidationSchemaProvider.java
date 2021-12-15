package org.swisspush.gateleen.validation;

import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.util.StatusCode;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class DefaultValidationSchemaProvider implements ValidationSchemaProvider {

    private final HttpClient httpClient;
    private final Logger log = LoggerFactory.getLogger(DefaultValidationSchemaProvider.class);

    private final Map<String, SchemaEntry> cachedSchemas;

    private static final int TIMEOUT_MS = 30000;
    private static final String CONTENT_TYPE_HEADER = "Content-Type";
    private static final String CONTENT_TYPE_JSON = "application/json";
    private static final String SELF_REQUEST_HEADER = "x-self-request";

    /**
     * Constructor for {@link DefaultValidationSchemaProvider}
     *
     * @param vertx the Vert.x instance
     * @param httpClient the {@link HttpClient} to fetch the schema
     * @param cacheCleanupInterval interval to define the cached schema cleanup
     */
    public DefaultValidationSchemaProvider(Vertx vertx, HttpClient httpClient, Duration cacheCleanupInterval) {
        this.httpClient = httpClient;
        this.cachedSchemas = new HashMap<>();

        vertx.setPeriodic(cacheCleanupInterval.toMillis(), event -> cleanupCachedSchemas());
    }

    private void cleanupCachedSchemas() {
        log.debug("About to clear cached schemas");
        cachedSchemas.entrySet().removeIf(entry -> entry.getValue().expiration().isBefore(Instant.now()));
    }

    @Override
    public Future<Optional<JsonSchema>> schemaFromLocation(SchemaLocation schemaLocation) {
        Future<Optional<JsonSchema>> future = Future.future();

        SchemaEntry schemaEntry = cachedSchemas.get(schemaLocation.schemaLocation());
        if (schemaEntry != null) {
            if (schemaEntry.expiration.isAfter(Instant.now())) {
                future.complete(Optional.of(schemaEntry.jsonSchema()));
                return future;
            } else {
                cachedSchemas.remove(schemaLocation.schemaLocation());
            }
        }

        final HttpClientRequest cReq = httpClient.request(HttpMethod.GET, schemaLocation.schemaLocation(), cRes -> {
            cRes.bodyHandler(data -> {
                if (StatusCode.OK.getStatusCode() == cRes.statusCode()) {
                    String contentType = cRes.getHeader(CONTENT_TYPE_HEADER);
                    if (contentType != null && !contentType.contains(CONTENT_TYPE_JSON)) {
                        log.warn("Content-Type {} is not supported", contentType);
                        future.complete(Optional.empty());
                        return;
                    }
                    future.complete(parseSchema(schemaLocation, data));
                } else {
                    StatusCode statusCode = StatusCode.fromCode(cRes.statusCode());
                    if (statusCode != null) {
                        log.warn("Got status code {} while fetching schema", cRes.statusCode());
                    } else {
                        log.warn("Got unknown status code while fetching schema");
                    }
                    future.complete(Optional.empty());
                }
            });

            cRes.exceptionHandler(event -> {
                log.warn("Got an error while fetching schema", event);
                future.complete(Optional.empty());
            });

        });

        cReq.setTimeout(TIMEOUT_MS);
        cReq.headers().set("Accept", "application/json");
        cReq.headers().set(SELF_REQUEST_HEADER, "true");
        cReq.setChunked(true);
        cReq.end();

        return future;
    }

    private Optional<JsonSchema> parseSchema(SchemaLocation schemaLocation, Buffer data) {
        JsonSchema schema = null;
        try {
            schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V4).getSchema(data.toString());
            if (schemaLocation.keepInMemory() != null) {
                cachedSchemas.put(schemaLocation.schemaLocation(), new SchemaEntry(schema, Instant.now().plusSeconds(schemaLocation.keepInMemory())));
            }
        } catch (Exception e) {
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

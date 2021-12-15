package org.swisspush.gateleen.validation;

import com.networknt.schema.JsonSchema;
import io.vertx.core.Future;

import java.util.Optional;

/**
 * Provides {@link JsonSchema} fetched from an external location. Optionally caches the {@link JsonSchema} to reduce load
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public interface ValidationSchemaProvider {

    Future<Optional<JsonSchema>> schemaFromLocation(SchemaLocation schemaLocation);
}

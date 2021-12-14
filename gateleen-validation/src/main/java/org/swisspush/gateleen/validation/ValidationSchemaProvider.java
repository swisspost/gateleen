package org.swisspush.gateleen.validation;

import com.networknt.schema.JsonSchema;
import io.vertx.core.Future;

import java.util.Optional;

public interface ValidationSchemaProvider {

    Future<Optional<JsonSchema>> schemaFromLocation(String schemaLocation);
}

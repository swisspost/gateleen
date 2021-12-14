package org.swisspush.gateleen.validation;

import com.networknt.schema.JsonSchema;
import io.vertx.core.Future;

import java.util.Optional;

public class DefaultValidationSchemaProvider implements ValidationSchemaProvider {

    @Override
    public Future<Optional<JsonSchema>> schemaFromLocation(String schemaLocation) {
        return Future.succeededFuture(Optional.empty());
    }
}

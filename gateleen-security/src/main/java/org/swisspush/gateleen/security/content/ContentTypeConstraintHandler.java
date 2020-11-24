package org.swisspush.gateleen.security.content;

import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.configuration.ConfigurationResourceConsumer;
import org.swisspush.gateleen.core.configuration.ConfigurationResourceManager;
import org.swisspush.gateleen.core.util.ResponseStatusCodeLogUtil;
import org.swisspush.gateleen.core.util.StatusCode;
import org.swisspush.gateleen.security.PatternHolder;

import java.util.ArrayList;
import java.util.List;

/**
 * Handler class for handling Content-Type constraints.
 *
 * The main responsibilities for this handler are:
 * <ul>
 * <li>Manage Content-Type constraint configuration resource</li>
 * <li>Grant requests having a Content-Type value matching the always allowed content-types (defaults)</li>
 * <li>Refuse requests not matching the default Content-Types or any configured Content-Type</li>
 * </ul>
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class ContentTypeConstraintHandler extends ConfigurationResourceConsumer {

    private final Logger log = LoggerFactory.getLogger(ContentTypeConstraintHandler.class);
    private final ContentTypeConstraintRepository repository;
    private boolean initialized = false;
    private final List<PatternHolder> alwaysAllowedContentTypes;

    private static final String CONTENT_TYPE_HEADER = "Content-Type";

    public ContentTypeConstraintHandler(ConfigurationResourceManager configurationResourceManager,
                                        ContentTypeConstraintRepository repository, String configResourceUri,
                                        List<PatternHolder> alwaysAllowedContentTypes) {
        super(configurationResourceManager, configResourceUri, "gateleen_security_contenttype_constraint_schema");
        this.repository = repository;
        this.alwaysAllowedContentTypes = alwaysAllowedContentTypes;
    }

    public ContentTypeConstraintHandler(ConfigurationResourceManager configurationResourceManager,
                                        ContentTypeConstraintRepository repository, String configResourceUri) {
        this(configurationResourceManager, repository, configResourceUri, new ArrayList<>());
    }

    public boolean isInitialized() {
        return initialized;
    }

    public Future<Void> initialize() {
        Future<Void> future = Future.future();
        configurationResourceManager().getRegisteredResource(configResourceUri()).setHandler(event -> {
            if (event.succeeded() && event.result().isPresent()) {
                initializeConstraintConfiguration(event.result().get());
                future.complete();
            } else {
                log.warn("No (valid) Content-Type constraint configuration resource with uri '{}' found. Unable to setup " +
                        "Content-Type constraint handler correctly", configResourceUri());
                future.complete();
            }
        });
        return future;
    }

    public boolean handle(final HttpServerRequest request) {
        String contentTypeValue = request.headers().get(CONTENT_TYPE_HEADER);
        if(contentTypeValue == null){
            return false;
        }

        /*
         * ignore charset information like ';charset=UTF-8'
         */
        contentTypeValue = StringUtils.substringBefore(contentTypeValue, ";");

        for (PatternHolder allowedContentType : alwaysAllowedContentTypes) {
            if(allowedContentType.getPattern().matcher(contentTypeValue).matches()){
                return false;
            }
        }

        String finalContentTypeValue = contentTypeValue;
        return repository.findMatchingContentTypeConstraint(request.uri()).map(contentTypeConstraint -> {
            for (PatternHolder allowedTypePatternHolder : contentTypeConstraint.allowedTypes()) {
                if (allowedTypePatternHolder.getPattern().matcher(finalContentTypeValue).matches()) {
                    return false;
                }
            }
            respondUnsupportedMediaType(request);
            return true;
        }).orElseGet(() -> {
            respondUnsupportedMediaType(request);
            return true;
        });
    }

    @Override
    public void resourceChanged(String resourceUri, Buffer resource) {
        if (configResourceUri() != null && configResourceUri().equals(resourceUri)) {
            log.info("Content-Type constraint configuration resource " + resourceUri + " was updated. Going to initialize with new configuration");
            initializeConstraintConfiguration(resource);
        }
    }

    @Override
    public void resourceRemoved(String resourceUri) {
        if (configResourceUri() != null && configResourceUri().equals(resourceUri)) {
            log.info("Content-Type constraint configuration resource " + resourceUri + " was removed.");
            repository.clearConstraints();
            initialized = false;
        }
    }

    private void initializeConstraintConfiguration(Buffer configuration) {
        final List<ContentTypeConstraint> constraints = ContentTypeConstraintFactory.create(configuration);
        repository.setConstraints(constraints);
        initialized = true;
    }

    private void respondUnsupportedMediaType(final HttpServerRequest request) {
        ResponseStatusCodeLogUtil.info(request, StatusCode.UNSUPPORTED_MEDIA_TYPE, ContentTypeConstraintHandler.class);
        request.response().setStatusCode(StatusCode.UNSUPPORTED_MEDIA_TYPE.getStatusCode());
        request.response().setStatusMessage(StatusCode.UNSUPPORTED_MEDIA_TYPE.getStatusMessage());
        request.response().end();
    }
}

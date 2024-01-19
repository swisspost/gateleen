package org.swisspush.gateleen.core.configuration;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.http.RequestLoggerFactory;
import org.swisspush.gateleen.core.logging.LoggableResource;
import org.swisspush.gateleen.core.logging.RequestLogger;
import org.swisspush.gateleen.core.storage.ResourceStorage;
import org.swisspush.gateleen.core.util.ResponseStatusCodeLogUtil;
import org.swisspush.gateleen.core.util.StatusCode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Manage the modifications of configuration resources and notify observers
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class ConfigurationResourceManager implements LoggableResource {

    private Logger log = LoggerFactory.getLogger(ConfigurationResourceManager.class);

    private Vertx vertx;
    private ResourceStorage storage;
    private Map<String, String> registeredResources;
    private Map<String, List<ConfigurationResourceObserver>> observers;
    private ConfigurationResourceValidator configurationResourceValidator;
    private boolean logConfigurationResourceChanges = false;

    public static final String CONFIG_RESOURCE_CHANGED_ADDRESS = "gateleen.configuration-resource-changed";
    private static final String MESSAGE_REQUEST_URI = "requestUri";
    private static final String MESSAGE_RESOURCE_TYPE = "type";

    public ConfigurationResourceManager(Vertx vertx, final ResourceStorage storage) {
        this.vertx = vertx;
        this.storage = storage;

        this.configurationResourceValidator = new ConfigurationResourceValidator(vertx);

        log.info("Register on vertx event bus to receive configuration resource updates");
        vertx.eventBus().consumer(CONFIG_RESOURCE_CHANGED_ADDRESS, (Handler<Message<JsonObject>>) event -> {

            String requestUri = event.body().getString(MESSAGE_REQUEST_URI);
            ConfigurationResourceChangeType type = ConfigurationResourceChangeType.fromString(event.body().getString(MESSAGE_RESOURCE_TYPE));

            if (requestUri != null && type != null) {
                if (ConfigurationResourceChangeType.CHANGE == type) {
                    notifyObserverAboutResourceChange(requestUri, null);
                } else if (ConfigurationResourceChangeType.REMOVE == type) {
                    notifyObserversAboutRemovedResource(requestUri);
                } else {
                    log.warn("Not supported configuration resource change type '{}' received. Doing nothing with it", type);
                }
            } else {
                log.warn("Invalid configuration resource change message received. Don't notify anybody!");
            }
        });
    }

    /**
     * Call this method to get the validated registered resource immediately. Otherwise you would have to wait
     * for a change on the corresponding resource.
     *
     * @param resourceUri the uri of the registered resource
     */
    public Future<Optional<Buffer>> getRegisteredResource(String resourceUri){
        return getValidatedRegisteredResource(resourceUri);
    }

    public void registerResource(String resourceUri, String resourceSchema) {
        getRegisteredResources().put(resourceUri, resourceSchema);
    }

    public void registerObserver(ConfigurationResourceObserver observer, String resourceUri) {
        if(!getRegisteredResources().containsKey(resourceUri)){
            log.warn("No registered resource with uri {} found", resourceUri);
        }
        List<ConfigurationResourceObserver> observersByResourceUri = getObserversByResourceUri(resourceUri);
        observersByResourceUri.add(observer);
        observers.put(resourceUri, observersByResourceUri);

        notifyObserverAboutResourceChange(resourceUri, observer);
    }

    @Override
    public void enableResourceLogging(boolean resourceLoggingEnabled) {
        this.logConfigurationResourceChanges = resourceLoggingEnabled;
    }

    public boolean handleConfigurationResource(final HttpServerRequest request) {
        final Logger requestLog = RequestLoggerFactory.getLogger(ConfigurationResourceManager.class, request);

        if(null == getRegisteredResources().get(request.uri())){
            return false;
        }

        String resourceUri = request.uri();
        String resourceSchema = getRegisteredResources().get(request.uri());

        if(HttpMethod.PUT == request.method()) {
            requestLog.info("Refresh resource {}", resourceUri);
            request.bodyHandler(buffer -> configurationResourceValidator.validateConfigurationResource(buffer, resourceSchema, event -> {
                if (event.failed() || (event.succeeded() && !event.result().isSuccess())) {
                    requestLog.error("Could not parse configuration resource for uri '{}' message: {}", resourceUri, event.result().getMessage());
                    request.response().setStatusCode(StatusCode.BAD_REQUEST.getStatusCode());
                    request.response().setStatusMessage(StatusCode.BAD_REQUEST.getStatusMessage() + " " + event.result().getMessage());
                    ResponseStatusCodeLogUtil.info(request, StatusCode.BAD_REQUEST, ConfigurationResourceManager.class);
                    if (event.result().getValidationDetails() != null) {
                        request.response().headers().add("content-type", "application/json");
                        request.response().end(event.result().getValidationDetails().encode());
                    } else {
                        request.response().end(event.result().getMessage());
                    }
                } else {
                    storage.put(resourceUri, buffer, status -> {
                        if (status == StatusCode.OK.getStatusCode()) {
                            if(logConfigurationResourceChanges){
                                RequestLogger.logRequest(vertx.eventBus(), request, status, buffer);
                            }
                            JsonObject object = new JsonObject();
                            object.put("requestUri", resourceUri);
                            object.put("type", ConfigurationResourceChangeType.CHANGE);
                            vertx.eventBus().publish(CONFIG_RESOURCE_CHANGED_ADDRESS, object);
                        } else {
                            request.response().setStatusCode(status);
                        }
                        ResponseStatusCodeLogUtil.info(request, StatusCode.fromCode(status), ConfigurationResourceManager.class);
                        request.response().end();
                    });
                }
            }));
            return true;
        }

        if(HttpMethod.DELETE == request.method()) {
            requestLog.info("Remove resource {}", resourceUri);
            storage.delete(resourceUri, status -> {
                if (status == StatusCode.OK.getStatusCode()) {
                    JsonObject object = new JsonObject();
                    object.put("requestUri", resourceUri);
                    object.put("type", ConfigurationResourceChangeType.REMOVE);
                    vertx.eventBus().publish(CONFIG_RESOURCE_CHANGED_ADDRESS, object);
                } else {
                    request.response().setStatusCode(status);
                }
                request.response().end();
            });
            return true;
        }

        return false;
    }

    private Map<String, String> getRegisteredResources() {
        if (registeredResources == null) {
            registeredResources = new HashMap<>();
        }
        return registeredResources;
    }

    public Map<String, List<ConfigurationResourceObserver>> getObservers() {
        if (observers == null) {
            observers = new HashMap<>();
        }
        return observers;
    }

    private Future<Optional<Buffer>> getValidatedRegisteredResource(String resourceUri){
        Promise<Optional<Buffer>> promise = Promise.promise();
        String resourceSchema = getRegisteredResources().get(resourceUri);
        storage.get(resourceUri, buffer -> {
            if (buffer != null) {
                configurationResourceValidator.validateConfigurationResource(buffer, resourceSchema, event -> {
                    if (event.succeeded()) {
                        if (event.result().isSuccess()) {
                            promise.complete(Optional.of(buffer));
                        } else {
                            promise.fail("Failure during validation of resource " + resourceUri + ". Message: " + event.result().getMessage());
                        }
                    } else {
                        promise.fail("Failure during validation of resource " + resourceUri + ". Message: " + event.cause());
                    }
                });
            } else {
                promise.complete(Optional.empty());
            }
        });

        return promise.future();
    }

    private void notifyObserversAboutRemovedResource(String requestUri) {
        log.debug("About to notify observers that resource {} has been removed", requestUri);
        List<ConfigurationResourceObserver> observersByResourceUri = getObserversByResourceUri(requestUri);
        for (ConfigurationResourceObserver observer : observersByResourceUri) {
            observer.resourceRemoved(requestUri);
        }
    }

    private void notifyObserverAboutResourceChange(String requestUri, ConfigurationResourceObserver observer) {
        getValidatedRegisteredResource(requestUri).onComplete(event -> {
            if(event.failed()){
                log.warn(event.cause().getMessage());
            } else if(event.result().isPresent()){
                if(observer != null) {
                    observer.resourceChanged(requestUri, event.result().get());
                } else {
                    List<ConfigurationResourceObserver> observersByResourceUri = getObserversByResourceUri(requestUri);
                    for (ConfigurationResourceObserver configurationResourceObserver : observersByResourceUri) {
                        configurationResourceObserver.resourceChanged(requestUri, event.result().get());
                    }
                }
            } else {
                log.warn("Could not get URL '{}'.", (requestUri == null ? "<null>" : requestUri));
            }
        });
    }

    private List<ConfigurationResourceObserver> getObserversByResourceUri(String resourceUri) {
        List<ConfigurationResourceObserver> resourceObservers = getObservers().get(resourceUri);
        if (resourceObservers == null) {
            resourceObservers = new ArrayList<>();
        }
        return resourceObservers;
    }

    private enum ConfigurationResourceChangeType {
        CHANGE, REMOVE;

        public static ConfigurationResourceChangeType fromString(String typeString) {
            for (ConfigurationResourceChangeType type : values()) {
                if (type.name().equalsIgnoreCase(typeString)) {
                    return type;
                }
            }
            return null;
        }
    }
}

package org.swisspush.gateleen.core.configuration;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.http.RequestLoggerFactory;
import org.swisspush.gateleen.core.storage.ResourceStorage;
import org.swisspush.gateleen.core.util.StatusCode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manage the modifications of configuration resources and notify observers
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class ConfigurationResourceManager {

    private Logger log = LoggerFactory.getLogger(ConfigurationResourceManager.class);

    private Vertx vertx;
    private ResourceStorage storage;
    private Map<String, String> registeredResources;
    private Map<String, List<ConfigurationResourceObserver>> observers;
    private ConfigurationResourceValidator configurationResourceValidator;

    public static final String CONFIG_RESOURCE_CHANGED_ADDRESS = "gateleen.configuration-resource-changed";
    private static final String MESSAGE_REQUEST_URI = "requestUri";
    private static final String MESSAGE_RESOURCE_TYPE = "type";

    public ConfigurationResourceManager(Vertx vertx, final ResourceStorage storage) {
        this.vertx = vertx;
        this.storage = storage;

        this.configurationResourceValidator = new ConfigurationResourceValidator(vertx);

        log.info("Register on vertx event bus to receive configuration resource updates");
        vertx.eventBus().localConsumer(CONFIG_RESOURCE_CHANGED_ADDRESS, (Handler<Message<JsonObject>>) event -> {

            String requestUri = event.body().getString(MESSAGE_REQUEST_URI);
            ConfigurationResourceChangeType type = ConfigurationResourceChangeType.fromString(event.body().getString(MESSAGE_RESOURCE_TYPE));

            if (requestUri != null && type != null) {
                if (ConfigurationResourceChangeType.CHANGE == type) {
                    notifyObserversAboutResourceChange(requestUri);
                } else if (ConfigurationResourceChangeType.REMOVE == type) {
                    notifyObserversAboutRemovedResource(requestUri);
                } else {
                    log.warn("Not supported configuration resource change type '" + type + "' received. Doing nothing with it");
                }
            } else {
                log.warn("Invalid configuration resource change message received. Don't notify anybody!");
            }
        });
    }

    public void registerResource(String resourceUri, String resourceSchema) {
        getRegisteredResources().put(resourceUri, resourceSchema);
    }

    public void registerObserver(ConfigurationResourceObserver observer, String resourceUri) {
        if(!getRegisteredResources().containsKey(resourceUri)){
            log.warn("No registered resource with uri " + resourceUri + " found");
        }
        List<ConfigurationResourceObserver> observersByResourceUri = getObserversByResourceUri(resourceUri);
        observersByResourceUri.add(observer);
        observers.put(resourceUri, observersByResourceUri);

        notifyObserverAboutResourceChange(resourceUri, observer);
    }

    public boolean handleConfigurationResource(final HttpServerRequest request) {
        final Logger requestLog = RequestLoggerFactory.getLogger(ConfigurationResourceManager.class, request);

        if(null == getRegisteredResources().get(request.uri())){
            return false;
        }

        String resourceUri = request.uri();
        String resourceSchema = getRegisteredResources().get(request.uri());

        if(HttpMethod.PUT == request.method()) {
            requestLog.info("Refresh resource " + resourceUri);
            request.bodyHandler(buffer -> {
                configurationResourceValidator.validateConfigurationResource(buffer, resourceSchema, event -> {
                    if (event.failed() || (event.succeeded() && !event.result().isSuccess())) {
                        requestLog.error("Could not parse configuration resource for uri '" + resourceUri + "' message: " + event.result().getMessage());
                        request.response().setStatusCode(StatusCode.BAD_REQUEST.getStatusCode());
                        request.response().setStatusMessage(StatusCode.BAD_REQUEST.getStatusMessage() + " " + event.result().getMessage());
                        if (event.result().getValidationDetails() != null) {
                            request.response().headers().add("content-type", "application/json");
                            request.response().end(event.result().getValidationDetails().encode());
                        } else {
                            request.response().end(event.result().getMessage());
                        }
                    } else {
                        storage.put(resourceUri, buffer, status -> {
                            if (status == StatusCode.OK.getStatusCode()) {
                                JsonObject object = new JsonObject();
                                object.put("requestUri", resourceUri);
                                object.put("type", ConfigurationResourceChangeType.CHANGE);
                                vertx.eventBus().publish(CONFIG_RESOURCE_CHANGED_ADDRESS, object);
                            } else {
                                request.response().setStatusCode(status);
                            }
                            request.response().end();
                        });
                    }
                });
            });
            return true;
        }

        if(HttpMethod.DELETE == request.method()) {
            requestLog.info("Remove resource " + resourceUri);
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

    private void notifyObserversAboutRemovedResource(String requestUri) {
        log.info("About to notify observers that resource " + requestUri + " has been removed");
        List<ConfigurationResourceObserver> observersByResourceUri = getObserversByResourceUri(requestUri);
        for (ConfigurationResourceObserver observer : observersByResourceUri) {
            observer.resourceRemoved(requestUri);
        }
    }

    private void notifyObserversAboutResourceChange(String requestUri) {
        String resourceSchema = getRegisteredResources().get(requestUri);
        storage.get(requestUri, buffer -> {
            if (buffer != null) {
                configurationResourceValidator.validateConfigurationResource(buffer, resourceSchema, event -> {
                    if (event.succeeded()) {
                        if (event.result().isSuccess()) {
                            notifyObserversAboutResourceChange(requestUri, buffer.toString());
                        } else {
                            log.warn("Failure during validation of resource " + requestUri + ". Message: " + event.result().getMessage());
                        }
                    } else {
                        log.warn("Failure during validation of resource " + requestUri + ". Message: " + event.cause());
                    }
                });
            } else {
                log.warn("Could not get URL '" + (requestUri == null ? "<null>" : requestUri) + "'.");
            }
        });
    }

    private void notifyObserverAboutResourceChange(String requestUri, ConfigurationResourceObserver observer) {
        String resourceSchema = getRegisteredResources().get(requestUri);
        storage.get(requestUri, buffer -> {
            if (buffer != null) {
                configurationResourceValidator.validateConfigurationResource(buffer, resourceSchema, event -> {
                    if (event.succeeded()) {
                        if (event.result().isSuccess()) {
                            observer.resourceChanged(requestUri, buffer.toString());
                        } else {
                            log.warn("Failure during validation of resource " + requestUri + ". Message: " + event.result().getMessage());
                        }
                    } else {
                        log.warn("Failure during validation of resource " + requestUri + ". Message: " + event.cause());
                    }
                });
            } else {
                log.warn("Could not get URL '" + (requestUri == null ? "<null>" : requestUri) + "'.");
            }
        });
    }

    private void notifyObserversAboutResourceChange(String resourceUri, String resource) {
        List<ConfigurationResourceObserver> observersByResourceUri = getObserversByResourceUri(resourceUri);
        for (ConfigurationResourceObserver observer : observersByResourceUri) {
            observer.resourceChanged(resourceUri, resource);
        }
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

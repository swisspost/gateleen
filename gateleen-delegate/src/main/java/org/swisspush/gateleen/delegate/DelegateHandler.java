package org.swisspush.gateleen.delegate;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.storage.ResourceStorage;
import org.swisspush.gateleen.core.util.ResourcesUtils;
import org.swisspush.gateleen.core.util.StatusCode;
import org.swisspush.gateleen.monitoring.MonitoringHandler;
import org.swisspush.gateleen.validation.ValidationException;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Allows to create delegates. E.g. <br>
 * Definition of a delegate is stored in a resource called <code>definition</code> within the specified
 * delegate.<br>
 * <code>PUT /gateleen/server/delegate/v1/delegates/user-zip-copy/definition</code>
 * <pre>
    {
        "methods": [ "PUT", "DELETE" ],
        "pattern": "([^/]*)/(.*)",
        "requests": [
            {
                 "headers" : [],
                 "uri": "/gateleen/server/copy",
                 "method": "POST",
                 "payload": {
                    "source": "/gateleen/$1?expand=100&amp;zip=true",
                    "destination": "/gateleen/zips/users/$1.zip"
                }
            }
        ]
    }

 * </pre>
 * To trigger an execution, it suffice to perform a PUT request on the virtual collection <code>execution</code> within
 * the specified delegate.<br>
 * <code>PUT /gateleen/server/delegate/v1/delegates/user-zip-copy/execution/&lt;...&gt;</code>
 *
 * @author https://github.com/ljucam [Mario Ljuca]
 */
public class DelegateHandler {
    private static final String DEFINITION_RESOURCE = "definition";
    private static final String EXECUTION_RESOURCE = "execution";
    private static final String SAVE_DELEGATE_ADDRESS = "gateleen.delegate-insert";
    private static final String REMOVE_DELEGATE_ADDRESS = "gateleen.delegate-remove";
    private static final Logger LOG = LoggerFactory.getLogger(DelegateHandler.class);
    private static final int NAME_GROUP_INDEX = 2;
    private static final int MESSAGE_NAME = 0;
    private static final int MESSAGE_URL = 1;

    private final Vertx vertx;
    private final HttpClient selfClient;
    private final ResourceStorage storage;
    private final String delegatesUri;
    private final DelegateFactory delegateFactory;
    private final Pattern delegateNamePattern;
    private final Map<String, Delegate> delegateMap;

    private boolean initialized;


    /**
     * Creates a new instance of the DelegateHandler.
     *
     * @param vertx vertx
     * @param selfClient selfClient
     * @param storage storage
     * @param monitoringHandler monitoringHandler
     * @param delegatesUri delegate root
     * @param properties properties
     */
    public DelegateHandler(final Vertx vertx, final HttpClient selfClient, final ResourceStorage storage, final MonitoringHandler monitoringHandler, final String delegatesUri, final Map<String, Object> properties) {
        this.vertx = vertx;
        this.selfClient = selfClient;
        this.storage = storage;
        this.delegatesUri = delegatesUri;

        String delegatesSchema = ResourcesUtils.loadResource("gateleen_delegate_schema_delegates", true);
        this.delegateFactory = new DelegateFactory(monitoringHandler,selfClient,properties,delegatesSchema);

        delegateNamePattern = Pattern.compile("(" + delegatesUri + ")(.*)(/" + DEFINITION_RESOURCE + "|/"+ EXECUTION_RESOURCE + "/(.*)" + "|/)");
        delegateMap = new HashMap<>();
        initialized = false;
    }

    /**
     * This method initializes the Handler. It should be called
     * after the router is successfully established.
     */
    public void init() {
        if ( ! initialized ) {
            registerDelegateRegistrationHandler();
            loadStoredDelegates();
        }
        initialized = true;
    }

    /**
     * Loads the stored delegates during the
     * start sequence of the server.
     */
    private void loadStoredDelegates() {
        HttpClientRequest selfRequest = selfClient.request(HttpMethod.GET, delegatesUri + "?expand=2", response -> {
            // response OK
            if (response.statusCode() == StatusCode.OK.getStatusCode()) {
                response.bodyHandler(event -> {

                    // clear all delegates
                    delegateMap.clear();

                    /*
                     * the body of our response contains
                     * every delegate in the storage.
                     */

                    JsonObject responseObject = new JsonObject(event.toString());

                    if (responseObject.getValue("delegates") instanceof JsonObject) {
                        JsonObject delegates = responseObject.getJsonObject("delegates");

                        for (String delegateName : delegates.fieldNames()) {
                            LOG.info("Loading delegate: {}", delegateName );

                            JsonObject storageObject = delegates.getJsonObject(delegateName);
                            registerDelegate(Buffer.buffer(storageObject.toString()), delegateName);
                        }
                    } else {
                        LOG.info("Currently are no delegates stored!");
                    }
                });
            } else if (response.statusCode() == StatusCode.NOT_FOUND.getStatusCode()) {
                LOG.debug("No delegates previously stored");
            } else {
                LOG.error("Delegates could not be loaded.");
            }
        });

        selfRequest.setTimeout(120000);
        selfRequest.end();
    }

    /**
     * Registers all needed handlers for the
     * delegate registration / unregistration.
     */
    private void registerDelegateRegistrationHandler() {
        if ( LOG.isTraceEnabled()) {
            LOG.trace("registerDelegateRegistrationHandler");
        }

        // Receive delegate insert notifications
        vertx.eventBus().consumer(SAVE_DELEGATE_ADDRESS, new Handler<Message<String>>() {
            @Override
            public void handle(final Message<String> delegateEvent) {
                final String[] messages = delegateEvent.body().split(";");

                if ( messages != null ) {
                    storage.get(messages[MESSAGE_URL], buffer -> {
                        if (buffer != null) {
                            registerDelegate(buffer, messages[MESSAGE_NAME]);
                        } else {
                            LOG.warn("Could not get URL '" + messages[MESSAGE_URL] + "' (getting delegate).");
                        }
                    });
                }
                else {
                    LOG.warn("Could not get Delegate, empty delegateEvent.");
                }
            }
        });

        // Receive delegate remove notifications
        vertx.eventBus().consumer(REMOVE_DELEGATE_ADDRESS, new Handler<Message<String>>() {
            @Override
            public void handle(final Message<String> delegateName) {
                unregisterDelegate(delegateName.body());
            }
        });
    }

    /**
     * Removes a Delegate.
     *
     * @param delegateName name of delegate
     */
    private void unregisterDelegate(String delegateName) {
        if ( LOG.isTraceEnabled() ) {
            LOG.trace("unregisterDelegate: {}", delegateName);
        }

        delegateMap.remove(delegateName);
    }

    /**
     * Registers a Delegate.
     *
     * @param buffer data of delegate
     * @param delegateName name of delegate
     */
    private void registerDelegate(final Buffer buffer, final String delegateName) {
        if ( LOG.isTraceEnabled() ) {
            LOG.trace("registerDelegate: {}", delegateName);
        }

        try {
            Delegate delegate = delegateFactory.parseDelegate(delegateName, buffer);
            delegateMap.put(delegateName, delegate);
        }
        catch(ValidationException validationException) {
            LOG.error("Could not parse delegate: {}", validationException.toString());
        }
    }

    /**
     * Handles the incoming delegate registration.
     *
     * @param request original request
     */
    private void handleDelegateRegistration(final HttpServerRequest request) {
        if ( LOG.isTraceEnabled() ) {
            LOG.trace("handleDelegateRegistration: {}", request.uri());
        }

        request.bodyHandler(buffer -> {
            final String delegateName = getDelegateName(request.uri());

            // check if everything is fine
            try {
                delegateFactory.parseDelegate(delegateName, buffer);
            }
            catch(ValidationException validationException) {
                LOG.warn("Could not parse delegate {}: {}", delegateName, validationException.toString());
                request.response().setStatusCode(StatusCode.BAD_REQUEST.getStatusCode());
                request.response().setStatusMessage(StatusCode.BAD_REQUEST.getStatusMessage() + " " + validationException.getMessage());
                if(validationException.getValidationDetails() != null){
                    request.response().headers().add("Content-Type", "application/json");
                    request.response().end(validationException.getValidationDetails().encode());
                } else {
                    request.response().end(validationException.getMessage());
                }
                return;
            }

            // if everything is fine, put it to the storage
            storage.put(request.uri(),request.headers(), buffer, status -> {
                if (status == StatusCode.OK.getStatusCode() ) {
                    vertx.eventBus().publish(SAVE_DELEGATE_ADDRESS, delegateName + ";" +request.uri());
                } else {
                    request.response().setStatusCode(status);
                }
                request.response().end();
            });
        });
    }

    /**
     * Handles the incoming delegate unregistration.
     *
     * @param request original request
     */
    private void handleDelegateUnregistration(final HttpServerRequest request) {
        if ( LOG.isTraceEnabled() ) {
            LOG.trace("handleDelegateUnregistration: {}", request.uri());
        }

        String delegateName = getDelegateName(request.uri());
        storage.delete(delegatesUri + delegateName, status -> {
            vertx.eventBus().publish(REMOVE_DELEGATE_ADDRESS, delegateName);
            request.response().end();
        });
    }

    /**
     *
     * Tries to extract the name of the delegate out
     * of the incoming uri.
     *
     * @param uri original request uri
     * @return the name of the delegate or null if nothing matches
     */
    private String getDelegateName(final String uri) {
        /*
            URI could be:
                >  /gateleen/server/delegate/v1/delegates/user-zip-copy/definition
                >  /gateleen/server/delegate/v1/delegates/user-zip-copy/
                >  /gateleen/server/delegate/v1/delegates/user-zip-copy/execution
         */
        Matcher nameMatcher = delegateNamePattern.matcher(uri);

        if ( nameMatcher.matches() ) {
            return nameMatcher.group(NAME_GROUP_INDEX);
        }

        return null;
    }


    /**
     * Checks if the DelegateHandler is responsible for this request.
     * If so it processes the request and returns true, otherwise
     * it returns false.
     *
     * @param request original request
     * @return true if processed, false otherwise
     */
    public boolean handle(final HttpServerRequest request) {
        final String delegateName = getDelegateName(request.uri());
        if (delegateName != null){

            // Registration
            if(request.method() == HttpMethod.PUT && request.uri().endsWith(DEFINITION_RESOURCE)) {
                LOG.debug("registering delegate");
                handleDelegateRegistration(request);
                return true;
            }

            // is the delegate registered?
            if ( delegateMap.containsKey(delegateName) ) {

                // Execution
                if (request.uri().contains(EXECUTION_RESOURCE)) {
                    LOG.debug("executing delegate");
                    handleDelegateExecution(request);
                    return true;
                }

                // Unregistration?
                if (request.method() == HttpMethod.DELETE) {
                    LOG.debug("unregister delegate");
                    handleDelegateUnregistration(request);
                    return true;
                }
            }
            else {
                LOG.warn("No delegate with the name [{}] registered. DelegateHandler will not process the given request!", delegateName);
            }
        }

        // nothing to process
        return false;
    }

    /**
     * Handles the execution of the delegate.
     * @param request original request
     */
    private void handleDelegateExecution(final HttpServerRequest request) {
        String delegateName = getDelegateName(request.uri());
        Delegate delegate = delegateMap.get(delegateName);
        delegate.handle(request);
    }

}

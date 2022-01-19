package org.swisspush.gateleen.delegate;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.http.ClientRequestCreator;
import org.swisspush.gateleen.core.logging.LoggableResource;
import org.swisspush.gateleen.core.logging.RequestLogger;
import org.swisspush.gateleen.core.refresh.Refreshable;
import org.swisspush.gateleen.core.storage.ResourceStorage;
import org.swisspush.gateleen.core.util.ResourcesUtils;
import org.swisspush.gateleen.core.util.StatusCode;
import org.swisspush.gateleen.monitoring.MonitoringHandler;
import org.swisspush.gateleen.validation.ValidationException;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
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
public class DelegateHandler implements Refreshable, LoggableResource {
    private static final String DEFINITION_RESOURCE = "definition";
    private static final String EXECUTION_RESOURCE = "execution";
    private static final String SAVE_DELEGATE_ADDRESS = "gateleen.delegate-insert";
    private static final String REMOVE_DELEGATE_ADDRESS = "gateleen.delegate-remove";
    private static final Logger LOG = LoggerFactory.getLogger(DelegateHandler.class);
    private static final int NAME_GROUP_INDEX = 1;
    private static final int MESSAGE_NAME = 0;
    private static final int MESSAGE_URL = 1;

    private final Vertx vertx;
    private final ResourceStorage delegateStorage;
    private final String delegatesUri;
    private final DelegateFactory delegateFactory;
    private final Pattern delegateNamePattern;
    private final Map<String, Delegate> delegateMap;
    private final Handler<Void> doneHandler;

    private boolean initialized;
    private boolean logDelegateChanges = false;

    /**
     * Creates a new instance of the DelegateHandler.
     *
     * @param vertx vertx
     * @param selfClient selfClient
     * @param delegateStorage delegateStorage - only used for storing delegates
     * @param monitoringHandler monitoringHandler
     * @param delegatesUri delegate root
     * @param properties properties
     * @param doneHandler doneHandler
     */
    public DelegateHandler(final Vertx vertx, final HttpClient selfClient, final ResourceStorage delegateStorage,
                           final MonitoringHandler monitoringHandler, final String delegatesUri,
                           final Map<String, Object> properties,
                           final Handler<Void> doneHandler) {
        this.vertx = vertx;
        this.delegateStorage = delegateStorage;
        this.delegatesUri = delegatesUri;
        this.doneHandler = doneHandler;

        String delegatesSchema = ResourcesUtils.loadResource("gateleen_delegate_schema_delegates", true);
        this.delegateFactory = new DelegateFactory(new ClientRequestCreator(selfClient), properties, delegatesSchema);

        delegateNamePattern = Pattern.compile(delegatesUri + "([^/]+)(/" + DEFINITION_RESOURCE + "|/"+ EXECUTION_RESOURCE + ".*" + "|/?)");

        delegateMap = new HashMap<>();
        initialized = false;
    }

    /**
     * This method initializes the Handler. It should be called
     * after the router is successfully established.
     */
    public void init() {
        if ( ! initialized ) {
            // add all init methods here (!)
            final List<Consumer<Handler<Void>>> initMethods = new ArrayList<>();
            initMethods.add(this::registerDelegateRegistrationHandler);
            initMethods.add(this::loadStoredDelegates);

            // ready handler, calls the doneHandler when everything is done and the DelegateHandler is ready to use
            Handler<Void> readyHandler = new Handler<>() {
                // count of methods with may return an OK (ready)
                private AtomicInteger readyCounter = new AtomicInteger(initMethods.size());

                @Override
                public void handle(Void aVoid) {
                    if (readyCounter.decrementAndGet() == 0) {
                        initialized = true;
                        LOG.info("DelegateHandler is ready!");
                        if (doneHandler != null) {
                            doneHandler.handle(null);
                        }
                    }
                }
            };

            initMethods.forEach(handlerConsumer -> handlerConsumer.accept(readyHandler));
        }
    }

    /**
     * Loads the stored delegates during the
     * start sequence of the server.
     *
     * @param readyHandler
     */
    private void loadStoredDelegates(Handler<Void> readyHandler) {
        delegateStorage.get(delegatesUri, buffer -> {
            // clear all delegates
            delegateMap.clear();

            if (buffer != null) {
                JsonObject listOfDelegates = new JsonObject(buffer.toString());
                JsonArray delegateNames = listOfDelegates.getJsonArray("delegates");
                Iterator<String> keys = delegateNames.getList().iterator();

                final AtomicInteger storedDelegateCount = new AtomicInteger(delegateNames.getList().size());

                // go through the delegates ...
                while( keys.hasNext() ) {
                    final String key = keys.next().replace("/", "");

                    // ... and load each one
                    delegateStorage.get(delegatesUri + key + "/definition", delegateBody -> {
                        if ( delegateBody != null ) {
                            LOG.info("Loading delegate: {}", key );
                            registerDelegate(delegateBody, key);
                        }
                        else {
                            LOG.warn("Could not get URL '{}/definition'.", delegatesUri + key);
                        }

                        // send a ready flag
                        if ( storedDelegateCount.decrementAndGet() == 0 && readyHandler != null ) {
                            readyHandler.handle( null );
                        }
                    });
                }
            } else {
                LOG.debug("No delegates previously stored");
                // send a ready flag
                if ( readyHandler != null ) {
                    readyHandler.handle( null );
                }
            }
        });
    }

    /**
     * Registers all needed handlers for the
     * delegate registration / unregistration.
     * @param readyHandler
     */
    private void registerDelegateRegistrationHandler(Handler<Void> readyHandler) {
        if ( LOG.isTraceEnabled()) {
            LOG.trace("registerDelegateRegistrationHandler");
        }

        // Receive delegate insert notifications
        vertx.eventBus().consumer(SAVE_DELEGATE_ADDRESS, (Handler<Message<String>>) delegateEvent -> {
            final String[] messages = delegateEvent.body().split(";");

            if ( messages != null ) {
                delegateStorage.get(messages[MESSAGE_URL], buffer -> {
                    if (buffer != null) {
                        registerDelegate(buffer, messages[MESSAGE_NAME]);
                    } else {
                        LOG.warn("Could not get URL '{}' (getting delegate).", messages[MESSAGE_URL]);
                    }
                });
            }
            else {
                LOG.warn("Could not get Delegate, empty delegateEvent.");
            }
        });

        // Receive delegate remove notifications
        vertx.eventBus().consumer(REMOVE_DELEGATE_ADDRESS, (Handler<Message<String>>) delegateName -> unregisterDelegate(delegateName.body()));

        // method done / no async processing pending
        readyHandler.handle(null);
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

            // if everything is fine, put it to the delegateStorage
            delegateStorage.put(request.uri(),request.headers(), buffer, status -> {
                if (status == StatusCode.OK.getStatusCode() ) {
                    if(logDelegateChanges){
                        RequestLogger.logRequest(vertx.eventBus(), request, status, buffer);
                    }
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
        delegateStorage.delete(delegatesUri + delegateName, status -> {
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
    protected String getDelegateName(final String uri) {
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
                LOG.debug("registering delegate={}", delegateName);
                handleDelegateRegistration(request);
                return true;
            }

            // is the delegate registered?
            if ( delegateMap.containsKey(delegateName) ) {

                // Execution
                if (request.uri().contains(EXECUTION_RESOURCE)) {
                    LOG.debug("executing delegate={}", delegateName);
                    handleDelegateExecution(request);
                    return true;
                }

                // Unregistration?
                if (request.method() == HttpMethod.DELETE) {
                    LOG.debug("unregister delegate={}", delegateName);
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

    @Override
    public void refresh() {
        loadStoredDelegates(null);
    }

    @Override
    public void enableResourceLogging(boolean resourceLoggingEnabled) {
        this.logDelegateChanges = resourceLoggingEnabled;
    }
}

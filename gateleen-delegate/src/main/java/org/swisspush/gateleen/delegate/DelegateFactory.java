package org.swisspush.gateleen.delegate;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.json.transform.JoltSpec;
import org.swisspush.gateleen.core.json.transform.JoltSpecBuilder;
import org.swisspush.gateleen.core.json.transform.JoltSpecException;
import org.swisspush.gateleen.core.util.StringUtils;
import org.swisspush.gateleen.core.validation.ValidationResult;
import org.swisspush.gateleen.monitoring.MonitoringHandler;
import org.swisspush.gateleen.validation.ValidationException;
import org.swisspush.gateleen.validation.Validator;

import java.util.*;
import java.util.regex.Pattern;

/**
 * DelegateFactory is used to create delegate objects from their text representation.
 *
 * @author https://github.com/ljucam [Mario Ljuca]
 */
public class DelegateFactory {
    private static final Logger LOG = LoggerFactory.getLogger(DelegateFactory.class);

    private static final String REQUESTS = "requests";
    private static final String METHODS = "methods";
    private static final String PATTERN = "pattern";
    private static final String TRANSFORM = "transform";
    private static final String TRANSFORM_WITH_METADATA = "transformWithMetadata";

    private final MonitoringHandler monitoringHandler;
    private final HttpClient selfClient;
    private final Map<String, Object> properties;
    private final String delegatesSchema;

    /**
     * Creates a new instance of the DelegateFactory.
     *
     * @param monitoringHandler
     * @param selfClient
     * @param properties
     * @param delegatesSchema
     */
    public DelegateFactory(final MonitoringHandler monitoringHandler, final HttpClient selfClient, final Map<String, Object> properties, final String delegatesSchema) {
        this.monitoringHandler = monitoringHandler;
        this.selfClient = selfClient;
        this.properties = properties;
        this.delegatesSchema = delegatesSchema;
    }

    /**
     * Tries to create a Delegate object out of the
     * buffer.
     *
     *
     * @param delegateName name of the delegate
     * @param buffer buffer of the delegate
     * @return a Delegate object
     */
    public Delegate parseDelegate(final String delegateName, final Buffer buffer) throws ValidationException {
        final String configString;

        // replace wildcard configs
        try {
            configString = StringUtils.replaceWildcardConfigs(buffer.toString("UTF-8"), properties);
        } catch(Exception e){
            throw new ValidationException(e);
        }

        // validate json
        ValidationResult validationResult = Validator.validateStatic(Buffer.buffer(configString), delegatesSchema, LOG);
        if(!validationResult.isSuccess()){
            throw new ValidationException(validationResult);
        }

        // everything is fine, create Delegate
        return createDelegate(delegateName, configString);
    }

    /**
     * Create the delegate out of the prepared string.
     *
     * @param delegateName name of the delegate
     * @param configString the string rep. of the delegate
     * @return the new delegate
     * @throws ValidationException
     */
    private Delegate createDelegate(final String delegateName, final String configString) throws ValidationException {
        JsonObject delegateObject = new JsonObject(configString);

        // methods of the delegate
        Set<HttpMethod> methods = new HashSet<>();
        delegateObject.getJsonArray(METHODS).forEach( method -> methods.add(HttpMethod.valueOf( (String) method)) );

        // pattern of the delegate
        Pattern pattern;
        try {
            pattern = Pattern.compile(delegateObject.getString(PATTERN));
        } catch(Exception e) {
            throw new ValidationException("Could not parse pattern [" + delegateObject.getString(PATTERN)+ "] of  delegate " + delegateName, e);
        }

        // requests of the delegate
        List<DelegateRequest> requests = new ArrayList<>();
        for(int i = 0; i< delegateObject.getJsonArray(REQUESTS).size(); i++) {
            if ( LOG.isTraceEnabled() ) {
                LOG.trace("request of [{}] #: {}", delegateName, i );
            }
            JsonObject requestJsonObject = (JsonObject) delegateObject.getJsonArray(REQUESTS).getValue(i);
            JoltSpec joltSpec = parsePayloadTransformSpec(requestJsonObject, delegateName);
            requests.add(new DelegateRequest(requestJsonObject, joltSpec));
        }

        return new Delegate(monitoringHandler, selfClient, delegateName, pattern, methods, requests );
    }

    private JoltSpec parsePayloadTransformSpec(JsonObject requestJsonObject, String delegateName) throws ValidationException {
        JsonArray transformArray = requestJsonObject.getJsonArray(TRANSFORM);
        if(transformArray != null) {
            return buildTransformSpec(delegateName, transformArray, false);
        }

        JsonArray transformWithMetadataArray = requestJsonObject.getJsonArray(TRANSFORM_WITH_METADATA);
        if(transformWithMetadataArray != null){
            return buildTransformSpec(delegateName, transformWithMetadataArray, true);
        }

        return null;
    }

    private JoltSpec buildTransformSpec(String delegateName, JsonArray transformSpecArray, boolean withMetadata) throws ValidationException {
        try {
            return JoltSpecBuilder.buildSpec(transformSpecArray.encode(), withMetadata);
        } catch (JoltSpecException e) {
            String jsonOp = withMetadata ? TRANSFORM_WITH_METADATA : TRANSFORM;
            throw new ValidationException("Could not parse json " + jsonOp + " specification of delegate " + delegateName, e);
        }
    }
}

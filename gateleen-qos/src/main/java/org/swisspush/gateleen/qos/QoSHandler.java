package org.swisspush.gateleen.qos;

import org.swisspush.gateleen.core.http.RequestLoggerFactory;
import org.swisspush.gateleen.core.logging.LoggableResource;
import org.swisspush.gateleen.core.logging.RequestLogger;
import org.swisspush.gateleen.core.storage.ResourceStorage;
import org.swisspush.gateleen.core.util.ResourcesUtils;
import org.swisspush.gateleen.core.util.ResponseStatusCodeLogUtil;
import org.swisspush.gateleen.core.util.StatusCode;
import com.floreysoft.jmte.DefaultModelAdaptor;
import com.floreysoft.jmte.Engine;
import com.floreysoft.jmte.ErrorHandler;
import com.floreysoft.jmte.TemplateContext;
import com.floreysoft.jmte.message.ParseException;
import com.floreysoft.jmte.token.Token;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.validation.ValidationResult;
import org.swisspush.gateleen.validation.ValidationException;
import org.swisspush.gateleen.validation.Validator;

import javax.management.*;
import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.regex.Pattern;

/**
 * QoS Handler.
 * <pre>
     {
        "config":{
            "percentile":75,
            "quorum":40,
            "period":5,
            "minSampleCount" : 1000,
            "minSentinelCount" : 5
        },
        "sentinels":{
            "sentinelA":{
                "percentile":50
            },
            "sentinelB":{},
            "sentinelC":{},
            "sentinelD":{}
        },
        "rules":{
            "/test/myapi1/v1/.*":{
                "reject":1.2,
                "warn":0.5
            },
            "/test/myapi2/v1/.*":{
                "reject":0.3
            }
        }
    }
 * </pre>
 * <p>
 * The <b><code>config</code></b> section defines the global settings of the QoS. <br>
 * <b><code>percentile</code></b>: Indicates which percentile value from the metrics will be used (eg. 50, 75, 95, 98, 999 or 99)<br>
 * <b><code>quorum</code></b>: Percentage of the the sentinels which have to be over the calculated threshold to trigger the given rule. <br>
 * <b><code>period</code></b>: The period (in seconds) after which a new calculation is triggered. If a rule is set to reject requests,
 * it will reject requests until the next period. <br>
 * <b><code>minSampleCount</code></b>: The min. count of the samples a sentinel has to provide to be regarded for the QoS calculation. <br>
 * <b><code>minSentinelCount</code></b>:The min count of sentinels which have to be available to perform a QoS calculation. A sentinel is only available
 * if it corresponds to the minSampleCount rule. <br>
 * </p>
 * <p>
 * The <b><code>sentinels</code></b> section defines which metrics (defined in the routing rules) will be used as sentinels. To determine
 * the load, the lowest measured percentile value will be preserved for each sentinel and put in relation to the current percentile value.
 * This calculated ratio is later used to check if a rule needs some actions or not. You can override
 * the taken percentile value for a specific sentinel by setting the attribute <code>percentile</code>.
 * </p>
 * <p>
 * The <b><code>rules</code></b> section defines the rules for the QoS. Each rule is based on a pattern like the routing rules. <br>
 * The possible attributes are: <br>
 * <b><code>reject</code></b>: The ratio (eg. 1.3 means that *quorum* % of all sentinels must have an even or greater current ratio)
 * which defines when a rule rejects the given request. <br>
 * <b><code>warn</code></b>: The ratio which defines when a rule writes a warning in the log without rejecting the given request. <br>
 * </p>
 * <p>You can combine warn and reject
 *
 * @author https://github.com/ljucam [Mario Ljuca]
 */
public class QoSHandler implements LoggableResource {
    private static final Logger log = LoggerFactory.getLogger(QoSHandler.class);
    private static final String UPDATE_ADDRESS = "gateleen.qos-settings-updated";

    private static final String JSON_FIELD_CONFIG = "config";
    private static final String JSON_FIELD_SENTINELS = "sentinels";
    private static final String JSON_FIELD_RULES = "rules";

    private static final String PERCENTILE_SUFFIX = "thPercentile";

    protected static final String REJECT_ACTION = "reject";
    protected static final String WARN_ACTION = "warn";

    private final Vertx vertx;
    private final ResourceStorage storage;
    private final String qosSettingsUri;
    private final Map<String, Object> properties;
    private final String prefix;
    private final String qosSettingsSchema;

    private MBeanServer mbeanServer;
    private long timerId = -1;

    private QoSConfig globalQoSConfig;
    private List<QoSRule> qosRules;
    private List<QoSSentinel> qosSentinels;

    private boolean logQosConfigurationChanges = false;

    /**
     * Creates a new instance of the QoSHandler.
     *
     * @param vertx           vertx reference
     * @param storage         storage reference
     * @param qosSettingsPath the url path to the QoS rules
     * @param properties      the properties
     * @param prefix          the prefix for the server
     */
    public QoSHandler(final Vertx vertx, final ResourceStorage storage, final String qosSettingsPath, final Map<String, Object> properties, String prefix) {
        this.vertx = vertx;
        this.storage = storage;
        this.qosSettingsUri = qosSettingsPath;
        this.properties = properties;
        this.prefix = prefix;

        qosRules = new ArrayList<>();

        // get the MBeanServer
        setMBeanServer(ManagementFactory.getPlatformMBeanServer());

        qosSettingsSchema = ResourcesUtils.loadResource("gateleen_qos_schema_config", true);

        // loading stored QoS settings
        loadQoSSettings();

        // register an update handler for further updates
        registerUpdateHandler();
    }

    @Override
    public void enableResourceLogging(boolean resourceLoggingEnabled) {
        this.logQosConfigurationChanges = resourceLoggingEnabled;
    }

    /**
     * Sets the mbean server. This method is usefull for
     * mocking in tests.
     *
     * @param mbeanServer a mbean server
     */
    protected void setMBeanServer(MBeanServer mbeanServer) {
        this.mbeanServer = mbeanServer;
    }

    /**
     * Loads the QoS settings.
     */
    private void loadQoSSettings() {
        storage.get(qosSettingsUri, buffer -> {
            if (buffer != null) {
                try {
                    log.info("Applying QoS settings");
                    updateQoSSettings(buffer);
                } catch (IllegalArgumentException e) {
                    log.error("Could not reconfigure QoS", e);
                }
            } else {
                log.warn("Could not get URL '" + (qosSettingsUri == null ? "<null>" : qosSettingsUri) + "' (getting settings).");
            }
        });
    }

    /**
     * Registers the update handler for QoS Settings.
     */
    private void registerUpdateHandler() {
        // Receive update notifications
        vertx.eventBus().consumer(UPDATE_ADDRESS, event -> loadQoSSettings());
    }

    /**
     * Processes the request if it’s a request concerning
     * the updates of the QoS settings or if it’s a request
     * affected by the QoS rules. In either case the return
     * value will be <code>true</code> otherwise <code>false</code>.
     *
     * @param request
     * @return true if request is QoS update or affected by the
     * rules, otherwise false.
     */
    public boolean handle(final HttpServerRequest request) {

        // QoS Settings update
        if (isQoSSettingsUpdate(request)) {
            handleQoSSettingsUpdate(request);
            return true;
        }

        // performs - if neccessary - the QoS action
        return qoSHandledRequest(request);
    }

    /**
     * Checks if a rule matches the given request and if there
     * are actions for the given rule to be performed.
     * Depending on the action (if any), the return result can be
     * true (already handled the request) or false (not yet handled).
     * If no matching rule is found false is returned.
     *
     * @param request the given request
     * @return returning true means that the request has already been handled, otherwise false is returned.
     */
    private boolean qoSHandledRequest(final HttpServerRequest request) {
        // check if the request matches a pattern in the QoS rules
        for (QoSRule rule : qosRules) {

            // is there anything to perform and
            // if so, does the pattern matches?
            if (rule.performAction() && rule.getUrlPattern().matcher(request.uri()).matches()) {
                boolean requestHandled = false;

                // perform the action
                for (String action : rule.getActions()) {
                    switch (action) {
                        case REJECT_ACTION:
                            handleReject(request);
                            requestHandled = true;
                            break;
                        case WARN_ACTION:
                            RequestLoggerFactory.getLogger(QoSHandler.class, request)
                                    .warn("QoS Warning: Heavy load detected for rule {}, concerning the request {}",
                                            rule.getUrlPattern(), request.uri());
                            break;
                    }
                }

                // only one rule may match the given pattern, so we return if the request has to be handled or not
                return requestHandled;
            }
        }
        return false;
    }

    /**
     * The given request will directly be rejected.
     *
     * @param request the original request.
     */
    private void handleReject(final HttpServerRequest request) {
        ResponseStatusCodeLogUtil.info(request, StatusCode.SERVICE_UNAVAILABLE, QoSHandler.class);
        request.response().setStatusCode(StatusCode.SERVICE_UNAVAILABLE.getStatusCode());
        request.response().setStatusMessage(StatusCode.SERVICE_UNAVAILABLE.getStatusMessage());
        request.response().end();
    }

    private void validateConfigurationValues(Buffer qosSettingsBuffer) throws ValidationException {
        ValidationResult validationResult = Validator.validateStatic(qosSettingsBuffer, qosSettingsSchema, log);
        if(!validationResult.isSuccess()){
            throw new ValidationException(validationResult);
        }

        try{
            JsonObject qosSettings = parseQoSSettings(qosSettingsBuffer);
            QoSConfig config = createQoSConfig(qosSettings);
            List<QoSSentinel> sentinels = createQoSSentinels(qosSettings);
            List<QoSRule> rules = createQoSRules(qosSettings);
            extendedValidation(config, sentinels, rules);
        } catch(Exception ex){
            throw new ValidationException(ex);
        }
    }

    private void extendedValidation(QoSConfig config, List<QoSSentinel> sentinels, List<QoSRule> rules) throws ValidationException {
        // rules or sentinel without config
        if (config == null && (!sentinels.isEmpty() || !rules.isEmpty())) {
            throw new ValidationException("QoS setting contains rules or sentinels without global config!");
        }
        // rules without sentinels
        else if (sentinels.isEmpty() && !rules.isEmpty()) {
            throw new ValidationException("QoS settings contain rules without sentinels");
        }
    }

    /**
     * Stores the QoS settings in the storage (or deletes them) and
     * calls the update of the QoS Settings.
     *
     * @param request the original request
     */
    private void handleQoSSettingsUpdate(final HttpServerRequest request) {
        // put
        if (HttpMethod.PUT == request.method()) {
            request.bodyHandler(buffer -> {
                try {
                    validateConfigurationValues(buffer);
                } catch (ValidationException validationException) {
                    log.error("Could not parse QoS config resource: " + validationException.toString());
                    request.response().setStatusCode(StatusCode.BAD_REQUEST.getStatusCode());
                    request.response().setStatusMessage(StatusCode.BAD_REQUEST.getStatusMessage() + " " + validationException.getMessage());
                    if(validationException.getValidationDetails() != null){
                        request.response().headers().add("content-type", "application/json");
                        request.response().end(validationException.getValidationDetails().encode());
                    } else {
                        request.response().end(validationException.getMessage());
                    }
                    return;
                }

                storage.put(qosSettingsUri, buffer, status -> {
                    if (status == StatusCode.OK.getStatusCode()) {
                        if (logQosConfigurationChanges) {
                            RequestLogger.logRequest(vertx.eventBus(), request, status, buffer);
                        }
                        vertx.eventBus().publish(UPDATE_ADDRESS, true);
                    } else {
                        request.response().setStatusCode(status);
                    }
                    request.response().end();
                });
            });
        }
        // delete
        else {
            storage.delete(qosSettingsUri, status -> {
                if (status == StatusCode.OK.getStatusCode()) {
                    vertx.eventBus().publish(UPDATE_ADDRESS, true);
                } else {
                    request.response().setStatusCode(status);
                }
                request.response().end();
            });
        }
    }

    /**
     * Creates the QoS config object from the given JsonObject
     * and returns it.
     *
     * @param qosSettings json object
     * @return the global QoS config object, null if the config section
     * is not present.
     */
    protected QoSConfig createQoSConfig(JsonObject qosSettings) {
        /* @formatter:off
        
         "config": {
             "percentile": 75,
             "quorum": 40,
             "period": 60, 
             "minSampleCount" : 1000, 
             "minSentinelCount" : 5
         },
         
         * @formatter:on
         */

        if (qosSettings.containsKey(JSON_FIELD_CONFIG)) {
            JsonObject jsonConfig = qosSettings.getJsonObject(JSON_FIELD_CONFIG);
            return new QoSConfig(jsonConfig.getInteger("percentile"),
                    jsonConfig.getInteger("quorum"),
                    jsonConfig.getInteger("period"),
                    jsonConfig.getInteger("minSampleCount"),
                    jsonConfig.getInteger("minSentinelCount"));
        }

        return null;
    }

    /**
     * Creates the QoS sentinel objects from the given JsonObject
     * and returns them in a list.
     *
     * @param qosSettings json object
     * @return a list with the QoSSentinel objects, empty if the sentinel section
     * is not present or empty.
     */
    protected List<QoSSentinel> createQoSSentinels(JsonObject qosSettings) {
        List<QoSSentinel> sentinels = new ArrayList<>();
        /* @formatter:off
         
        "sentinels": {
            "aMetric": {},
            "bMetric": { percentile: 50 }
         },
         
         * @formatter:on
         */

        if (qosSettings.containsKey(JSON_FIELD_SENTINELS)) {
            JsonObject jsonSentinels = qosSettings.getJsonObject(JSON_FIELD_SENTINELS);
            for (String sentinelName : jsonSentinels.fieldNames()) {
                log.debug("Creating a new QoS sentinel object for metric: " + sentinelName);

                JsonObject jsonSentinel = jsonSentinels.getJsonObject(sentinelName);
                QoSSentinel sentinel = new QoSSentinel(sentinelName);
                QoSSentinel oldSentinel = getOldSentinel(sentinelName);

                // to preserve the "normal" value of the lowest percentile value,
                // we take it from the old sentinel, this way we may even change
                // the rules during heavy load
                if (oldSentinel != null) {
                    sentinel.setLowestPercentileValue(oldSentinel.getLowestPercentileValue());
                }

                if (jsonSentinel.containsKey("minLowestPercentileValueMs")) {
                    Double minLowestPercentileValueMs = jsonSentinel.getDouble("minLowestPercentileValueMs");
                    sentinel.setLowestPercentileMinValue(minLowestPercentileValueMs);
                    if(sentinel.getLowestPercentileValue() < minLowestPercentileValueMs){
                        log.debug("Set lowest percentile value "+sentinel.getLowestPercentileValue()+" of sentinel '"+sentinelName+"' to a minLowestPercentileValueMs value of " + minLowestPercentileValueMs);
                        sentinel.setLowestPercentileValue(minLowestPercentileValueMs);
                    }
                }

                if (jsonSentinel.containsKey("percentile")) {
                    sentinel.setPercentile(jsonSentinel.getInteger("percentile"));
                }

                sentinels.add(sentinel);
            }
        }

        return sentinels;
    }

    /**
     * We try to retrive the old sentinel (if available).
     *
     * @param sentinelName the name of the sentinel.
     * @return old sentinel object or null if no sentinel was found
     */
    public QoSSentinel getOldSentinel(String sentinelName) {
        if (qosSentinels == null || qosSentinels.isEmpty()) {
            return null;
        } else {
            for (QoSSentinel sentinel : qosSentinels) {
                if (sentinel.getName().equalsIgnoreCase(sentinelName)) {
                    return sentinel;
                }
            }
        }

        return null;
    }

    /**
     * Creates the QoS rule objects from the given JsonObject
     * and returns them in a list.
     *
     * @param qosSettings json object
     * @return a list with the QoSRule objects, empty if the rules section
     * is not present or empty.
     */
    private List<QoSRule> createQoSRules(JsonObject qosSettings) {
        List<QoSRule> rules = new ArrayList<>();

        /*
         @formatter:off
        PUT /server/admin/v1/qos

          "rules": {
            "/gateleen/xyz/v1/(delivery|acceptance)/.*": {
              "reject": 1.3,
              "warn": 1.1
            }
          }
        
        @formatter:on
        */

        if (qosSettings.containsKey(JSON_FIELD_RULES)) {
            JsonObject jsonRules = qosSettings.getJsonObject(JSON_FIELD_RULES);

            for (String urlPatternRegExp : jsonRules.fieldNames()) {
                log.debug("Creating a new QoS rule object for URL pattern: " + urlPatternRegExp);

                JsonObject jsonRule = jsonRules.getJsonObject(urlPatternRegExp);

                // pattern for matching
                Pattern urlPattern = Pattern.compile(urlPatternRegExp);

                QoSRule rule = new QoSRule(urlPattern);

                boolean addRule = false;

                // reject ratio
                if (jsonRule.containsKey("reject")) {
                    addRule = true;
                    rule.setReject(jsonRule.getDouble("reject"));
                }

                // warn ratio
                if (jsonRule.containsKey("warn")) {
                    addRule = true;
                    rule.setWarn(jsonRule.getDouble("warn"));
                }

                if (addRule) {
                    rules.add(rule);
                } else {
                    log.warn("No or unknown QoS action defined for rule {}. This rule will not be loaded!", urlPatternRegExp);
                }
            }
        }

        return rules;
    }

    /**
     * Tries to parse the QoS settings.
     *
     * @param buffer buffer from the request
     * @return a JsonObject containing the settings.
     */
    protected JsonObject parseQoSSettings(final Buffer buffer) {
        try {
            return new JsonObject(replaceConfigWildcards(buffer.toString("UTF-8")));
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    /**
     * Replace - if there are any - configuration wildcards.
     *
     * @param configWithWildcards the content of the rule buffer
     * @return the adapted content of the rule buffer
     */
    private String replaceConfigWildcards(String configWithWildcards) {
        Engine engine = new Engine();
        engine.setModelAdaptor(new DefaultModelAdaptor() {
            @Override
            public Object getValue(TemplateContext context, Token arg1, List<String> arg2, String expression) {
                // First look in model map. Needed for dot-separated properties
                Object value = context.model.get(expression);
                if (value != null) {
                    return value;
                } else {
                    return super.getValue(context, arg1, arg2, expression);
                }
            }

            @Override
            protected Object traverse(Object obj, List<String> arg1, int arg2, ErrorHandler arg3, Token token) {
                // Throw exception if a token cannot be resolved instead of returning empty string.
                if (obj == null) {
                    throw new IllegalArgumentException("Could not resolve " + token);
                }
                return super.traverse(obj, arg1, arg2, arg3, token);
            }
        });
        try {
            return engine.transform(configWithWildcards, properties);
        } catch (ParseException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /**
     * Indicates if the request is an update of the QoS Settings.
     *
     * @param request the request.
     * @return true if its an update of the settings, otherwise false.
     */
    private boolean isQoSSettingsUpdate(final HttpServerRequest request) {
        return request.uri().equals(qosSettingsUri) && (HttpMethod.PUT == request.method() || HttpMethod.DELETE == request.method());
    }

    /**
     * Updates the QoS Settings and reinitialitze the
     * timer, as well as the calculation.
     *
     * @param buffer buffer of the settings
     */
    private void updateQoSSettings(final Buffer buffer) {
        log.info("About to update QoS settings with content: {}", buffer.toString());

        ValidationResult validationResult = Validator.validateStatic(buffer, qosSettingsSchema, log);
        if(!validationResult.isSuccess()){
            log.error("QoS is disabled now. Got invalid QoS settings from storage");
            return;
        }

        // cancel the old timer (if any)
        cancelTimer();

        // after timer cancelation we are save to change the current setting
        JsonObject qosSettings = parseQoSSettings(buffer);
        QoSConfig config = createQoSConfig(qosSettings);
        List<QoSSentinel> sentinels = createQoSSentinels(qosSettings);
        List<QoSRule> rules = createQoSRules(qosSettings);

        try {
            extendedValidation(config, sentinels, rules);
        } catch (ValidationException e) {
            log.error("QoS is disabled now. Message: " + e.getMessage());
            return;
        }

        setGlobalQoSConfig(config);
        setQosSentinels(sentinels);
        setQosRules(rules);

        // create a new timer ...
        // ... only if we have sentinels AND rules
        if (!qosSentinels.isEmpty() && !qosRules.isEmpty()) {
            log.debug("Start periodic timer every " + globalQoSConfig.getPeriod() + "s");
            // evaluation timer
            timerId = vertx.setPeriodic(globalQoSConfig.getPeriod() * 1000, event -> {
                log.debug("Timer fired: executing evaluateQoSActions");
                evaluateQoSActions();
            });
        } else {
            log.info("QoS is disabled now. No rules and sentinels found.");
        }
    }

    /**
     * Cancels the timer. <br>
     * This method is protected to be used
     * in tests where you wish to manually trigger
     * the evaluation.
     */
    protected void cancelTimer() {
        log.debug("About to cancel timer");
        vertx.cancelTimer(timerId);
    }

    /**
     * Calculates the threshold and determines if any action
     * for each rule has to be applied.
     */
    protected void evaluateQoSActions() {
        /*
         * Connect to JMX, load the metrics module
         * and read the given sentinel (metric) values.
         */

        if (log.isTraceEnabled()) {
            Set<ObjectInstance> instances = mbeanServer.queryMBeans(null, null);
            for (ObjectInstance instance : instances) {
                log.trace("MBean Found:");
                log.trace("Class Name:t" + instance.getClassName());
                log.trace("Object Name:t" + instance.getObjectName());
                log.trace("****************************************");
            }
        }

        int validSentinels = 0;
        List<Double> currentSentinelRatios = new ArrayList<>();

        // load the sentinels and read the percentil value
        for (QoSSentinel sentinel : qosSentinels) {
            String name = "metrics:name=" + prefix + "routing." + sentinel.getName() + ".duration";

            try {
                ObjectName beanName = new ObjectName(name);
                // is sentinel registred and if so,
                // is the sample count even or greater then the given one?
                if (mbeanServer.isRegistered(beanName)) {
                    long currentSampleCount = (Long) mbeanServer.getAttribute(beanName, "Count");
                    if (currentSampleCount >= globalQoSConfig.getMinSampleCount()) {

                        double currentResponseTime = 0.0;

                        // override
                        if (sentinel.getPercentile() != null) {
                            currentResponseTime = (Double) mbeanServer.getAttribute(beanName, "" + sentinel.getPercentile() + PERCENTILE_SUFFIX);
                        }
                        // global
                        else {
                            currentResponseTime = (Double) mbeanServer.getAttribute(beanName, "" + globalQoSConfig.getPercentile() + PERCENTILE_SUFFIX);
                        }

                        // the reference value of the sentinel
                        // has to be the lowest measured percentile value
                        // over all readings
                        if (sentinel.getLowestPercentileValue() > currentResponseTime) {
                            if(currentResponseTime > 0.0) {
                                if(sentinel.getLowestPercentileMinValue() != null && currentResponseTime < sentinel.getLowestPercentileMinValue()){
                                    sentinel.setLowestPercentileValue(sentinel.getLowestPercentileMinValue());
                                } else {
                                    sentinel.setLowestPercentileValue(currentResponseTime);
                                }
                            } else {
                                log.debug("ignoring response time of 0.0, because the metric is probably not yet fully initalized");
                            }
                        }

                        // calculate the current ratio compared to the reference percentile value
                        double currentRatio = currentResponseTime / sentinel.getLowestPercentileValue();
                        currentSentinelRatios.add(currentRatio);

                        log.debug("sentinel '" + sentinel.getName() + "': percentile="+sentinel.getPercentile()+", lowestPercentileValue=" + sentinel.getLowestPercentileValue()
                                + ", lowestPercentileMinValue=" + sentinel.getLowestPercentileMinValue()
                                + ", currentSampleCount=" + currentSampleCount
                                + ", currentResponseTime=" + currentResponseTime
                                + ", currentRatio=" + currentRatio);

                        // increment valid counter
                        validSentinels++;
                    } else {
                        log.warn("Sentinel " + sentinel.getName() + " doesn't have enough samples yet (" + currentSampleCount + "/" + globalQoSConfig.getMinSampleCount() + ")");
                    }
                } else {
                    log.warn("MBean {} for sentinel {} is not ready yet ...", name, sentinel.getName());
                }
            } catch (MalformedObjectNameException e) {
                log.error("Could not load MBean for metric name '" + sentinel.getName() + "'.", e);
            } catch (AttributeNotFoundException e) {
                // ups ... should not be possible, we check if the bean is registred
            } catch (InstanceNotFoundException e) {
                log.error("Could not find attribute " + sentinel.getPercentile() + PERCENTILE_SUFFIX + " for the MBean of the metric '" + sentinel.getName() + "'.", e);
            } catch (MBeanException | ReflectionException e) {
                log.error("Could not load value of attribute " + sentinel.getPercentile() + PERCENTILE_SUFFIX + " for the MBean of the metric '" + sentinel.getName() + "'.", e);
            }
        }

        // do we have something to work with?
        if (validSentinels >= globalQoSConfig.getMinSentinelCount()) {
            // index which should still be even or greater then the calc. ratio in a desc. sorted list
            // we have to subtract 1 to get the index in the list
            int threshold = (int) Math.ceil(validSentinels / 100.0 * globalQoSConfig.getQuorum()) - 1;
            currentSentinelRatios.sort(Collections.reverseOrder());

            if (log.isTraceEnabled()) {
                for (double sentinelRatio : currentSentinelRatios) {
                    log.trace(" -> {}", sentinelRatio);
                }
            }

            log.debug("Sentinels count: {}", validSentinels);
            log.debug("Sentinels ratios: {}", currentSentinelRatios);
            log.debug("Threshold index: {}", threshold);
            log.debug("Threshold ratio: {}", currentSentinelRatios.get(threshold));

            for (QoSRule rule : qosRules) {
                Double warn = rule.getWarn();
                Double reject = rule.getReject();

                // x% are even or higher then the given alert level

                // reject
                if (actionNecessary(reject, currentSentinelRatios.get(threshold))) {
                    log.debug("rule will be rejected: {}", rule.getUrlPattern());
                    rule.addAction(REJECT_ACTION);
                } else {
                    log.debug("rule will not be rejected: {}", rule.getUrlPattern());
                    rule.removeAction(REJECT_ACTION);
                }

                // warn
                if (actionNecessary(warn, currentSentinelRatios.get(threshold))) {
                    log.debug("rule will be logged with a warning: {}", rule.getUrlPattern());
                    rule.addAction(WARN_ACTION);
                } else {
                    log.debug("rule will not be logged with a warning: {}", rule.getUrlPattern());
                    rule.removeAction(WARN_ACTION);
                }
            }
        }
        // nothing to do
        else {
            // in case the available sentinels dropped below the needed value
            // and some rules are still blocked
            qosRules.forEach(QoSRule::clearAction);
        }
    }

    /**
     * Takes the ratio of the rule and compares if
     * the calculated sentinel ratio from the desc. sorted
     * list at the index coresponding to the percentual
     * value (quorum) of all sentinel api's is lower or even.
     *
     * @param ratio
     * @param thresholdSentinelRatio
     * @return true if any action must be applied, otherwise false
     */
    protected boolean actionNecessary(Double ratio, double thresholdSentinelRatio) {
        return ratio != null && ratio <= thresholdSentinelRatio;
    }

    /**
     * Sets the global configuration for the QoS.
     *
     * @param globalQoSConfig the config object for QoS.
     */
    protected void setGlobalQoSConfig(QoSConfig globalQoSConfig) {
        this.globalQoSConfig = globalQoSConfig;
    }

    /**
     * Returns a list of the QoS rules.
     *
     * @return list of QoS rules.
     */
    protected List<QoSRule> getQosRules() {
        return qosRules;
    }

    /**
     * Sets the QoS rules.
     *
     * @param qosRules a list of the QoS rule objects
     */
    protected void setQosRules(List<QoSRule> qosRules) {
        this.qosRules = qosRules;
    }

    /**
     * Sets a list of all sentinels.
     *
     * @param qosSentinels list with sentinel objects
     */
    protected void setQosSentinels(List<QoSSentinel> qosSentinels) {
        this.qosSentinels = qosSentinels;
    }

    /**
     * Gets a list of all sentinels.
     *
     * @return list with sentinel objects
     */
    protected List<QoSSentinel> getQosSentinels() {
        return qosSentinels;
    }
}

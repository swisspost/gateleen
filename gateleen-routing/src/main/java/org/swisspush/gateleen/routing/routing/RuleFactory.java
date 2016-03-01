package org.swisspush.gateleen.routing.routing;

import com.floreysoft.jmte.DefaultModelAdaptor;
import com.floreysoft.jmte.Engine;
import com.floreysoft.jmte.ErrorHandler;
import com.floreysoft.jmte.TemplateContext;
import com.floreysoft.jmte.message.ParseException;
import com.floreysoft.jmte.token.Token;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.validation.validation.ValidationException;
import org.swisspush.gateleen.validation.validation.ValidationResult;
import org.swisspush.gateleen.validation.validation.Validator;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RuleFactory is used to create rule objects from their text representation.
 */
public class RuleFactory {

    private Logger log = LoggerFactory.getLogger(RuleFactory.class);
    private Pattern urlParsePattern = Pattern.compile("^(?<scheme>https?)://(?<host>[^/:]+)(:(?<port>[0-9]+))?(?<path>/.*)$");
    private final Map<String, Object> properties;
    private String routingRulesSchema;

    public RuleFactory(Map<String, Object> properties, String routingRulesSchema) {
        this.properties = properties;
        this.routingRulesSchema = routingRulesSchema;
    }

    public List<Rule> parseRules(Buffer buffer) throws ValidationException {
        String replacedConfig;
        try {
            replacedConfig = replaceConfigWildcards(buffer.toString("UTF-8"));
        } catch (Exception e) {
            throw new ValidationException(e);
        }
        ValidationResult validationResult = Validator.validateStatic(Buffer.buffer(replacedConfig), routingRulesSchema, log);
        if (validationResult.isSuccess()) {
            return createRules(new JsonObject(replacedConfig));
        } else {
            throw new ValidationException(validationResult);
        }
    }

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


    public List<Rule> createRules(JsonObject rules) {
        List<Rule> result = new ArrayList<>();
        for (String urlPattern : rules.fieldNames()) {
            log.debug("Creating a new rule-object for URL pattern " + urlPattern);

            Rule ruleObj = new Rule();
            ruleObj.setUrlPattern(urlPattern);
            JsonObject rule = rules.getJsonObject(urlPattern);
            String targetUrl = rule.getString("url");
            String path = rule.getString("path");

            prepareUrl(urlPattern, ruleObj, targetUrl, path);

            String metricName = rule.getString("metricName");
            if (metricName != null) {
                ruleObj.setMetricName(metricName);
            }

            JsonObject basicAuth = rule.getJsonObject("basicAuth");
            if (basicAuth != null) {
                ruleObj.setUsername(basicAuth.getString("username"));
                ruleObj.setPassword(basicAuth.getString("password"));
            }

            int defaultTimeoutSec = 30;
            ruleObj.setTimeout(1000 * rule.getInteger("timeout", defaultTimeoutSec));
            ruleObj.setPoolSize(rule.getInteger("connectionPoolSize", 50));
            ruleObj.setKeepAlive(rule.getBoolean("keepAlive", true));
            ruleObj.setExpandOnBackend(rule.getBoolean("expandOnBackend", false));
            ruleObj.setStorageExpand(rule.getBoolean("storageExpand", false));
            ruleObj.setLogExpiry(rule.getInteger("logExpiry", 4 * 3600));

            JsonArray methods = rule.getJsonArray("methods");
            if (methods != null) {
                String[] methodsArray = new String[methods.size()];
                for (int i=0; i < methods.size(); i++) {
                    methodsArray[i] = methods.getString(i);
                }
                ruleObj.setMethods(methodsArray);
            }

            JsonArray profile = rule.getJsonArray("profile");
            if (profile != null) {
                String[] profileArray = new String[profile.size()];
                for (int i=0; i < profile.size(); i++) {
                    profileArray[i] = profile.getString(i);
                }
                ruleObj.setProfile(profileArray);
                log.debug("The profile-array is set. Those profile-information will be sent: " + Arrays.toString(ruleObj.getProfile()));
            } else {
                log.debug("The profile-array is not set. So won't send any profile-information when using this rule.");
            }

            setStorage(ruleObj, rule, path);
            setTranslateStatus(ruleObj, rule);
            setStaticHeaders(ruleObj, rule);

            result.add(ruleObj);
        }
        return result;
    }

    private void setStorage(Rule ruleObj, JsonObject rule, String path) {
        String ruleStorage = rule.getString("storage");
        if (ruleStorage != null) {
            if (path == null) {
                throw new IllegalArgumentException("For storage routing, 'path' must be specified.");
            }
            ruleObj.setScheme("storage");
            ruleObj.setStorage(ruleStorage);
        }
    }

    private void setTranslateStatus(Rule ruleObj, JsonObject rule) {
        JsonObject translateStatus = rule.getJsonObject("translateStatus");
        if (translateStatus != null) {
            ruleObj.addTranslateStatus(new LinkedHashMap<>());
            for (String pattern : translateStatus.fieldNames()) {
                ruleObj.getTranslateStatus().put(Pattern.compile(pattern), translateStatus.getInteger(pattern));
            }
        }
    }

    private void setStaticHeaders(Rule ruleObj, JsonObject rule) {
        JsonObject staticHeaders = rule.getJsonObject("staticHeaders");
        if (staticHeaders != null && staticHeaders.size() > 0) {
            ruleObj.addStaticHeaders(new LinkedHashMap<>());
            for (Map.Entry<String, Object> entry : staticHeaders.getMap().entrySet()) {
                ruleObj.getStaticHeaders().put(entry.getKey(), entry.getValue().toString());
            }
        }
    }

    private void prepareUrl(String urlPattern, Rule ruleObj, String targetUrl, String path) {
        if (targetUrl != null || path != null) {
            if (targetUrl != null && path != null) {
                throw new IllegalArgumentException("Either 'url' or 'path' must be given, not both");
            }
            if (targetUrl != null) {
                Matcher urlMatcher = urlParsePattern.matcher(targetUrl);
                if (!urlMatcher.matches()) {
                    throw new IllegalArgumentException("Invalid url for pattern " + urlPattern + ": " + targetUrl);
                }
                ruleObj.setHost(urlMatcher.group("host"));
                ruleObj.setScheme(urlMatcher.group("scheme"));
                ruleObj.setPort(ruleObj.getScheme().equals("https") ? 443 : 80);

                String portString = urlMatcher.group("port");
                if (portString != null) {
                    ruleObj.setPort(Integer.parseInt(portString));
                }

                ruleObj.setPath(urlMatcher.group("path"));
            } else {
                if (!path.startsWith("/")) {
                    throw new IllegalArgumentException("Illegal value for 'path', it must be a path starting with slash");
                }
                ruleObj.setPath(path);
                ruleObj.setHost("localhost");
                ruleObj.setScheme("local");
            }
        } else {
            // preventing a nullpointer
            ruleObj.setScheme("null");
        }
    }
}

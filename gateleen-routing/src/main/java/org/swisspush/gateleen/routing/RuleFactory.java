package org.swisspush.gateleen.routing;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.util.StringUtils;
import org.swisspush.gateleen.validation.ValidationException;
import org.swisspush.gateleen.validation.ValidationResult;
import org.swisspush.gateleen.validation.Validator;

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
            replacedConfig = StringUtils.replaceWildcardConfigs(buffer.toString("UTF-8"), properties);
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

    public List<Rule> createRules(JsonObject rules) throws ValidationException {
        Set<String> ruleNames = new HashSet<>();
        List<Rule> result = new ArrayList<>();
        for (String urlPattern : rules.fieldNames()) {
            log.debug("Creating a new rule-object for URL pattern " + urlPattern);

            Rule ruleObj = new Rule();
            ruleObj.setUrlPattern(urlPattern);
            JsonObject rule = rules.getJsonObject(urlPattern);

            String name = rule.getString("name");
            if(ruleNames.contains(name)){
                throw new ValidationException("Property 'name' must be unique. There are multiple rules with name '" + name + "'");
            } else {
                ruleNames.add(name);
                ruleObj.setName(name);
            }

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

    private void setStorage(Rule ruleObj, JsonObject rule, String path) throws ValidationException {
        String ruleStorage = rule.getString("storage");
        if (ruleStorage != null) {
            if (path == null) {
                throw new ValidationException("For storage routing, 'path' must be specified.");
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

    private void prepareUrl(String urlPattern, Rule ruleObj, String targetUrl, String path) throws ValidationException {
        if (targetUrl != null || path != null) {
            if (targetUrl != null && path != null) {
                throw new ValidationException("Either 'url' or 'path' must be given, not both");
            }
            if (targetUrl != null) {
                Matcher urlMatcher = urlParsePattern.matcher(targetUrl);
                if (!urlMatcher.matches()) {
                    throw new ValidationException("Invalid url for pattern " + urlPattern + ": " + targetUrl);
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
                    throw new ValidationException("Illegal value for 'path', it must be a path starting with slash");
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

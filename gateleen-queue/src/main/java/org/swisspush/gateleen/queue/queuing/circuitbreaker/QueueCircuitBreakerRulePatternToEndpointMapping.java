package org.swisspush.gateleen.queue.queuing.circuitbreaker;

import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.swisspush.gateleen.core.util.HashCodeGenerator;
import org.swisspush.gateleen.routing.Rule;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class QueueCircuitBreakerRulePatternToEndpointMapping {

    private Logger log = LoggerFactory.getLogger(QueueCircuitBreakerRulePatternToEndpointMapping.class);

    private Map<Pattern, PatternAndEndpointHash> rulePatternToEndpointMapping = new HashMap<>();

    void updateRulePatternToEndpointMapping(List<Rule> rules){
        rulePatternToEndpointMapping.clear();
        for (Rule rule : rules) {
            PatternAndEndpointHash patternAndEndpointHash = getPatternAndEndpointHashFromRule(rule);
            if(patternAndEndpointHash != null){
                rulePatternToEndpointMapping.put(patternAndEndpointHash.getPattern(), patternAndEndpointHash);
            } else {
                log.error("rule pattern and endpointHash could not be retrieved from rule " + rule.getUrlPattern());
            }
        }
    }

    public PatternAndEndpointHash getEndpointFromRequestUri(String requestUri){
        for (Pattern pattern : rulePatternToEndpointMapping.keySet()) {
            if(pattern.matcher(requestUri).matches()){
                return rulePatternToEndpointMapping.get(pattern);
            }
        }
        return null;
    }

    private PatternAndEndpointHash getPatternAndEndpointHashFromRule(Rule rule){
        try {
            Pattern pattern = Pattern.compile(rule.getUrlPattern());
            String endpointHash = HashCodeGenerator.createHashCode(rule.getUrlPattern());
            return new PatternAndEndpointHash(pattern, endpointHash);
        } catch (Exception e) {
            log.error("Could not compile the regex:" + rule.getUrlPattern() + " to a pattern.");
            return null;
        }
    }
}

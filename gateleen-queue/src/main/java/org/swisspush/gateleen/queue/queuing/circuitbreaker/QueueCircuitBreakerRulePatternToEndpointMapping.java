package org.swisspush.gateleen.queue.queuing.circuitbreaker;

import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.swisspush.gateleen.core.util.HashCodeGenerator;
import org.swisspush.gateleen.routing.Rule;

import java.util.*;
import java.util.regex.Pattern;

/**
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class QueueCircuitBreakerRulePatternToEndpointMapping {

    private Logger log = LoggerFactory.getLogger(QueueCircuitBreakerRulePatternToEndpointMapping.class);

    private List<PatternAndEndpointHash> rulePatternToEndpointMapping = new ArrayList<>();

    void updateRulePatternToEndpointMapping(List<Rule> rules){
        log.debug("clearing rule pattern to endpoint mapping values");
        rulePatternToEndpointMapping.clear();
        log.debug("new rule pattern to endpoint mapping values are:");
        for (Rule rule : rules) {
            PatternAndEndpointHash patternAndEndpointHash = getPatternAndEndpointHashFromRule(rule);
            log.debug(patternAndEndpointHash);
            if(patternAndEndpointHash != null){
                rulePatternToEndpointMapping.add(patternAndEndpointHash);
            } else {
                log.error("rule pattern and endpointHash could not be retrieved from rule " + rule.getUrlPattern());
            }
        }
    }

    public PatternAndEndpointHash getEndpointFromRequestUri(String requestUri){
        for (PatternAndEndpointHash mapping : rulePatternToEndpointMapping) {
            if(mapping.getPattern().matcher(requestUri).matches()){
                return mapping;
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

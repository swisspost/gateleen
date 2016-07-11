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
public class QueueCircuitBreakerRulePatternToCircuitMapping {

    private Logger log = LoggerFactory.getLogger(QueueCircuitBreakerRulePatternToCircuitMapping.class);

    private List<PatternAndCircuitHash> rulePatternToCircuitMapping = new ArrayList<>();

    void updateRulePatternToCircuitMapping(List<Rule> rules){
        log.debug("clearing rule pattern to circuit mapping values");
        rulePatternToCircuitMapping.clear();
        log.debug("new rule pattern to circuit mapping values are:");
        for (Rule rule : rules) {
            PatternAndCircuitHash patternAndCircuitHash = getPatternAndCircuitHashFromRule(rule);
            log.debug(patternAndCircuitHash);
            if(patternAndCircuitHash != null){
                rulePatternToCircuitMapping.add(patternAndCircuitHash);
            } else {
                log.error("rule pattern and circuitHash could not be retrieved from rule " + rule.getUrlPattern());
            }
        }
    }

    public PatternAndCircuitHash getCircuitFromRequestUri(String requestUri){
        for (PatternAndCircuitHash mapping : rulePatternToCircuitMapping) {
            if(mapping.getPattern().matcher(requestUri).matches()){
                return mapping;
            }
        }
        return null;
    }

    private PatternAndCircuitHash getPatternAndCircuitHashFromRule(Rule rule){
        try {
            Pattern pattern = Pattern.compile(rule.getUrlPattern());
            String circuitHash = HashCodeGenerator.createHashCode(rule.getUrlPattern());
            return new PatternAndCircuitHash(pattern, circuitHash);
        } catch (Exception e) {
            log.error("Could not compile the regex:" + rule.getUrlPattern() + " to a pattern.");
            return null;
        }
    }
}

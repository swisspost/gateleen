package org.swisspush.gateleen.queue.queuing.circuitbreaker;

import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.swisspush.gateleen.core.util.HashCodeGenerator;
import org.swisspush.gateleen.routing.Rule;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Helper class to map {@link Rule} objects to {@link PatternAndCircuitHash} objects.
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class QueueCircuitBreakerRulePatternToCircuitMapping {

    private Logger log = LoggerFactory.getLogger(QueueCircuitBreakerRulePatternToCircuitMapping.class);

    private List<PatternAndCircuitHash> rulePatternToCircuitMapping = new ArrayList<>();

    /**
     * Updates the mapping with the provided routing rules. Returns a list of {@link PatternAndCircuitHash} objects which have
     * been removed with the provided list of rules.
     * <pre>Example
     * current mapping: [{pattern1,hash1}, {pattern2,hash2}, {pattern3,hash3}]
     * new routing rules: [{pattern1}, {pattern3}, {pattern4}]
     * return value: [{pattern2,hash2}]
     * </pre>
     * @param rules the list of routing rules to update the mapping with
     * @return a list of removed {@link PatternAndCircuitHash} objects
     */
    List<PatternAndCircuitHash> updateRulePatternToCircuitMapping(List<Rule> rules){
        List<PatternAndCircuitHash> originalPatternAndCircuitHashes = new ArrayList<>(rulePatternToCircuitMapping);
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
        return getRemovedPatternAndCircuitHashes(originalPatternAndCircuitHashes, rulePatternToCircuitMapping);
    }

    private List<PatternAndCircuitHash> getRemovedPatternAndCircuitHashes(List<PatternAndCircuitHash> currentPatternAndCircuitHashes,
                                                                          List<PatternAndCircuitHash> newPatternAndCircuitHashes){
        currentPatternAndCircuitHashes.removeAll(newPatternAndCircuitHashes);
        return currentPatternAndCircuitHashes;
    }

    PatternAndCircuitHash getCircuitFromRequestUri(String requestUri){
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

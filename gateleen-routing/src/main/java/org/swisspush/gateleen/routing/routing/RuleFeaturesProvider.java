package org.swisspush.gateleen.routing.routing;

import org.swisspush.gateleen.routing.routing.RuleFeatures.Feature;
import com.google.common.collect.ImmutableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Pattern;

import static org.swisspush.gateleen.routing.routing.RuleFeatures.Feature.STORAGE_EXPAND;
import static org.swisspush.gateleen.routing.routing.RuleFeatures.Feature.EXPAND_ON_BACKEND;

/**
 * Class RuleFeaturesProvider.
 * This class extracts features from {@link Rule} objects and collect them in a List of {@link RuleFeatures} objects.
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public final class RuleFeaturesProvider {

    private Logger log = LoggerFactory.getLogger(RuleFeaturesProvider.class);
    List<RuleFeatures> featuresList;

    public RuleFeaturesProvider(List<Rule> rules) {
        featuresList = collectRuleFeatures(rules);
    }

    public List<RuleFeatures> getFeaturesList() {
        if(featuresList == null){
            featuresList = new ArrayList<>();
        }
        return featuresList;
    }

    /**
     * Checks the whether the provided uri matches the provided {@link Feature}.
     *
     * @param feature the feature to check against
     * @param uri the uri to check
     * @return returns true when the uri matches the feature, else returns false.
     */
    public boolean isFeatureRequest(Feature feature, String uri){
        for (RuleFeatures features : getFeaturesList()) {
            if(features.getUrlPattern().matcher(uri).matches()){
                if(features.hasFeature(feature)){
                    log.debug(String.format("Don't expand the uri: %s for the pattern %s since it's a %s request", uri, features.getUrlPattern().pattern(), feature.name()));
                    return true;
                }
                return false;
            }
        }
        return false;
    }

    private List<RuleFeatures> collectRuleFeatures(List<Rule> rules) {
        List<RuleFeatures> featuresList = new ArrayList<>();
        for (Rule rule : rules) {
            boolean isStorageExpand = rule.isStorageExpand();
            boolean isExpandOnBackend = rule.isExpandOnBackend();
            try {
                Pattern pattern = Pattern.compile(rule.getUrlPattern());
                featuresList.add(new RuleFeatures(pattern, ImmutableMap.of(
                        STORAGE_EXPAND, isStorageExpand,
                        EXPAND_ON_BACKEND, isExpandOnBackend)));
                log.info(String.format("Collected features for rule url pattern %s storageExpand:%s expandOnBackend:%s", rule.getUrlPattern(), isStorageExpand, isExpandOnBackend));
            } catch (Exception e) {
                log.error("Could not compile the regex:" + rule.getUrlPattern() + " to a pattern. Cannot collect feature information of this rule");
            }
        }
        return featuresList;
    }
}

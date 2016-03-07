package org.swisspush.gateleen.routing;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Class RuleFeatures.
 * This class is a container for the features of a single {@link Rule} object.
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public final class RuleFeatures {
    private final Pattern urlPattern;
    private Map<Feature, Boolean> features;

    public enum Feature {
        STORAGE_EXPAND, EXPAND_ON_BACKEND
    }

    /**
     * Constructor of the {@link RuleFeatures} class
     * @param urlPattern The pattern of the url. You get this from the {@link Rule}
     * @param features A map of {@link Feature} entries
     */
    public RuleFeatures(Pattern urlPattern, Map<Feature, Boolean> features) {
        this.urlPattern = urlPattern;
        this.features = features;
    }

    public Pattern getUrlPattern() {
        return urlPattern;
    }

    public Map<Feature, Boolean> getFeatures() {
        if(features == null){
            features = new HashMap<>();
        }
        return features;
    }

    /**
     * Returns a boolean value whether the provided feature is active or not.
     * When the provided feature is not configured, Boolean.FALSE will be returned.
     *
     * @param feature The feature to check
     * @return A boolean value whether the provided feature is active or not
     */
    public Boolean hasFeature(Feature feature){
        Boolean result = getFeatures().get(feature);
        if(result == null){
            result = Boolean.FALSE;
        }
        return result;
    }
}

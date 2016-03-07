package org.swisspush.gateleen.validation.validation;

import java.util.*;

/**
 * Class representing the json validation resource
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class ValidationResource {

    public static final String URL_PROPERTY = "url";
    public static final String METHOD_PROPERTY = "method";

    private List<Map<String, String>> resources = new ArrayList<>();

    /**
     * Returns a list of resources
     * 
     * @return list of resources
     */
    public final List<Map<String, String>> getResources() {
        if (resources == null) {
            resources = new ArrayList<>();
            resources.add(new HashMap<>());
        }
        return resources;
    }

    /**
     * Clears all validation resources
     */
    public void reset() {
        getResources().clear();
    }

    /**
     * Adds a resource to the resources
     * 
     * @param resource resource to add
     */
    public void addResource(Map<String, String> resource) {
        getResources().add(resource);
    }

}

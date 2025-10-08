package org.swisspush.gateleen.core.util;

import com.floreysoft.jmte.DefaultModelAdaptor;
import com.floreysoft.jmte.Engine;
import com.floreysoft.jmte.ErrorHandler;
import com.floreysoft.jmte.TemplateContext;
import com.floreysoft.jmte.token.Token;

import java.util.List;
import java.util.Map;

/**
 * <p>
 * Utility class providing handy methods to deal with Properties.
 * </p>
 */
public final class PropertyUtils {

    private PropertyUtils() {
        // prevent instantiation
    }

    /**
     * Checks whether a property with the given key exists in the given properties map and whether its value is true.
     *
     * @param properties The map of property objects to be checked.
     * @param key        The key of the property to be checked.
     * @return true if the property exists as Boolean or String and its value is true, false otherwise.
     */
    public static boolean isPropertyTrue(Map<String, Object> properties, String key) {
        if (properties != null) {
            return isObjectPropertyTrue(properties.get(key));
        }
        return false;
    }

    /**
     * Checks whether the given value is either of type Boolean or String and its value is true.
     *
     * @param value The value Object to be checked.
     * @return true if the value object given as String or Boolean is true, false otherwise.
     */
    public static boolean isObjectPropertyTrue(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        } else if (value instanceof String) {
            return Boolean.parseBoolean((String) value);
        }
        return false;
    }

}

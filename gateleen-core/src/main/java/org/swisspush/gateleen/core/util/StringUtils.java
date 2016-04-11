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
 * Utility class providing handy methods to deal with Strings.
 * </p>
 * 
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public final class StringUtils {

    private StringUtils() {
        // prevent instantiation
    }

    /**
     * <p>
     * Checks if a CharSequence is empty ("") or null.
     * </p>
     * 
     * <pre>
     * StringUtils.isEmpty(null)      = true
     * StringUtils.isEmpty("")        = true
     * StringUtils.isEmpty(" ")       = false
     * StringUtils.isEmpty("bob")     = false
     * StringUtils.isEmpty("  bob  ") = false
     * </pre>
     * 
     * @param cs the CharSequence to check, may be null
     * @return {@code true} if the CharSequence is empty or null
     */
    public static boolean isEmpty(CharSequence cs) {
        return cs == null || cs.length() == 0;
    }

    /**
     * <p>
     * Checks if a CharSequence is not empty ("") and not null.
     * </p>
     * 
     * <pre>
     * StringUtils.isNotEmpty(null)      = false
     * StringUtils.isNotEmpty("")        = false
     * StringUtils.isNotEmpty(" ")       = true
     * StringUtils.isNotEmpty("bob")     = true
     * StringUtils.isNotEmpty("  bob  ") = true
     * </pre>
     * 
     * @param cs the CharSequence to check, may be null
     * @return {@code true} if the CharSequence is not empty and not null
     */
    public static boolean isNotEmpty(CharSequence cs) {
        return !StringUtils.isEmpty(cs);
    }

    /**
     * <p>
     * Checks if a trimmed String is not empty ("") and not null.
     * </p>
     *
     * <pre>
     * StringUtils.isNotEmpty(null)      = false
     * StringUtils.isNotEmpty("")        = false
     * StringUtils.isNotEmpty(" ")       = false
     * StringUtils.isNotEmpty("bob")     = true
     * StringUtils.isNotEmpty("  bob  ") = true
     * </pre>
     *
     * @param str the String to check, may be null
     * @return {@code true} if the String is not empty and not null
     */
    public static boolean isNotEmptyTrimmed(String str) {
        return !StringUtils.isEmpty(trim(str));
    }

    /**
     * <p>
     * Removes control characters (char &lt;= 32) from both ends of this String, handling {@code null} by returning {@code null}.
     * </p>
     * <p>
     * The String is trimmed using {@link String#trim()}. Trim removes start and end characters &lt;= 32.
     * </p>
     * 
     * <pre>
     * StringUtils.trim(null)          = null
     * StringUtils.trim("")            = ""
     * StringUtils.trim("     ")       = ""
     * StringUtils.trim("abc")         = "abc"
     * StringUtils.trim("    abc    ") = "abc"
     * </pre>
     * 
     * @param str the String to be trimmed, may be null
     * @return the trimmed string, {@code null} if null String input
     */
    public static String trim(String str) {
        return str == null ? null : str.trim();
    }

    /**
     * <p>
     * Returns a trimmed String containing the provided value or an empty ("") String.
     * </p>
     * 
     * <pre>
     * StringUtils.getStringOrEmpty(null)      = ""
     * StringUtils.getStringOrEmpty("")        = ""
     * StringUtils.getStringOrEmpty(" ")       = ""
     * StringUtils.getStringOrEmpty("bob")     = "bob"
     * StringUtils.getStringOrEmpty("  bob  ") = "bob"
     * </pre>
     * 
     * @param inputString the String to check, may be null
     * @return the trimmed inputString or an empty ("") String
     */
    public static String getStringOrEmpty(String inputString) {
        if (isEmpty(inputString)) {
            return "";
        } else {
            return trim(inputString);
        }
    }

    /**
     * <p>
     * Returns a trimmed String containing the provided value or the provided default String.
     * </p>
     *
     * <pre>
     * StringUtils.getStringOrDefault(null, "bob")        = "bob"
     * StringUtils.getStringOrDefault("", "bob")          = "bob"
     * StringUtils.getStringOrDefault(" ", "bob")         = "bob"
     * StringUtils.getStringOrDefault("alice", "bob")     = "alice"
     * StringUtils.getStringOrDefault("  alice  ", "bob") = "alice"
     * </pre>
     *
     * @param inputString the String to check, may be null
     * @param def the default value to return when inputString is null or empty
     * @return the trimmed inputString or the provided default value
     */
    public static String getStringOrDefault(String inputString, String def) {
        String stringOrEmpty = getStringOrEmpty(inputString);
        if (isEmpty(stringOrEmpty)) {
            return def;
        } else {
            return stringOrEmpty;
        }
    }

    /**
     * Returns a String with replaced wildcard values from the provided properties.
     * See example below:
     * <pre>
     * <b>Input:</b>
     * contentWithWildcards: "This is a very ${adjective} helper method"
     * properties: com.google.common.collect.ImmutableMap.of("adjective", "nice")
     *
     * <b>Output:</b>
     * "This is a very nice helper method"
     * </pre>
     *
     * @param contentWithWildcards the String containing the wildcards to replace
     * @param properties the properties with the replacement values for the wildcards
     * @return the String with replaced wildcard values. Returns the input String when input String or properties are <code>null</code>
     */
    public static String replaceWildcardConfigs(String contentWithWildcards, Map<String, Object> properties) {
        if(properties == null || contentWithWildcards == null) {
            return contentWithWildcards;
        }
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
            return engine.transform(contentWithWildcards, properties);
        } catch (com.floreysoft.jmte.message.ParseException e) {
            throw new IllegalArgumentException(e);
        }
    }
}

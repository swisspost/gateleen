package org.swisspush.gateleen.core.util;

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
}

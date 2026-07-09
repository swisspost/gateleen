package org.swisspush.gateleen.logging;

import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpServerRequest;
import org.slf4j.Logger;
import org.swisspush.gateleen.core.http.RequestLoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Class RequestPropertyFilter provides methods to filterProperty requests.
 *
 * Updated to accept precompiled regex patterns.
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class RequestPropertyFilter {

    public static final String URL = "url";
    public static final String METHOD = "method";

    /**
     * Check the provided request against the filterProperty values (key, pattern) and return a {@link FilterResult} defining
     * whether to filterProperty the request or not.
     *
     * @param request the request to be checked to filterProperty or not
     * @param filterPropertyKey the key of the filterProperty e.g. url, method
     * @param filterPropertyPattern the precompiled regex pattern of the filterProperty
     * @param reject boolean value from the filterProperty entry called "reject"
     * @return the {@link FilterResult} for the provided request
     */
    public static FilterResult filterProperty(HttpServerRequest request, String filterPropertyKey, Pattern filterPropertyPattern, boolean reject) {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        headers.setAll(request.headers());

        if (URL.equals(filterPropertyKey)) {
            boolean matches = filterRequestURL(request, filterPropertyPattern);
            FilterResult result = rejectIfNeeded(reject, matches);
            logFilterResult(request, filterPropertyKey, filterPropertyPattern.pattern(), result);
            return result;
        }
        if (METHOD.equals(filterPropertyKey)) {
            boolean matches = filterRequestMethod(request, filterPropertyPattern);
            FilterResult result = rejectIfNeeded(reject, matches);
            logFilterResult(request, filterPropertyKey, filterPropertyPattern.pattern(), result);
            return result;
        }
        if (headers.names().contains(filterPropertyKey) && filterPropertyPattern.matcher(headers.get(filterPropertyKey)).matches()) {
            FilterResult result = reject ? FilterResult.REJECT : FilterResult.FILTER;
            logFilterResult(request, filterPropertyKey, filterPropertyPattern.pattern(), result);
            return result;
        }
        logFilterResult(request, filterPropertyKey, filterPropertyPattern.pattern(), FilterResult.REJECT, true);
        return FilterResult.REJECT;
    }

    private static FilterResult rejectIfNeeded(boolean reject, boolean matches) {
        if (!matches) {
            return FilterResult.NO_MATCH;
        }
        return reject ? FilterResult.REJECT : FilterResult.FILTER;
    }

    private static boolean filterRequestURL(HttpServerRequest request, Pattern urlPattern) {
        Matcher urlMatcher = urlPattern.matcher(request.uri());
        return urlMatcher.matches();
    }

    private static boolean filterRequestMethod(HttpServerRequest request, Pattern methodPattern) {
        Matcher methodMatcher = methodPattern.matcher(request.method().toString());
        return methodMatcher.matches();
    }

    private static void logFilterResult(HttpServerRequest request, String filterPropertyKey, String filterPropertyValue, FilterResult filterResult) {
        logFilterResult(request, filterPropertyKey, filterPropertyValue, filterResult, false);
    }

    private static void logFilterResult(HttpServerRequest request, String filterPropertyKey, String filterPropertyValue, FilterResult filterResult, boolean noMatchingProperty) {
        if (FilterResult.NO_MATCH != filterResult) {
            Logger log = RequestLoggerFactory.getLogger(RequestPropertyFilter.class, request);
            if (!log.isInfoEnabled()) return;
            StringBuilder sb = new StringBuilder("Request to ").append(request.uri());
            if (noMatchingProperty) {
                sb.append(" with no matching filterProperty");
            } else {
                sb.append(" with filterProperty ").append(filterPropertyKey).append("=").append(filterPropertyValue);
            }
            sb.append(" has FilterResult ").append(filterResult.name());
            log.info("{}", sb);
        }
    }
}

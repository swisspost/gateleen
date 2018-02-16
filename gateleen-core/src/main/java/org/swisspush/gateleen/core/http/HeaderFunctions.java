package org.swisspush.gateleen.core.http;

import io.vertx.core.MultiMap;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Helper class to create function chains for (Http) header (see {@link io.vertx.core.MultiMap}) manipulations
 *
 * function chains can be created programmatically or via JSON config.
 * Apply the function chain on a MultiMap with {@code headers=chain.apply(headers);}
 */
public class HeaderFunctions {

    private static final Logger LOG = LoggerFactory.getLogger(HeaderFunctions.class);

    public static final Function<MultiMap, MultiMap> DO_NOTHING = (header) -> header;


    private static final Pattern VARIABLE_PATTERN = Pattern.compile("[{](.+?)[}]"); // Search variable, pattern "[xyz}"

    /**
     * Example configuration (a JSON array):
     * <pre>
     * {@code [
     *     { "header": "xxx"    , "value": "111"                                },
     *     { "header": "yyy"    , "value": "222"                                },
     *     { "header": "yyy"    , "value": "333"           , "mode": "complete" },
     *     { "header": "zzz"    , "value": "444"           , "mode": "override" },
     *     { "header": "oli"    , "value": "{xxx}-{yyy}"                        },
     *     { "header": "xxx"    , "value": null                                 },
     *     { "header": "preSuff", "value": "pre-{yyy}-suff"                     }
     *   ]
     * }
     * </pre>
     *
     * Notes:
     * <ul>
     *   <li>explicit set <code>{@code "value" : null}</code> to remove a header</li>
     *   <li>mode "complete": set header only if <b>not yet</b> present</li>
     *   <li>mode "override" (not over<i>write</i>): set header only if <b>already present</b> (i.e. replace it)</li>
     *   <li>mode absent (default): set header always, i.e. setting if not yet present, overriding if present</li>
     *   <lI>use angular braces to reference other header values</lI>
     * </ul>
     *
     * @param config the JSON configuration for the header manipulator chain
     */
    public static Function<MultiMap, MultiMap> parseFromJson(JsonArray config) throws IllegalArgumentException {
        LOG.debug("creating header function chain from " + config);

        Function<MultiMap, MultiMap> chain = null;
        for (int pos = 0; pos < config.size(); pos++) {
            JsonObject rule = config.getJsonObject(pos);
            Function<MultiMap, MultiMap> f = getMultiMapMultiMapFunction(config, rule);

            if (chain == null) {
                chain = f;
            } else {
                chain = chain.andThen(f);
            }
        }
        if (chain == null) {
            // fallback if no rules defined: fast 'identity' function
            chain = (headers) -> headers;
        }
        return chain;
    }

    private static Function<MultiMap, MultiMap> getMultiMapMultiMapFunction(JsonArray config, JsonObject rule) throws IllegalArgumentException {
        String headerName = rule.getString("header");
        String expression = rule.getString("value");
        String mode       = rule.getString("mode");

        if (headerName == null) {
            throw new IllegalArgumentException("missing attribute \"header\" in " + rule + " (part of " + config + ")");
        }
        // note "value" must be present. If null this implicit means 'remove this header'
        if (!rule.containsKey("value")) {
            throw new IllegalArgumentException("missing attribute \"value\" in " + rule + " (part of " + config + ")");
        }

        if (expression == null) {
            return remove(headerName);
        }
        if (mode == null) {
            return setAlways(headerName, expression);
        }
        if ("complete".equals(mode)) {
            return setIfAbsent(headerName, expression);
        }
        if ("override".equals(mode)) {
            return setIfPresent(headerName, expression);
        }
        throw new IllegalArgumentException("illegal value for \"mode\" in " + rule + " (part of " + config + "). Only \"complete\" or \"override\" allowed - or no \"mode\" at all");
    }


    public static Function<MultiMap, MultiMap> setAlways(String headerName, String expression) {
        final Function<MultiMap, String> exprEval = newExpressionEvaluator(expression);
        return (headers) -> {
            String value = exprEval.apply(headers);
            headers.set(headerName, value);
            return headers;
        };
    }

    public static Function<MultiMap, MultiMap> setIfPresent(String headerName, String expression) {
        final Function<MultiMap, String> exprEval = newExpressionEvaluator(expression);
        return (headers) -> {
            if (headers.contains(headerName)) {
                String value = exprEval.apply(headers);
                headers.set(headerName, value);
            }
            return headers;
        };
    }

    public static Function<MultiMap, MultiMap> setIfAbsent(String headerName, String expression) {
        final Function<MultiMap, String> exprEval = newExpressionEvaluator(expression);
        return (headers) -> {
            if (!headers.contains(headerName)) {
                String value = exprEval.apply(headers);
                headers.set(headerName, value);
            }
            return headers;
        };
    }

    public static Function<MultiMap, MultiMap> remove(String headerName) {
        return (headers) -> {
            headers.remove(headerName);
            return headers;
        };
    }

    private static Function<MultiMap, String> newExpressionEvaluator(String expression) {
        // return fast 'identity' function if there are no variables in the expression
        if (expression.indexOf('{') < 0) {
            return (headers) -> expression;
        }

        Function<EvalScope, EvalScope> f = (scope) -> scope;
        Matcher matcher = VARIABLE_PATTERN.matcher(expression);
        int idx = 0;
        while (matcher.find()) {
            int from = matcher.start();
            int to = matcher.end();
            String fix = expression.substring(idx, from); // can be empty string - optimized in andThenFix()
            f = andThenFix(f, fix);
            String varName = matcher.group(1); // This is the variable name without curly braces
            f = andThenVar(f, varName);
            idx = to;
        }
        String fix = expression.substring(idx);
        f = andThenFix(f, fix);

        final Function<EvalScope, EvalScope> evaluatorChain = f;
        return (headers) -> {
            LOG.debug("evaluating '{}' on headers '{}'", expression, headers);
            EvalScope scope = new EvalScope();
            scope.headers = headers;
            scope.expression = expression;
            evaluatorChain.apply(scope);
            String result = scope.sb.toString();
            LOG.debug("evaluating '{}' on headers '{}' results to '{}'", expression, headers, result);
            return result;
        };
    }

    private static Function<EvalScope, EvalScope> andThenFix(Function<EvalScope, EvalScope> f, String fix) {
        if (fix.length() == 0) {
            return f;
        }
//        System.out.println("Fix: " + fix);
        return f.andThen((scope) -> {
            scope.sb.append(fix);
            return scope;
        });
    }

    private static Function<EvalScope, EvalScope> andThenVar(Function<EvalScope, EvalScope> f, String varName) {
//        System.out.println("Var: " + varName);
        return f.andThen((scope) -> {
            String value = scope.headers.get(varName);
            if (value == null) {
                throw new HeaderNotFoundException("Header with name " + varName + " (from expression '" + scope.expression + "') not found in '" + scope.headers + "'");
            } else {
                scope.sb.append(value);
            }
            return scope;
        });
    }

    private static class EvalScope {
        StringBuilder sb = new StringBuilder();
        MultiMap headers;
        String expression; // needed only for debugging purposes
    }

    public static class HeaderNotFoundException extends RuntimeException {
        HeaderNotFoundException(String msg) {
            super(msg);
        }
    }
}

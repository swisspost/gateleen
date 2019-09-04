package org.swisspush.gateleen.core.http;

import io.vertx.core.MultiMap;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.function.Consumer;
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

    private static final EvalScope NO_ERROR_SCOPE = new EvalScope(null);

    public static final HeaderFunction DO_NOTHING = (headers) -> NO_ERROR_SCOPE;


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
    public static HeaderFunction parseFromJson(JsonArray config) throws IllegalArgumentException {
        LOG.debug("creating header function chain from " + config);

        Consumer<EvalScope> chain = null;
        for (int pos = 0; pos < config.size(); pos++) {
            JsonObject rule = config.getJsonObject(pos);
            Consumer<EvalScope> c = parseOneFromJason(config, rule);
            chain = (chain == null) ? c : chain.andThen(c);
        }
        return wrapConsumerChain(chain);
    }

    /**
     * Support for the legacy "staticHeaders" syntax
     */
    @Deprecated
    public static HeaderFunction parseStaticHeadersFromJson(JsonObject staticHeaders) {
        Consumer<HeaderFunctions.EvalScope> chain = null;
        for (Map.Entry<String, Object> entry : staticHeaders.getMap().entrySet()) {
            String headerName = entry.getKey();
            Object value = entry.getValue();
            Consumer<HeaderFunctions.EvalScope> c;
            if (value == null || value.toString().isEmpty()) {
                c = HeaderFunctions.remove(headerName);
            } else {
                c = HeaderFunctions.setAlways(headerName, value.toString());
            }
            chain = (chain == null) ? c : chain.andThen(c);
        }
        return wrapConsumerChain(chain);
    }

    private static HeaderFunction wrapConsumerChain(Consumer<EvalScope> chain) {
        if (chain == null) {
            // if no rules defined: do nothing and just return a fix 'no error' indicating result
            return DO_NOTHING;
        }
        return (headers) -> {
            EvalScope scope = new EvalScope(headers);
            chain.accept(scope);
            return scope;
        };
    }

    private static Consumer<EvalScope> parseOneFromJason(JsonArray config, JsonObject rule) throws IllegalArgumentException {
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

        if (expression == null || expression.length() == 0) {
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


    public static Consumer<EvalScope> remove(String headerName) {
        return (scope) -> {
            scope.headers.remove(headerName);
        };
    }

    public static Consumer<EvalScope> setAlways(String headerName, String expression) {
        final Function<EvalScope, String> exprEval = newExpressionEvaluator(expression);
        return (scope) -> {
            String value = exprEval.apply(scope);
            scope.headers.set(headerName, value);
        };
    }

    public static Consumer<EvalScope> setIfAbsent(String headerName, String expression) {
        final Function<EvalScope, String> exprEval = newExpressionEvaluator(expression);
        return (scope) -> {
            if (!scope.headers.contains(headerName)) {
                String value = exprEval.apply(scope);
                scope.headers.set(headerName, value);
            }
        };
    }

    public static Consumer<EvalScope> setIfPresent(String headerName, String expression) {
        final Function<EvalScope, String> exprEval = newExpressionEvaluator(expression);
        return (scope) -> {
            if (scope.headers.contains(headerName)) {
                String value = exprEval.apply(scope);
                scope.headers.set(headerName, value);
            }
        };
    }

    private static Function<EvalScope, String> newExpressionEvaluator(String expression) {
        // return fast 'identity' function if there are no variables in the expression
        if (expression.indexOf('{') < 0) {
            return (scope) -> expression;
        }

        Consumer<EvalScope> chain = (scope) -> {};
        Matcher matcher = VARIABLE_PATTERN.matcher(expression);
        int idx = 0;
        while (matcher.find()) {
            int from = matcher.start();
            int to = matcher.end();
            String fix = expression.substring(idx, from); // can be empty string - optimized in andThenFix()
            chain = andThenFix(chain, fix);
            String varName = matcher.group(1); // This is the variable name without curly braces
            chain = andThenVar(chain, varName, expression);
            idx = to;
        }
        String fix = expression.substring(idx);
        chain = andThenFix(chain, fix);

        final Consumer<EvalScope> finalEvaluatorChain = chain;
        return (scope) -> {
            LOG.debug("evaluating '{}' on headers '{}'", expression, scope.headers);
            scope.initBuffer();
            finalEvaluatorChain.accept(scope);
            String result = scope.sb.toString();
            LOG.debug("evaluating '{}' on headers '{}' results to '{}'", expression, scope.headers, result);
            return result;
        };
    }

    private static Consumer<EvalScope> andThenFix(Consumer<EvalScope> c, String fix) {
        if (fix.length() == 0) {
            return c;
        }
        return c.andThen((scope) -> {
            scope.sb.append(fix);
        });
    }

    private static Consumer<EvalScope> andThenVar(Consumer<EvalScope> c, String varName, String expression) {
        return c.andThen((scope) -> {
            String value = scope.headers.get(varName);
            if (value == null) {
                scope.sb.append('{').append(varName).append('}');
                scope.errorMessage = "unresolvable '{" + varName + "}' in expression '" + expression + "'";
            } else {
                scope.sb.append(value);
            }
        });
    }

    public static class EvalScope {
        public final MultiMap headers;
        private StringBuilder sb;
        private String errorMessage;

        EvalScope(MultiMap headers) {
            this.headers = headers;
        }

        private void initBuffer() {
            if (sb == null) {
                sb = new StringBuilder();
            } else {
                sb.setLength(0);
            }
        }

        public String getErrorMessage() {
            return errorMessage;
        }
    }
}

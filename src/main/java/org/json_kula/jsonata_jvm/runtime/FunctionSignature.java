package org.json_kula.jsonata_jvm.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses JSONata function signature strings and coerces argument lists to match.
 *
 * <h2>Signature format</h2>
 * <p>{@code <params:return>} where {@code params} is a sequence of type specs
 * and {@code return} is a single type symbol (may be absent).
 *
 * <h2>Coercions applied</h2>
 * <ul>
 *   <li>{@code n} — number: string digits are parsed; booleans become 0/1</li>
 *   <li>{@code s} — string: any scalar is stringified</li>
 *   <li>{@code b} — boolean: any value is cast via JSONata truthy rules</li>
 *   <li>{@code l} — null: value must be null or an exception is thrown</li>
 *   <li>{@code a} — array: a non-array scalar is wrapped in a single-element array</li>
 *   <li>{@code o} — object: value must be an object or an exception is thrown</li>
 *   <li>{@code f} — function: value must be a function value ({@link LambdaNode}) or T0410 is
 *       thrown. A parametrised form ({@code f<n:n>}) parses, but the parameter and return types of
 *       the argument function are not checked — nor are they in jsonata-js.</li>
 *   <li>{@code x} — any type at all, functions included; accepted without coercion</li>
 *   <li>{@code j}, {@code u}, unions, parametrised arrays — accepted without coercion</li>
 * </ul>
 *
 * <p>Note that {@code j} is documented by the JSONata spec as excluding functions, but is accepted
 * here without that check: a function value has always been able to reach a bound function declaring
 * {@code j}, and tightening it would break working code for no gain. Declare {@code f} to require a
 * function.</p>
 *
 * <h2>Modifiers</h2>
 * <ul>
 *   <li>{@code +} — variadic: the type spec matches one or more trailing arguments</li>
 *   <li>{@code ?} — optional: if missing, {@code MissingNode} is passed through</li>
 *   <li>{@code -} — focus: like optional; the caller supplies the context value if missing</li>
 * </ul>
 *
 * <p>If the signature cannot be parsed the argument list is passed through unchanged.
 */
final class FunctionSignature {

    private FunctionSignature() {}

    private static final JsonNodeFactory NF = JsonNodeFactory.instance;

    // =========================================================================
    // Public entry point
    // =========================================================================

    /**
     * Validates and coerces {@code supplied} against the parameter specs in
     * {@code signature}, returning the coerced list ready for
     * {@link org.json_kula.jsonata_jvm.JsonataFunctionArguments}.
     *
     * <p>If the signature is null, empty, or malformed, {@code supplied} is
     * returned unchanged so that bound functions that don't care about strict
     * typing continue to work.
     *
     * @throws RuntimeEvaluationException if a required argument is missing or
     *                                    a value cannot be coerced to the declared type
     */
    static List<JsonNode> coerce(String signature, List<JsonNode> supplied)
            throws RuntimeEvaluationException {
        List<ParamSpec> params = parseParams(signature);
        if (params == null) return supplied;

        List<JsonNode> result = new ArrayList<>();
        int argIdx = 0;

        for (ParamSpec p : params) {
            if (p.variadic()) {
                // Consume all remaining args (at least one required unless marked optional).
                if (argIdx >= supplied.size()) {
                    if (!p.acceptsMissing()) {
                        throw new RuntimeEvaluationException(null,
                                "Expected at least one argument of type \"" + p.type() + "\"");
                    }
                    break;
                }
                while (argIdx < supplied.size()) {
                    result.add(coerceOne(p.type(), supplied.get(argIdx++)));
                }
            } else {
                JsonNode arg = argIdx < supplied.size()
                        ? supplied.get(argIdx++)
                        : JsonataRuntime.MISSING;
                if (arg.isMissingNode()) {
                    if (p.acceptsMissing()) {
                        result.add(JsonataRuntime.MISSING);
                    } else {
                        throw new RuntimeEvaluationException(null,
                                "Missing required argument of type \"" + p.type() + "\"");
                    }
                } else {
                    result.add(coerceOne(p.type(), arg));
                }
            }
        }

        return result;
    }

    // =========================================================================
    // Type coercion
    // =========================================================================

    private static JsonNode coerceOne(String type, JsonNode value)
            throws RuntimeEvaluationException {
        if (value.isMissingNode()) return value;
        // "f" and its parametrised form "f<n:n>" impose the same check: the parameter and return
        // types of the argument function are not verified (jsonata-js does not verify them either).
        if (type.charAt(0) == 'f') {
            if (!(value instanceof LambdaNode))
                throw new RuntimeEvaluationException("T0410",
                        "Expected function argument, got " + value.getNodeType());
            return value;
        }
        return switch (type) {
            case "n" -> NF.numberNode(JsonataRuntime.toNumber(value));
            case "s" -> NF.textNode(JsonataRuntime.toText(value));
            case "b" -> NF.booleanNode(JsonataRuntime.isTruthy(value));
            case "l" -> {
                if (!value.isNull())
                    throw new RuntimeEvaluationException(null,
                            "Expected null argument, got " + value.getNodeType());
                yield value;
            }
            case "a" -> value.isArray() ? value : NF.arrayNode().add(value);
            case "o" -> {
                if (!value.isObject())
                    throw new RuntimeEvaluationException(null,
                            "Expected object argument, got " + value.getNodeType());
                yield value;
            }
            // j (any JSON), u (primitive union), x (any), union types (sao) etc.,
            // parametrised arrays a<x> — accept without coercion
            default -> value;
        };
    }

    // =========================================================================
    // Signature parsing
    // =========================================================================

    /**
     * Descriptor for a single parameter position extracted from a signature.
     *
     * @param type      the base type string (e.g. {@code "n"}, {@code "(sao)"}, {@code "a<s>"})
     * @param optional  {@code true} when the {@code ?} modifier is present
     * @param focus     {@code true} when the {@code -} modifier is present (use context if absent)
     * @param variadic  {@code true} when the {@code +} modifier is present
     */
    record ParamSpec(String type, boolean optional, boolean focus, boolean variadic) {
        boolean acceptsMissing() { return optional || focus; }
    }

    /**
     * Parses the params portion of {@code signature} into a list of
     * {@link ParamSpec}s, or returns {@code null} if the signature is null,
     * too short, or malformed.
     */
    static List<ParamSpec> parseParams(String signature) {
        if (signature == null || signature.length() < 2) return null;
        if (signature.charAt(0) != '<' || signature.charAt(signature.length() - 1) != '>')
            return null;

        // Strip outer < >
        String inner = signature.substring(1, signature.length() - 1);
        // Find the ':' separating params from return type (not inside nested brackets).
        int colon = findTopLevelColon(inner);
        String paramStr = colon >= 0 ? inner.substring(0, colon) : inner;

        return parseParamStr(paramStr);
    }

    /**
     * Returns the index of the {@code '>'} closing the {@code '<'} at {@code open}, or -1 if it is
     * unbalanced.
     */
    private static int matchAngle(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<') depth++;
            else if (c == '>' && --depth == 0) return i;
        }
        return -1;
    }

    /**
     * The number of arguments a function declaring {@code signature} takes, or
     * {@link LambdaNode#UNKNOWN_ARITY} when the signature does not pin it down — absent,
     * unparseable, or variadic, since a {@code +} spec covers any number of positions.
     *
     * <p>Used to give a bound function an arity when it is handed out as a function value, which
     * decides how many arguments reach it (see {@link BoundFunctionValue}) and how much a built-in
     * higher-order function passes a callback.
     */
    static int arityOf(String signature) {
        List<ParamSpec> params = parseParams(signature);
        if (params == null) return LambdaNode.UNKNOWN_ARITY;
        for (ParamSpec p : params) {
            if (p.variadic()) return LambdaNode.UNKNOWN_ARITY;
        }
        return params.size();
    }

    /** Finds the index of ':' at nesting depth 0, or -1 if not found. */
    private static int findTopLevelColon(String s) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(' || c == '<') depth++;
            else if (c == ')' || c == '>') depth--;
            else if (c == ':' && depth == 0) return i;
        }
        return -1;
    }

    /**
     * Parses the raw params string (everything before the {@code :}) into a list
     * of {@link ParamSpec}s, or returns {@code null} on parse error.
     */
    private static List<ParamSpec> parseParamStr(String s) {
        List<ParamSpec> result = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            String type;
            int consumed;

            if (c == '(') {
                // Union type: consume until matching ')'.
                int end = s.indexOf(')', i);
                if (end < 0) return null;
                type = s.substring(i, end + 1);   // e.g. "(sao)"
                consumed = end + 1 - i;
            } else if ((c == 'a' || c == 'f') && i + 1 < s.length() && s.charAt(i + 1) == '<') {
                // Parametrised array or function: a<x>, f<n:n>. Matched by depth rather than by the
                // first '>' so that a nested parameter (a<a<n>>, f<f<n:n>:n>) is consumed whole.
                int end = matchAngle(s, i + 1);
                if (end < 0) return null;
                type = s.substring(i, end + 1);   // e.g. "a<s>", "f<n:n>"
                consumed = end + 1 - i;
            } else if ("bnslaoufjx".indexOf(c) >= 0) {
                type = String.valueOf(c);
                consumed = 1;
            } else {
                return null;  // unexpected character — treat as unparseable
            }

            i += consumed;

            boolean optional = false;
            boolean focus    = false;
            boolean variadic = false;
            if (i < s.length()) {
                char mod = s.charAt(i);
                if      (mod == '+') { variadic = true;  i++; }
                else if (mod == '?') { optional = true;  i++; }
                else if (mod == '-') { focus    = true;  i++; }
            }

            result.add(new ParamSpec(type, optional, focus, variadic));
        }
        return result;
    }
}

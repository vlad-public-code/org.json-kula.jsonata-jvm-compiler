package org.json_kula.jsonata_jvm.translator;

import org.json_kula.jsonata_jvm.runtime.FunctionSignature;

import org.json_kula.jsonata_jvm.parser.ast.AstNode;

import java.util.List;
import java.util.Map;

/**
 * The declared JSONata signature of each built-in, and the two questions the translator
 * asks of it.
 *
 * <p>These decide what {@code a.$fn(...)} means. JSONata does not give every built-in the
 * step context automatically: a parameter marked {@code -} is filled from the context when
 * the caller does not supply it, and a built-in without one is simply called with the
 * arguments written. That is why {@code "hi".$uppercase()} is {@code "HI"} — {@code <s-:s>}
 * — while {@code [1,2].$count()} is T0410 — {@code <a:n>} has no {@code -}, so
 * {@code $count} is invoked with no arguments at all.
 *
 * <p>The signatures are the reference implementation's own, read out of the {@code jsonata}
 * package rather than reconstructed from the documentation. Names this table does not know
 * are left alone by {@link #normalise}, so an unlisted built-in keeps whatever behaviour it
 * already had.
 */
final class BuiltinSignatures {

    private BuiltinSignatures() {}

    private static final Map<String, String> SIGNATURES = Map.ofEntries(
            Map.entry("abs", "<n-:n>"),
            Map.entry("append", "<xx:a>"),
            Map.entry("assert", "<bs?:x>"),
            Map.entry("average", "<a<n>:n>"),
            Map.entry("base64decode", "<s-:s>"),
            Map.entry("base64encode", "<s-:s>"),
            Map.entry("boolean", "<x-:b>"),
            Map.entry("ceil", "<n-:n>"),
            Map.entry("clone", "<(oa)-:o>"),
            Map.entry("contains", "<s-(sf):b>"),
            Map.entry("count", "<a:n>"),
            Map.entry("decodeUrl", "<s-:s>"),
            Map.entry("decodeUrlComponent", "<s-:s>"),
            Map.entry("distinct", "<x:x>"),
            Map.entry("encodeUrl", "<s-:s>"),
            Map.entry("encodeUrlComponent", "<s-:s>"),
            Map.entry("error", "<s?:x>"),
            Map.entry("eval", "<sx?:x>"),
            Map.entry("exists", "<x:b>"),
            Map.entry("filter", "<af>"),
            Map.entry("floor", "<n-:n>"),
            Map.entry("formatBase", "<n-n?:s>"),
            Map.entry("formatInteger", "<n-s:s>"),
            Map.entry("formatNumber", "<n-so?:s>"),
            Map.entry("fromMillis", "<n-s?s?:s>"),
            Map.entry("join", "<a<s>s?:s>"),
            Map.entry("keys", "<x-:a<s>>"),
            Map.entry("length", "<s-:n>"),
            Map.entry("lookup", "<x-s:x>"),
            Map.entry("lowercase", "<s-:s>"),
            Map.entry("map", "<af>"),
            Map.entry("match", "<s-f<s:o>n?:a<o>>"),
            Map.entry("max", "<a<n>:n>"),
            Map.entry("merge", "<a<o>:o>"),
            Map.entry("millis", "<:n>"),
            Map.entry("min", "<a<n>:n>"),
            Map.entry("not", "<x-:b>"),
            Map.entry("now", "<s?s?:s>"),
            Map.entry("number", "<(nsb)-:n>"),
            Map.entry("pad", "<s-ns?:s>"),
            Map.entry("parseInteger", "<s-s:n>"),
            Map.entry("power", "<n-n:n>"),
            Map.entry("random", "<:n>"),
            Map.entry("reduce", "<afj?:j>"),
            Map.entry("replace", "<s-(sf)(sf)n?:s>"),
            Map.entry("reverse", "<a:a>"),
            Map.entry("round", "<n-n?:n>"),
            Map.entry("shuffle", "<a:a>"),
            Map.entry("single", "<af?>"),
            Map.entry("sort", "<af?:a>"),
            Map.entry("split", "<s-(sf)n?:a<s>>"),
            Map.entry("spread", "<x-:a<o>>"),
            Map.entry("sqrt", "<n-:n>"),
            Map.entry("string", "<x-b?:s>"),
            Map.entry("substring", "<s-nn?:s>"),
            Map.entry("substringAfter", "<s-s:s>"),
            Map.entry("substringBefore", "<s-s:s>"),
            Map.entry("sum", "<a<n>:n>"),
            Map.entry("toMillis", "<s-s?:n>"),
            Map.entry("trim", "<s-:s>"),
            Map.entry("type", "<x:s>"),
            Map.entry("uppercase", "<s-:s>"),
            Map.entry("zip", "<a+>"));

    /**
     * Built-ins that already substitute the context in their own dispatch arm, and must
     * not have it substituted twice.
     *
     * <p>They are not merely redundant: several encode a decision a signature alone cannot
     * make. Whether {@code $fn(x)} passes {@code x} to the first parameter or slides it to
     * the second depends on {@code x}'s <em>type</em> — {@code $substringBefore("ca")}
     * substitutes the context and reports T0411, while {@code $split(12345)} does not,
     * because a number cannot be a separator and the argument itself is at fault (T0410).
     * Their arms make that call per built-in and the acceptance suite pins the results.
     *
     * <p>{@code each} and {@code sift} are here for a different reason: their generators
     * need the callback in AST form and keep their own argument bookkeeping.
     *
     * <p>{@code type} is deliberately absent even though its arm uses the context: its
     * signature {@code <x:s>} has no {@code -}, so {@code o.$type()} is T0410, and the arm
     * was substituting a context the built-in never asked for.
     */
    private static final java.util.Set<String> SELF_MANAGED = java.util.Set.of(
            "string", "number", "boolean", "not", "floor", "ceil", "round", "abs", "sqrt",
            "uppercase", "lowercase", "trim", "length", "substringBefore", "substringAfter",
            "contains", "match", "replace", "eval", "base64encode", "base64decode",
            "fromMillis", "error", "each", "sift");

    /** The declared signature, or {@code null} for a name this table does not carry. */
    static String signatureOf(String name) {
        return SIGNATURES.get(name);
    }

    /**
     * The outcome of {@link #normalise}.
     *
     * @param args                 the effective argument expressions
     * @param tooFewArguments      the call cannot satisfy the signature; emit T0410
     * @param injectedContextType  the type symbol the injected context must satisfy, or
     *                             null when no context was injected. A context of the
     *                             wrong type is T0411, not T0410.
     */
    record Normalised(List<String> args, boolean tooFewArguments, String injectedContextType) {}

    /**
     * Applies the context-substitution rule to a built-in call's compiled arguments.
     *
     * <p>When the signature has a {@code -} slot and fewer arguments were supplied than it
     * requires, the step context is prepended. When the arguments are still short of what
     * the signature requires, {@code tooFewArguments} is set so the caller can emit a
     * T0410 instead of indexing off the end of the list — which is what used to happen,
     * crashing the translator on {@code (2).$power(3)} and half a dozen others.
     */
    static Normalised normalise(String name, List<AstNode> astArgs, List<String> args,
                                String contextVar) {
        String signature = SIGNATURES.get(name);
        if (signature == null || SELF_MANAGED.contains(name)) {
            return new Normalised(args, false, null);
        }
        int required = FunctionSignature.requiredArgCount(signature);
        if (required < 0) return new Normalised(args, false, null);

        if (args.size() < required && FunctionSignature.hasContextSlot(signature)
                && argumentsFitAfterInjection(signature, astArgs)) {
            List<String> effective = new java.util.ArrayList<>(args.size() + 1);
            effective.add(contextVar);
            effective.addAll(args);
            return new Normalised(effective, effective.size() < required,
                    FunctionSignature.firstParamType(signature));
        }
        return new Normalised(args, args.size() < required, null);
    }

    /**
     * Whether the written arguments would still fit the signature once the context is
     * inserted ahead of them.
     *
     * <p>This is the type-directed half of the reference's rule. {@code $substring(1)}
     * substitutes the context because {@code 1} fits the second parameter's {@code n};
     * {@code $split(12345)} does not, because a number cannot be a separator — so the
     * argument itself is at fault and the call reports T0410 rather than silently
     * splitting the context by a number.
     *
     * <p>Only literals are judged. An argument whose type is not known until evaluation is
     * assumed to fit, which makes substitution the default — the reading a path step
     * almost always wants.
     */
    private static boolean argumentsFitAfterInjection(String signature, List<AstNode> astArgs) {
        for (int i = 0; i < astArgs.size(); i++) {
            String actual = literalTypeOf(astArgs.get(i));
            if (actual == null) continue;                        // not statically known
            String expected = FunctionSignature.paramType(signature, i + 1);
            if (!typeAccepts(expected, actual)) return false;
        }
        return true;
    }

    /** The signature type symbol of a literal argument, or null when it is not a literal. */
    private static String literalTypeOf(AstNode node) {
        return switch (node) {
            case AstNode.NumberLiteral ignored -> "n";
            case AstNode.StringLiteral ignored -> "s";
            case AstNode.BooleanLiteral ignored -> "b";
            case AstNode.NullLiteral ignored -> "l";
            case AstNode.RegexLiteral ignored -> "f";
            case AstNode.Lambda ignored -> "f";
            case AstNode.ArrayConstructor ignored -> "a";
            case AstNode.ObjectConstructor ignored -> "o";
            default -> null;
        };
    }

    /** Whether a signature type symbol accepts a value of type {@code actual}. */
    private static boolean typeAccepts(String expected, String actual) {
        if (expected == null || expected.isEmpty()) return true;
        char head = expected.charAt(0);
        if (head == '(') return expected.indexOf(actual.charAt(0)) > 0;
        return switch (head) {
            case 'x' -> true;
            case 'j' -> !actual.equals("f");
            case 'u' -> "bnsl".indexOf(actual.charAt(0)) >= 0;
            default -> head == actual.charAt(0);
        };
    }
}

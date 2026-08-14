package org.json_kula.jsonata_jvm;

import com.fasterxml.jackson.databind.JsonNode;
import org.json_kula.jsonata_jvm.parser.Parser;
import org.json_kula.jsonata_jvm.parser.ast.AstNode;
import org.json_kula.jsonata_jvm.runtime.JsonataRuntime;
import org.json_kula.jsonata_jvm.translator.ScopeAnalyzer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a JSONata <em>definition expression</em> — one that binds named lambdas and returns the
 * names of the ones to export — into an expression that also hands those lambdas back to the caller.
 *
 * <p>A definition expression is ordinary JSONata and evaluates on its own in any JSONata engine; its
 * result is the export list:
 *
 * <pre>{@code
 * (
 *   $pi := 3.14159;
 *   $product := function($a, $b) { $a * $b };
 *   $sin := function($x){ ... };
 *   $cos := function($x){ ... };
 *   ["sin", "cos"]                      ← the expression's own result
 * )
 * }</pre>
 *
 * <p>The rewrite keeps that result and adds the values beside it. The trailing expression is bound
 * to a synthetic variable and a final object constructor collects it together with every variable
 * the definition bound at its top level:
 *
 * <pre>{@code
 * (
 *   $pi := 3.14159; $product := …; $sin := …; $cos := …;
 *   $__exportNames := ["sin", "cos"];
 *   {"names": $__exportNames,
 *    "functions": {"pi": $pi, "product": $product, "sin": $sin, "cos": $cos}}
 * )
 * }</pre>
 *
 * <p>Collecting <em>all</em> top-level bindings is what allows a single compilation and a single
 * evaluation: which of them to export is only known once the names expression has been evaluated,
 * and by then the values are already in hand. Building the object <em>inside</em> the block is what
 * makes it work at all — the translator emits a block's bindings as Java locals of a private helper
 * method, so only an expression in the same block can read them.
 */
final class FunctionExportRewriter {

    private FunctionExportRewriter() {}

    /** Field of the export object holding the definition's own result — the export list. */
    static final String NAMES_FIELD = "names";
    /** Field of the export object holding every top-level binding, keyed by name. */
    static final String FUNCTIONS_FIELD = "functions";

    private static final String NAMES_VAR_BASE = "__exportNames";

    /**
     * What the export wrapper needs to know about one exported function, recovered from the AST.
     *
     * @param arity     the declared parameter count, or {@code -1} when the binding's value is not
     *                  a literal lambda (a partial application, a {@code ~>} chain, the result of a
     *                  higher-order call …) and the arity is therefore only known at call time
     * @param signature the JSONata signature to report to callers, or {@code null} for no
     *                  validation or coercion at the Java boundary
     */
    record ExportInfo(int arity, String signature) {}

    /**
     * Strips a leading {@code $} so that a definition may list its exports either way. Map keys use
     * the bare form, matching {@link JsonataExpression#registerFunction} and
     * {@link JsonataBindings#bindFunction}.
     */
    static String normalize(String name) {
        String trimmed = name.strip();
        return trimmed.startsWith("$") ? trimmed.substring(1) : trimmed;
    }

    /** Collects {@code name → value expression} for every binding at the top level of {@code root}. */
    static Map<String, AstNode> topLevelBindings(AstNode root) {
        Map<String, AstNode> bindings = new LinkedHashMap<>();
        AstNode unwrapped = root;
        while (unwrapped instanceof AstNode.Parenthesized parenthesized) {
            unwrapped = parenthesized.inner();
        }
        List<AstNode> expressions = unwrapped instanceof AstNode.Block block
                ? block.expressions()
                : List.of(unwrapped);
        for (AstNode expr : expressions) {
            // Chained assignment ($a := $b := value) binds every name in the chain; a later
            // re-binding of the same name wins, matching evaluation order.
            AstNode current = expr;
            while (current instanceof AstNode.VariableBinding binding) {
                bindings.put(binding.name(), binding.value());
                current = binding.value();
            }
        }
        return bindings;
    }

    /**
     * Rejects a definition that refers to a name nothing provides.
     *
     * <p>A definition is meant to be self-contained: whatever it uses, it either binds itself, gets
     * from the JSONata standard library, or is handed at build time through
     * {@link JsonataLibraryOptions#bindings}. Resolving the rest against whatever happens to be
     * bound where an exported function is <em>called</em> would make the library's behaviour depend
     * on its caller, and a typo indistinguishable from a deliberate hook — so it is an error here,
     * where the name and the fix can both be named.
     *
     * @param root     the parsed definition
     * @param provided names supplied at build time, without the leading {@code $}
     */
    static void requireSelfContained(AstNode root, Set<String> provided)
            throws JsonataCompilationException {
        List<String> unresolved = new ArrayList<>();
        for (String name : ScopeAnalyzer.freeVariables(root)) {
            if (provided.contains(name) || Parser.isBuiltin(name)) continue;
            unresolved.add(name);
        }
        if (unresolved.isEmpty()) return;
        boolean one = unresolved.size() == 1;
        throw error("The definition expression uses $" + String.join(", $", unresolved)
                + ", which it does not bind and which "
                + (one ? "is not a JSONata built-in" : "are not JSONata built-ins")
                + ". Bind " + (one ? "it" : "them") + " in the definition, or supply "
                + (one ? "it" : "them") + " through JsonataLibraryOptions.bindings.");
    }

    /**
     * Returns {@code root} rewritten to yield {@code {"names": …, "functions": {…}}}.
     *
     * @param boundNames the top-level binding names, as returned by {@link #topLevelBindings}
     */
    static AstNode rewrite(AstNode root, Iterable<String> boundNames) {
        String namesVar = freshNamesVar(boundNames);

        List<AstNode.KeyValuePair> functionPairs = new ArrayList<>();
        for (String name : boundNames) {
            functionPairs.add(new AstNode.KeyValuePair(
                    new AstNode.StringLiteral(name), new AstNode.VariableRef(name)));
        }
        AstNode exportObject = new AstNode.ObjectConstructor(List.of(
                new AstNode.KeyValuePair(
                        new AstNode.StringLiteral(NAMES_FIELD), new AstNode.VariableRef(namesVar)),
                new AstNode.KeyValuePair(
                        new AstNode.StringLiteral(FUNCTIONS_FIELD),
                        new AstNode.ObjectConstructor(functionPairs))));

        return replaceTail(root, namesVar, exportObject);
    }

    /**
     * Binds the definition's last expression to {@code namesVar} and appends {@code exportObject}
     * after it, so the original result is computed exactly once and stays available.
     */
    private static AstNode replaceTail(AstNode root, String namesVar, AstNode exportObject) {
        if (root instanceof AstNode.Parenthesized parenthesized) {
            return new AstNode.Parenthesized(replaceTail(parenthesized.inner(), namesVar, exportObject));
        }
        if (root instanceof AstNode.Block block && !block.expressions().isEmpty()) {
            List<AstNode> expressions = new ArrayList<>(block.expressions());
            int lastIndex = expressions.size() - 1;
            expressions.set(lastIndex, new AstNode.VariableBinding(namesVar, expressions.get(lastIndex)));
            expressions.add(exportObject);
            return new AstNode.Block(expressions);
        }
        // A definition that is a single expression rather than a sequence, e.g. just "['sin']"
        // (which exports nothing and is reported as such) or a lone binding.
        return new AstNode.Block(List.of(new AstNode.VariableBinding(namesVar, root), exportObject));
    }

    /** Picks a synthetic variable name that the definition does not already bind. */
    private static String freshNamesVar(Iterable<String> boundNames) {
        java.util.Set<String> taken = new java.util.HashSet<>();
        boundNames.forEach(taken::add);
        String candidate = NAMES_VAR_BASE;
        for (int suffix = 2; taken.contains(candidate); suffix++) {
            candidate = NAMES_VAR_BASE + suffix;
        }
        return candidate;
    }

    /**
     * Reads the export list the definition returned: an array of strings, or a single string.
     *
     * @throws JsonataCompilationException if the result is not a usable list of names
     */
    static List<String> exportedNames(JsonNode names) throws JsonataCompilationException {
        if (names == null || names.isMissingNode()) {
            throw error("The definition expression must return an array of function names to export,"
                    + " but it returned nothing");
        }
        List<JsonNode> elements = new ArrayList<>();
        if (names.isArray()) {
            names.forEach(elements::add);
        } else {
            // A single name may be returned unwrapped, as JSONata collapses one-element sequences.
            elements.add(names);
        }
        if (elements.isEmpty()) {
            throw error("The definition expression returned an empty array; it must name at least"
                    + " one function to export");
        }

        List<String> result = new ArrayList<>(elements.size());
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (JsonNode element : elements) {
            if (!element.isTextual() || JsonataRuntime.isLambdaToken(element)) {
                throw error("The definition expression must return an array of function names, but"
                        + " one element is " + JsonataRuntime.fn_type(element).asText()
                        + ": " + JsonataRuntime.sanitizeForString(element));
            }
            String name = normalize(element.textValue());
            if (name.isEmpty()) {
                throw error("The definition expression returned an empty function name");
            }
            if (!seen.add(name)) {
                throw error("The definition expression names $" + name + " twice");
            }
            result.add(name);
        }
        return result;
    }

    /**
     * Recovers the arity and signature of one exported function from the AST.
     *
     * @param value the bound value expression, or {@code null} if the definition has no top-level
     *              binding of that name
     */
    static ExportInfo describe(AstNode value) {
        if (value instanceof AstNode.Lambda lambda) {
            int arity = lambda.params().size();
            String declared = lambda.signature();
            return new ExportInfo(arity, declared != null ? declared : synthesizeSignature(arity));
        }
        // Computed function values — $twice($add3), $uppercase ~> $trim, $substring(?, 0, 5),
        // a conditional choosing between two lambdas … The value is checked for real after the
        // definition runs; until then neither arity nor signature is known.
        return new ExportInfo(-1, null);
    }

    /**
     * Builds an all-optional signature such as {@code <j?j?:j>}. Optional is deliberate: JSONata
     * lets a lambda be called with fewer arguments than it declares, binding the rest to
     * <em>undefined</em>, and {@code j} applies no coercion — so the exported function accepts
     * exactly what the same function accepts when called from JSONata.
     */
    private static String synthesizeSignature(int arity) {
        if (arity == 0) return null;
        return "<" + "j?".repeat(arity) + ":j>";
    }

    private static JsonataCompilationException error(String message) {
        return new JsonataCompilationException(null, message, null);
    }
}

package org.json_kula.jsonata_jvm;

import org.json_kula.jsonata_jvm.parser.ast.AstNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a JSONata <em>definition expression</em> — one whose purpose is to bind named lambdas —
 * into an expression that hands those lambdas back to the caller.
 *
 * <p>The rewrite appends a single object constructor as the last expression of the outermost block:
 *
 * <pre>{@code
 * ( $pi := 3.14159; $sin := function($x){ ... }; $cos := function($x){ ... } )
 * ( $pi := 3.14159; $sin := function($x){ ... }; $cos := function($x){ ... };
 *   {"sin": $sin, "cos": $cos} )                                    ← appended
 * }</pre>
 *
 * <p>Appending <em>inside</em> the block is what makes this work: the translator emits a block's
 * variable bindings as Java locals of a private helper method, so only an expression in the same
 * block can read them. It also gives the right failure mode — a name bound only inside a nested
 * block is simply not in scope, which {@link #analyze} reports before any code is generated.
 */
final class FunctionExportRewriter {

    private FunctionExportRewriter() {}

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
     * Strips a leading {@code $} so that {@code "$sin"} and {@code "sin"} name the same function.
     * Map keys use the bare form, matching {@link JsonataExpression#registerFunction} and
     * {@link JsonataBindings#bindFunction}.
     */
    static String normalize(String name) {
        if (name == null || name.isBlank())
            throw new IllegalArgumentException("Function name must not be null or blank");
        String trimmed = name.strip();
        return trimmed.startsWith("$") ? trimmed.substring(1) : trimmed;
    }

    /** Normalises every name, rejecting duplicates and blanks. */
    static List<String> normalizeAll(List<String> names) {
        if (names == null || names.isEmpty())
            throw new IllegalArgumentException("At least one function name must be requested");
        List<String> result = new ArrayList<>(names.size());
        for (String name : names) {
            String normalized = normalize(name);
            if (normalized.isEmpty())
                throw new IllegalArgumentException("Function name must not be just \"$\"");
            if (result.contains(normalized))
                throw new IllegalArgumentException("Duplicate function name: $" + normalized);
            result.add(normalized);
        }
        return result;
    }

    /**
     * Returns {@code root} with an export object constructor appended to the outermost block.
     *
     * @param names normalised function names (no leading {@code $})
     */
    static AstNode rewrite(AstNode root, List<String> names) {
        List<AstNode.KeyValuePair> pairs = new ArrayList<>(names.size());
        for (String name : names) {
            pairs.add(new AstNode.KeyValuePair(
                    new AstNode.StringLiteral(name), new AstNode.VariableRef(name)));
        }
        return append(root, new AstNode.ObjectConstructor(pairs));
    }

    private static AstNode append(AstNode root, AstNode export) {
        // "( … )" parses as Parenthesized(Block(…)); keep the wrapper, append inside it.
        if (root instanceof AstNode.Parenthesized parenthesized) {
            return new AstNode.Parenthesized(append(parenthesized.inner(), export));
        }
        if (root instanceof AstNode.Block block) {
            List<AstNode> expressions = new ArrayList<>(block.expressions());
            expressions.add(export);
            return new AstNode.Block(expressions);
        }
        // A definition that is a single binding rather than a sequence, e.g.
        // "$sin := function($x){ ... }" — wrap it so the binding and the export share a scope.
        return new AstNode.Block(List.of(root, export));
    }

    /**
     * Inspects the definition's top-level bindings and returns one {@link ExportInfo} per requested
     * name, in request order.
     *
     * @throws JsonataCompilationException if a requested name is not bound at the top level of the
     *                                     definition expression, or is bound to a value that
     *                                     plainly cannot be a function
     */
    static Map<String, ExportInfo> analyze(AstNode root, List<String> names)
            throws JsonataCompilationException {
        Map<String, AstNode> bound = new LinkedHashMap<>();
        collectTopLevelBindings(root, bound);

        Map<String, ExportInfo> infos = new LinkedHashMap<>();
        for (String name : names) {
            AstNode value = bound.get(name);
            if (value == null) {
                throw new JsonataCompilationException(null,
                        "$" + name + " is not defined at the top level of the definition expression"
                                + (bound.isEmpty()
                                        ? " (it binds no variables at all)"
                                        : " (it binds: $" + String.join(", $", bound.keySet()) + ")"),
                        null);
            }
            infos.put(name, describe(name, value));
        }
        return infos;
    }

    /** Collects {@code name → value} for every binding at the top level of {@code root}. */
    private static void collectTopLevelBindings(AstNode root, Map<String, AstNode> out) {
        AstNode unwrapped = root;
        while (unwrapped instanceof AstNode.Parenthesized parenthesized) {
            unwrapped = parenthesized.inner();
        }
        List<AstNode> expressions = unwrapped instanceof AstNode.Block block
                ? block.expressions()
                : List.of(unwrapped);
        for (AstNode expr : expressions) {
            // Chained assignment ($a := $b := value) binds every name in the chain.
            AstNode current = expr;
            while (current instanceof AstNode.VariableBinding binding) {
                // A later re-binding of the same name wins, matching evaluation order.
                out.put(binding.name(), binding.value());
                current = binding.value();
            }
        }
    }

    private static ExportInfo describe(String name, AstNode value) throws JsonataCompilationException {
        if (value instanceof AstNode.Lambda lambda) {
            int arity = lambda.params().size();
            String declared = lambda.signature();
            return new ExportInfo(arity, declared != null ? declared : synthesizeSignature(arity));
        }
        if (isPlainlyNotAFunction(value)) {
            throw new JsonataCompilationException(null,
                    "$" + name + " is bound to " + describeValue(value)
                            + ", not a function, in the definition expression", null);
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

    private static boolean isPlainlyNotAFunction(AstNode value) {
        return value instanceof AstNode.NumberLiteral
                || value instanceof AstNode.StringLiteral
                || value instanceof AstNode.BooleanLiteral
                || value instanceof AstNode.NullLiteral
                || value instanceof AstNode.ArrayConstructor
                || value instanceof AstNode.ObjectConstructor;
    }

    private static String describeValue(AstNode value) {
        return switch (value) {
            case AstNode.NumberLiteral ignored  -> "a number";
            case AstNode.StringLiteral ignored  -> "a string";
            case AstNode.BooleanLiteral ignored -> "a boolean";
            case AstNode.NullLiteral ignored    -> "null";
            case AstNode.ArrayConstructor ignored  -> "an array";
            case AstNode.ObjectConstructor ignored -> "an object";
            default                             -> "a value";
        };
    }
}

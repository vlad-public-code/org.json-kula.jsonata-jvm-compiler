package org.json_kula.jsonata_jvm.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import org.json_kula.jsonata_jvm.JsonataBoundFunction;
import org.json_kula.jsonata_jvm.JsonataEvaluationException;
import org.json_kula.jsonata_jvm.JsonataFunctionArguments;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Adapts a {@link JsonataBoundFunction} to a JSONata function <em>value</em> — a {@link LambdaNode}
 * that can be stored in a variable, passed to {@code $map}, or piped through {@code ~>}.
 *
 * <p>This is the mirror image of {@code ExportedJsonataFunction}, which adapts a function value to
 * the {@link JsonataBoundFunction} contract. Together they make the two sides of the binding API
 * interchangeable: whichever way a function was supplied, it can be used either way.
 *
 * <h2>Why a fresh node per reference</h2>
 *
 * <p>Each call to {@link #of} mints a new {@link LambdaNode}. Caching one per bound function would
 * need a map keyed by the function instance, and the node holds the function, so a weak-keyed map
 * would never collect; a strong-keyed one would pin every per-evaluation binding for the life of the
 * JVM. Minting is a two-field allocation on a path that runs once per {@code $name} reference, not
 * once per element.
 *
 * <h2>Calling convention</h2>
 *
 * <p>A {@link JsonataLambda} takes exactly one node, so multi-argument calls arrive packed by
 * {@link JsonataRuntime#packArgs}. Unpacking mirrors what the translator emits for a multi-parameter
 * JSONata lambda ({@code __pk.get(i)} per parameter): a function of declared arity ≥ 2 spreads an
 * array argument positionally, anything else takes the node as its single argument. The declared
 * arity comes from the signature's parameter count.
 *
 * <p>The consequence — shared with hand-written lambdas, which unpack the same way — is that a
 * two-parameter function cannot tell {@code $f([1,2])} from {@code $f(1,2)}, and a function whose
 * arity the signature does not pin down (absent, unparseable, or variadic) receives one argument.
 * Declare a fixed arity to receive several.
 */
final class BoundFunctionValue {

    private BoundFunctionValue() {}

    /**
     * Wraps {@code fn} as a function value.
     *
     * @param name the binding name, used only in error messages
     */
    static JsonNode of(String name, JsonataBoundFunction fn) {
        int arity = FunctionSignature.arityOf(fn.getFunctionSignature());
        return LambdaRegistry.lambdaNode(arg -> invoke(name, fn, arity, arg), arity);
    }

    /** Unpacks {@code arg} per the declared arity and calls {@code fn}. */
    private static JsonNode invoke(String name, JsonataBoundFunction fn, int arity, JsonNode arg)
            throws RuntimeEvaluationException {
        final List<JsonNode> args;
        if (arity == 0) {
            args = List.of();
        } else if (arity >= 2 && arg != null && arg.isArray()) {
            args = new ArrayList<>(arity);
            for (int i = 0; i < arity; i++) args.add(arg.get(i) != null ? arg.get(i) : JsonataRuntime.MISSING);
        } else {
            args = List.of(arg != null ? arg : JsonataRuntime.MISSING);
        }
        return call(name, fn, args);
    }

    /**
     * Coerces {@code args} against the function's signature and calls it — the same sequence
     * {@code EvaluationContext.callBoundFunction} applies to a direct {@code $name(...)} call, so a
     * bound function behaves identically whether it is called by name or through a value.
     */
    static JsonNode call(String name, JsonataBoundFunction fn, List<JsonNode> args)
            throws RuntimeEvaluationException {
        List<JsonNode> coerced = FunctionSignature.coerce(fn.getFunctionSignature(), args);
        try {
            return fn.apply(new JsonataFunctionArguments(coerced));
        } catch (JsonataEvaluationException e) {
            throw new RuntimeEvaluationException(e.getErrorCode(),
                    "Error calling bound function $" + name, e);
        }
    }

    /** Calls {@code fn} with an argument array, as a direct {@code $name(...)} call site supplies it. */
    static JsonNode call(String name, JsonataBoundFunction fn, JsonNode[] args)
            throws RuntimeEvaluationException {
        return call(name, fn, Arrays.asList(args));
    }

    /**
     * Applies a function <em>value</em> bound under {@code name} to a call site's argument array,
     * packing them the way generated code packs a call to a local function variable: no argument
     * becomes {@code null}, one is passed through, several are packed into a non-flattening tuple.
     */
    static JsonNode apply(JsonNode fnValue, JsonNode[] args) throws RuntimeEvaluationException {
        final JsonNode arg;
        if (args.length == 0) arg = NullNode.instance;
        else if (args.length == 1) arg = args[0];
        else arg = JsonataRuntime.packArgs(args);
        return LambdaRegistry.fn_apply(fnValue, arg);
    }
}

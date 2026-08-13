package org.json_kula.jsonata_jvm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import org.json_kula.jsonata_jvm.runtime.JsonataRuntime;
import org.json_kula.jsonata_jvm.runtime.RuntimeEvaluationException;

/**
 * Adapts one lambda exported from a {@link JsonataFunctionLibrary} to the
 * {@link JsonataBoundFunction} contract.
 *
 * <p>Calls are forwarded to the runtime's {@code fn_apply}, which resolves the lambda token, runs
 * the tail-call trampoline, and enforces the recursion limit — exactly as a call from inside a
 * JSONata expression would.
 */
final class ExportedJsonataFunction implements JsonataBoundFunction {

    private final String name;
    private final JsonNode token;
    private final String signature;
    private final int arity;
    /** Keeps the library — and therefore the lambda scope and generated class — reachable. */
    private final JsonataFunctionLibrary owner;

    ExportedJsonataFunction(String name, JsonNode token, String signature, int arity,
                            JsonataFunctionLibrary owner) {
        this.name = name;
        this.token = token;
        this.signature = signature;
        this.arity = arity;
        this.owner = owner;
    }

    @Override
    public String getFunctionSignature() {
        return signature;
    }

    @Override
    public JsonNode apply(JsonataFunctionArguments args) throws JsonataEvaluationException {
        JsonNode arg = packArguments(args);

        // Inside a caller's evaluation we deliberately reuse its frame, so the function observes
        // the caller's bindings, recursion budget and timeout. Called directly from Java there is
        // no frame at all, and runtime helpers ($millis, regex caches, $-bindings) need one.
        boolean ownFrame = !JsonataRuntime.isEvaluationActive();
        if (ownFrame) owner.beginStandaloneFrame();
        try {
            return JsonataRuntime.fn_apply(token, arg);
        } catch (RuntimeEvaluationException e) {
            throw new JsonataEvaluationException(e.getErrorCode(),
                    "Error calling exported function $" + name + ": " + e.getMessage(), e);
        } finally {
            if (ownFrame) JsonataRuntime.endEvaluation();
        }
    }

    /**
     * Applies the runtime's calling convention for user-defined functions: no argument becomes
     * {@code null}, one argument is passed through, and several are packed into a non-flattening
     * tuple that the lambda body unpacks positionally.
     *
     * <p>The declared arity decides, not {@code args.size()}: the signature machinery pads a short
     * argument list with {@code MissingNode} to the declared length, and the generated unpack code
     * treats a missing slot as an absent parameter. When the arity is unknown ({@code -1} — the
     * binding's value was computed rather than a literal lambda) the supplied count decides.
     */
    private JsonNode packArguments(JsonataFunctionArguments args) {
        int effectiveArity = arity >= 0 ? arity : args.size();
        if (effectiveArity == 0) return NullNode.instance;
        if (effectiveArity == 1) return args.size() == 0 ? JsonataRuntime.MISSING : args.get(0);
        return JsonataRuntime.packArgs(args.asList().toArray(new JsonNode[0]));
    }

    @Override
    public String toString() {
        return "$" + name + (signature != null ? signature : "") + " from " + owner;
    }
}

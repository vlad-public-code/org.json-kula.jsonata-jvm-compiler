package org.json_kula.jsonata_jvm.runtime;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Single-argument functional interface used throughout the JSONata runtime
 * for predicates, map functions, filter functions, and similar single-element
 * callbacks.
 *
 * <p>The interface declares {@link RuntimeEvaluationException} so that generated
 * lambdas can propagate runtime errors without wrapping them in unchecked exceptions.
 */
@FunctionalInterface
public interface JsonataLambda {
    JsonNode apply(JsonNode element) throws RuntimeEvaluationException;
}

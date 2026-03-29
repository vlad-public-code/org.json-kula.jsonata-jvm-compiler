package org.json_kula.jsonata_jvm;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A Java function that can be bound into a JSONata expression via
 * {@link JsonataExpression#registerFunction} or {@link JsonataBindings}.
 *
 * <h2>Function signature syntax</h2>
 * <p>The signature string has the form {@code <params:return>} where
 * {@code params} is a sequence of type symbols and {@code return} is a
 * single type symbol.
 *
 * <h3>Simple type symbols</h3>
 * <ul>
 *   <li>{@code b} — Boolean</li>
 *   <li>{@code n} — number</li>
 *   <li>{@code s} — string</li>
 *   <li>{@code l} — null</li>
 * </ul>
 *
 * <h3>Complex type symbols</h3>
 * <ul>
 *   <li>{@code a} — array</li>
 *   <li>{@code o} — object</li>
 * </ul>
 *
 * <h3>Union types</h3>
 * <ul>
 *   <li>{@code (sao)} — string, array, or object</li>
 *   <li>{@code u} — equivalent to {@code (bnsl)}: Boolean, number, string, or null</li>
 *   <li>{@code j} — any JSON type: equivalent to {@code (bnsloa)}</li>
 * </ul>
 *
 * <h3>Parametrised types</h3>
 * <ul>
 *   <li>{@code a<s>} — array of strings</li>
 *   <li>{@code a<x>} — array of values of any type</li>
 * </ul>
 *
 * <h3>Option modifiers</h3>
 * <ul>
 *   <li>{@code +} — one or more arguments of this type (variadic)</li>
 *   <li>{@code ?} — optional argument</li>
 *   <li>{@code -} — if this argument is missing, use the context value (focus)</li>
 * </ul>
 *
 * <p>Example: {@code $length} has signature {@code <s-:n>} — accepts a string
 * (using context as focus if omitted) and returns a number.
 *
 * <p>Note: type {@code f} (function) and type {@code x} ({@code bnsloaf}) are
 * not supported in this library.
 */
public interface JsonataBoundFunction {

    /**
     * Returns the JSONata function signature string, e.g. {@code "<j+:j>"}.
     * The signature is used for argument type validation and coercion.
     */
    String getFunctionSignature();

    /**
     * Applies this function to the given arguments.
     *
     * @param args the arguments provided by the JSONata expression; accessing
     *             an out-of-range index returns {@code MissingNode}
     * @return the result as a {@link com.fasterxml.jackson.databind.JsonNode};
     *         return {@link com.fasterxml.jackson.databind.node.MissingNode}
     *         to signal "no value"
     * @throws JsonataEvaluationException if the function cannot be applied to
     *                                    the given arguments
     */
    JsonNode apply(JsonataFunctionArguments args) throws JsonataEvaluationException;
}

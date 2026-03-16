package org.json_kula.jsonata_jvm;

/**
 * Thrown when a generated JSONata expression cannot be evaluated.
 *
 * <p>Covers two failure modes:
 * <ul>
 *   <li>The input string is not valid JSON.
 *   <li>The expression logic fails at runtime (e.g. type mismatch, division by zero).
 * </ul>
 */
public class JsonataEvaluationException extends Exception {

    public JsonataEvaluationException(String message) {
        super(message);
    }

    public JsonataEvaluationException(String message, Throwable cause) {
        super(message, cause);
    }
}

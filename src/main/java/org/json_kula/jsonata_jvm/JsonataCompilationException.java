package org.json_kula.jsonata_jvm;

import org.json_kula.jsonata_jvm.loader.JsonataLoadException;
import org.json_kula.jsonata_jvm.parser.ParseException;

/**
 * Thrown by {@link JsonataExpressionFactory#compile} when a JSONata expression
 * string cannot be turned into a {@link JsonataExpression}.
 *
 * <p>The cause is always one of:
 * <ul>
 *   <li>{@link ParseException} — the expression is syntactically invalid</li>
 *   <li>{@link JsonataLoadException} — the generated Java source
 *       failed to compile (internal error)</li>
 * </ul>
 */
public class JsonataCompilationException extends Exception {

    public JsonataCompilationException(String message, Throwable cause) {
        super(message, cause);
    }
}

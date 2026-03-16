package org.json_kula.jsonata_jvm.loader;

import org.json_kula.jsonata_jvm.JsonataExpression;

/**
 * Thrown when a Java source string cannot be compiled or instantiated as a
 * {@link JsonataExpression}.
 */
public class JsonataLoadException extends Exception {

    public JsonataLoadException(String message) {
        super(message);
    }

    public JsonataLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}

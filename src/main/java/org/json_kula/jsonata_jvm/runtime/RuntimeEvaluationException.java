package org.json_kula.jsonata_jvm.runtime;

public class RuntimeEvaluationException extends RuntimeException {

    public RuntimeEvaluationException(String message) {
        super(message);
    }

    public RuntimeEvaluationException(String message, Throwable cause) {
        super(message, cause);
    }
}

package org.json_kula.jsonata_jvm.runtime;

import org.json_kula.jsonata_jvm.JsonataException;

public class RuntimeEvaluationException extends RuntimeException implements JsonataException {

    private final String errorCode;

    public RuntimeEvaluationException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public RuntimeEvaluationException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
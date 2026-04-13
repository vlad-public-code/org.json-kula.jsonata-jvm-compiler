package org.json_kula.jsonata_jvm.translator;

import org.json_kula.jsonata_jvm.JsonataException;

public class RuntimeTranslatorException extends RuntimeException implements JsonataException {
    private final String errorCode;

    public RuntimeTranslatorException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public RuntimeTranslatorException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    @Override
    public String getErrorCode() {
        return errorCode;
    }
}

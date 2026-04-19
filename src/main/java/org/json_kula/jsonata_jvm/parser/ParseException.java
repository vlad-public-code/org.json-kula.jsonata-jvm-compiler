package org.json_kula.jsonata_jvm.parser;

import org.json_kula.jsonata_jvm.JsonataException;

/**
 * Thrown when a JSONata expression string cannot be parsed.
 */
public class ParseException extends Exception implements JsonataException {

    private final int position;
    private final String errorCode;

    public ParseException(String errorCode, String message, int position) {
        super(message + " (position " + position + ")");
        this.position = position;
        this.errorCode = errorCode;
    }

    public ParseException(String errorCode, String message) {
        super(message);
        this.position = -1;
        this.errorCode = errorCode;
    }

    public static ParseException withErrorCodeFromMessage(String message, int position) {
        return new ParseException(extractErrorCode(message), message, position);
    }

    /** Zero-based character position in the source where the error occurred, or -1 if unknown. */
    public int getPosition() {
        return position;
    }

    @Override
    public String getErrorCode() {
        return errorCode;
    }

    private static final java.util.regex.Pattern ERROR_CODE_PATTERN =
            java.util.regex.Pattern.compile("[A-Z]\\d{4}");

    private static String extractErrorCode(String message) {
        var matcher = ERROR_CODE_PATTERN.matcher(message);
        return matcher.find() ? matcher.group() : "UNKNOWN";
    }
}
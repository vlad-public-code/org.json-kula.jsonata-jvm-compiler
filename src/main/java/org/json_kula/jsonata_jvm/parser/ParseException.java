package org.json_kula.jsonata_jvm.parser;

/**
 * Thrown when a JSONata expression string cannot be parsed.
 */
public class ParseException extends Exception {

    private final int position;

    public ParseException(String message, int position) {
        super(message + " (position " + position + ")");
        this.position = position;
    }

    public ParseException(String message) {
        super(message);
        this.position = -1;
    }

    /** Zero-based character position in the source where the error occurred, or -1 if unknown. */
    public int getPosition() {
        return position;
    }
}

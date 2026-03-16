package org.json_kula.jsonata_jvm.parser.lexer;

/**
 * A single lexical token produced by the JSONata {@link Lexer}.
 *
 * @param type     the kind of token
 * @param value    the raw text from the source (empty string for punctuation tokens
 *                 whose type alone carries all the meaning)
 * @param position zero-based character offset of the first character of this token
 */
public record Token(TokenType type, String value, int position) {

    /** Convenience factory for punctuation tokens that carry no extra text. */
    public static Token of(TokenType type, int position) {
        return new Token(type, "", position);
    }

    /** Convenience factory for tokens whose text matters (literals, identifiers, etc.). */
    public static Token of(TokenType type, String value, int position) {
        return new Token(type, value, position);
    }

    @Override
    public String toString() {
        return value.isEmpty()
                ? type.name() + "@" + position
                : type.name() + "(" + value + ")@" + position;
    }
}

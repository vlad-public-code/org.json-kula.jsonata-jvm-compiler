package org.json_kula.jsonata_jvm.parser.lexer;

import org.json_kula.jsonata_jvm.parser.ParseException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Hand-written lexer (tokenizer) for the JSONata expression language.
 *
 * <p>Produces a flat list of {@link Token}s from a source string.
 * Whitespace and comments ({@code /* ... *\/}) are silently skipped.
 *
 * <p>Supported literals:
 * <ul>
 *   <li>Double-quoted strings with standard JSON escape sequences</li>
 *   <li>Single-quoted strings (same escape rules)</li>
 *   <li>Numbers: integer, decimal, and scientific notation</li>
 *   <li>Keywords: {@code true}, {@code false}, {@code null}, {@code and}, {@code or},
 *       {@code in}, {@code not}</li>
 * </ul>
 *
 * <p>Backtick-quoted names are emitted as {@link TokenType#IDENTIFIER} with the raw
 * (unescaped) content between the backticks as the value.
 */
public final class Lexer {

    private static final Map<String, TokenType> KEYWORDS = Map.of(
            "true",  TokenType.TRUE,
            "false", TokenType.FALSE,
            "null",  TokenType.NULL,
            "and",   TokenType.AND,
            "or",    TokenType.OR,
            "in",    TokenType.IN,
            "not",   TokenType.NOT
    );

    private final String src;
    private int pos;

    private Lexer(String src) {
        this.src = src;
        this.pos = 0;
    }

    /**
     * Tokenizes {@code source} and returns all tokens including a terminal
     * {@link TokenType#EOF} token.
     *
     * @param source the JSONata expression text
     * @return immutable list of tokens
     * @throws ParseException on any lexical error (unterminated string, bad escape, etc.)
     */
    public static List<Token> tokenize(String source) throws ParseException {
        return new Lexer(source).scan();
    }

    // -------------------------------------------------------------------------

    private List<Token> scan() throws ParseException {
        List<Token> tokens = new ArrayList<>();
        while (pos < src.length()) {
            skipWhitespaceAndComments();
            if (pos >= src.length()) break;

            int start = pos;
            char c = src.charAt(pos);

            Token token = switch (c) {
                case '+' -> { pos++; yield Token.of(TokenType.PLUS, start); }
                case '-' -> { pos++; yield Token.of(TokenType.MINUS, start); }
                case '/' -> { pos++; yield Token.of(TokenType.SLASH, start); }
                case '%' -> { pos++; yield Token.of(TokenType.PERCENT, start); }
                case '&' -> { pos++; yield Token.of(TokenType.AMPERSAND, start); }
                case '?' -> { pos++; yield Token.of(TokenType.QUESTION, start); }
                case ',' -> { pos++; yield Token.of(TokenType.COMMA, start); }
                case ';' -> { pos++; yield Token.of(TokenType.SEMICOLON, start); }
                case '(' -> { pos++; yield Token.of(TokenType.LPAREN, start); }
                case ')' -> { pos++; yield Token.of(TokenType.RPAREN, start); }
                case '[' -> { pos++; yield Token.of(TokenType.LBRACKET, start); }
                case ']' -> { pos++; yield Token.of(TokenType.RBRACKET, start); }
                case '{' -> { pos++; yield Token.of(TokenType.LBRACE, start); }
                case '}' -> { pos++; yield Token.of(TokenType.RBRACE, start); }
                case '@' -> { pos++; yield Token.of(TokenType.AT, start); }
                case '^' -> { pos++; yield Token.of(TokenType.CARET, start); }
                case '|' -> { pos++; yield Token.of(TokenType.PIPE, start); }
                case '#' -> { pos++; yield Token.of(TokenType.HASH, start); }
                case '=' -> { pos++; yield Token.of(TokenType.EQUAL, start); }
                case '*' -> { yield lexStar(start); }
                case '.' -> { yield lexDot(start); }
                case ':' -> { yield lexColon(start); }
                case '<' -> { yield lexLess(start); }
                case '>' -> { yield lexGreater(start); }
                case '!' -> { yield lexBang(start); }
                case '~' -> { yield lexTilde(start); }
                case '$' -> { yield lexDollar(start); }
                case '"', '\'' -> { yield lexString(start); }
                case '`' -> { yield lexBacktickIdentifier(start); }
                default -> {
                    if (Character.isDigit(c)) yield lexNumber(start);
                    else if (isIdentStart(c))   yield lexIdentifierOrKeyword(start);
                    else throw new ParseException("Unexpected character: '" + c + "'", start);
                }
            };
            tokens.add(token);
        }
        tokens.add(Token.of(TokenType.EOF, pos));
        return List.copyOf(tokens);
    }

    // -------------------------------------------------------------------------
    // Multi-char token helpers
    // -------------------------------------------------------------------------

    private Token lexStar(int start) {
        pos++;
        if (pos < src.length() && src.charAt(pos) == '*') {
            pos++;
            return Token.of(TokenType.STAR_STAR, start);
        }
        return Token.of(TokenType.STAR, start);
    }

    private Token lexDot(int start) {
        pos++;
        if (pos < src.length() && src.charAt(pos) == '.') {
            pos++;
            return Token.of(TokenType.DOT_DOT, start);
        }
        return Token.of(TokenType.DOT, start);
    }

    private Token lexColon(int start) {
        pos++;
        if (pos < src.length() && src.charAt(pos) == '=') {
            pos++;
            return Token.of(TokenType.COLON_ASSIGN, start);
        }
        return Token.of(TokenType.COLON, start);
    }

    private Token lexLess(int start) {
        pos++;
        if (pos < src.length() && src.charAt(pos) == '=') {
            pos++;
            return Token.of(TokenType.LESS_EQUAL, start);
        }
        return Token.of(TokenType.LESS, start);
    }

    private Token lexGreater(int start) {
        pos++;
        if (pos < src.length() && src.charAt(pos) == '=') {
            pos++;
            return Token.of(TokenType.GREATER_EQUAL, start);
        }
        return Token.of(TokenType.GREATER, start);
    }

    private Token lexBang(int start) throws ParseException {
        pos++;
        if (pos < src.length() && src.charAt(pos) == '=') {
            pos++;
            return Token.of(TokenType.NOT_EQUAL, start);
        }
        throw new ParseException("Expected '=' after '!'", start);
    }

    private Token lexTilde(int start) throws ParseException {
        pos++;
        if (pos < src.length() && src.charAt(pos) == '>') {
            pos++;
            return Token.of(TokenType.TILDE_GT, start);
        }
        throw new ParseException("Expected '>' after '~'", start);
    }

    private Token lexDollar(int start) {
        pos++; // consume '$'
        if (pos < src.length() && src.charAt(pos) == '$') {
            pos++;
            return Token.of(TokenType.DOLLAR_DOLLAR, start);
        }
        // $name or bare $
        int nameStart = pos;
        while (pos < src.length() && isIdentPart(src.charAt(pos))) {
            pos++;
        }
        String name = src.substring(nameStart, pos);
        if (name.isEmpty()) {
            return Token.of(TokenType.DOLLAR, start);
        }
        return Token.of(TokenType.VARIABLE, name, start);
    }

    // -------------------------------------------------------------------------
    // String literals
    // -------------------------------------------------------------------------

    private Token lexString(int start) throws ParseException {
        char quote = src.charAt(pos);
        pos++; // consume opening quote
        StringBuilder sb = new StringBuilder();
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == quote) {
                pos++; // consume closing quote
                return Token.of(TokenType.STRING, sb.toString(), start);
            }
            if (c == '\\') {
                pos++;
                if (pos >= src.length()) {
                    throw new ParseException("Unterminated string escape", pos - 1);
                }
                char esc = src.charAt(pos++);
                sb.append(switch (esc) {
                    case '"'  -> '"';
                    case '\'' -> '\'';
                    case '\\' -> '\\';
                    case '/'  -> '/';
                    case 'b'  -> '\b';
                    case 'f'  -> '\f';
                    case 'n'  -> '\n';
                    case 'r'  -> '\r';
                    case 't'  -> '\t';
                    case 'u'  -> readUnicodeEscape(pos - 2);
                    default   -> throw new ParseException(
                            "Unknown escape sequence: \\" + esc, pos - 2);
                });
            } else {
                sb.append(c);
                pos++;
            }
        }
        throw new ParseException("Unterminated string literal", start);
    }

    private char readUnicodeEscape(int errorPos) throws ParseException {
        if (pos + 4 > src.length()) {
            throw new ParseException("Incomplete \\uXXXX escape", errorPos);
        }
        String hex = src.substring(pos, pos + 4);
        pos += 4;
        try {
            return (char) Integer.parseInt(hex, 16);
        } catch (NumberFormatException e) {
            throw new ParseException("Invalid \\uXXXX escape: " + hex, errorPos);
        }
    }

    // -------------------------------------------------------------------------
    // Backtick identifiers
    // -------------------------------------------------------------------------

    private Token lexBacktickIdentifier(int start) throws ParseException {
        pos++; // consume opening backtick
        int nameStart = pos;
        while (pos < src.length() && src.charAt(pos) != '`') {
            pos++;
        }
        if (pos >= src.length()) {
            throw new ParseException("Unterminated backtick identifier", start);
        }
        String name = src.substring(nameStart, pos);
        pos++; // consume closing backtick
        return Token.of(TokenType.IDENTIFIER, name, start);
    }

    // -------------------------------------------------------------------------
    // Numbers
    // -------------------------------------------------------------------------

    private Token lexNumber(int start) throws ParseException {
        int begin = pos;
        // Integer part
        while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
        // Decimal part
        if (pos < src.length() && src.charAt(pos) == '.' &&
                pos + 1 < src.length() && Character.isDigit(src.charAt(pos + 1))) {
            pos++; // consume '.'
            while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
        }
        // Exponent part
        if (pos < src.length() && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
            pos++;
            if (pos < src.length() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) pos++;
            if (pos >= src.length() || !Character.isDigit(src.charAt(pos))) {
                throw new ParseException("Malformed number exponent", start);
            }
            while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
        }
        return Token.of(TokenType.NUMBER, src.substring(begin, pos), start);
    }

    // -------------------------------------------------------------------------
    // Identifiers and keywords
    // -------------------------------------------------------------------------

    private Token lexIdentifierOrKeyword(int start) {
        int begin = pos;
        while (pos < src.length() && isIdentPart(src.charAt(pos))) pos++;
        String text = src.substring(begin, pos);
        TokenType kw = KEYWORDS.get(text);
        if (kw != null) {
            return Token.of(kw, text, start);
        }
        return Token.of(TokenType.IDENTIFIER, text, start);
    }

    // -------------------------------------------------------------------------
    // Whitespace and comments
    // -------------------------------------------------------------------------

    private void skipWhitespaceAndComments() throws ParseException {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (Character.isWhitespace(c)) {
                pos++;
            } else if (c == '/' && pos + 1 < src.length() && src.charAt(pos + 1) == '*') {
                skipBlockComment();
            } else {
                break;
            }
        }
    }

    private void skipBlockComment() throws ParseException {
        int start = pos;
        pos += 2; // skip /*
        while (pos + 1 < src.length()) {
            if (src.charAt(pos) == '*' && src.charAt(pos + 1) == '/') {
                pos += 2;
                return;
            }
            pos++;
        }
        throw new ParseException("Unterminated block comment", start);
    }

    // -------------------------------------------------------------------------
    // Character classification
    // -------------------------------------------------------------------------

    private static boolean isIdentStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isIdentPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}

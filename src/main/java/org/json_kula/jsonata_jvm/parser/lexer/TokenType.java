package org.json_kula.jsonata_jvm.parser.lexer;

/**
 * Lexical token types for the JSONata expression language.
 */
public enum TokenType {
    // Literals
    STRING,         // "hello", 'hello'
    NUMBER,         // 42, 3.14, 1e10
    TRUE,           // true
    FALSE,          // false
    NULL,           // null

    // References
    DOLLAR,         // $   (context value / root in function params)
    DOLLAR_DOLLAR,  // $$  (root of input document)
    VARIABLE,       // $name

    // Identifiers
    IDENTIFIER,     // field name or function name (bare or `backtick-quoted`)

    // Arithmetic operators
    PLUS,           // +
    MINUS,          // -
    STAR,           // *
    SLASH,          // /
    PERCENT,        // %

    // String concatenation
    AMPERSAND,      // &

    // Comparison operators
    EQUAL,          // =
    NOT_EQUAL,      // !=
    LESS,           // <
    LESS_EQUAL,     // <=
    GREATER,        // >
    GREATER_EQUAL,  // >=

    // Boolean keywords
    AND,            // and
    OR,             // or
    IN,             // in
    NOT,            // not  (keyword, used as function-like prefix)

    // Conditional / binding
    QUESTION,         // ?
    QUESTION_COLON,   // ?:  (Elvis / default operator)
    QUESTION_QUESTION,// ??  (Coalescing operator)
    COLON,            // :
    COLON_ASSIGN,     // :=

    // Path / step operators
    DOT,            // .
    DOT_DOT,        // ..  (not standard JSONata but reserved)
    STAR_STAR,      // **  (descendant wildcard)

    // Chain / transform
    TILDE_GT,       // ~>

    // Other operators
    PIPE,           // |   (transform / filter union)
    CARET,          // ^   (sort expression prefix)
    AT,             // @   (context binding in path steps)
    HASH,           // #   (position binding in path steps)

    // Delimiters
    LPAREN,         // (
    RPAREN,         // )
    LBRACKET,       // [
    RBRACKET,       // ]
    LBRACE,         // {
    RBRACE,         // }
    COMMA,          // ,
    SEMICOLON,      // ;   (function parameter separator)
    BACKTICK,       // `   (should never appear as standalone — absorbed into IDENTIFIER)

    // End of input
    EOF
}

---
name: java-parser-expert
description: >
  Expert guidance for Java senior developers building parsers, lexers, and language processors for
  context-free languages. Use this skill whenever the user is working on: grammar design (BNF/EBNF),
  choosing or using a parser generator (ANTLR4, JavaCC, Beaver, CUP), implementing lexers or
  tokenizers in Java, designing or traversing ASTs, handling parser errors or ambiguities, implementing
  language semantics, or any task involving formal language theory applied to Java code. Also trigger
  for questions about visitor/listener patterns on syntax trees, first/follow set computation,
  LL vs LR trade-offs, PEG grammars, left-recursion elimination, operator precedence encoding, or
  symbol table / scope management. When the user mentions parsing, tokenizing, grammar files (.g4,
  .jj, .cup), AST nodes, tree walkers, or language implementation in a Java context — use this skill.
---

# Java Parser Expert

You are acting as a senior language-tools engineer with deep experience designing and implementing parsers for context-free languages in Java. You understand the theory behind parsing (automata, formal grammars, closure algorithms) *and* the practical realities of shipping a real language tool. Ground your advice in both.

## Before answering: read the project

Before giving any recommendations, spend a moment looking at what already exists in the project. Check for:
- Existing grammar files (`.g4`, `.jj`, `.cup`)
- `pom.xml` or `build.gradle` for parser generator dependencies already in use
- Existing AST node classes or visitor interfaces
- `CLAUDE.md` for project description and stack

Concrete, project-specific advice (actual file paths, package names, existing class names) is far more useful than generic guidance. If you find relevant existing code, reference it directly.

## Core philosophy

- **Pick the right abstraction level.** A hand-written recursive-descent parser gives maximum control; ANTLR4 saves weeks for complex grammars. Match the tool to the need and explain the trade-off.
- **Grammar first.** Before writing a single line of Java, the grammar must be unambiguous, well-factored, and proven on paper (or with grun/ANTLR TestRig). Code bugs are cheap; grammar bugs are expensive.
- **Error recovery is a feature, not an afterthought.** A parser that only succeeds on valid input is half a parser.

---

## 1. Grammar Design

### BNF / EBNF essentials
- Use EBNF `()*`, `()+`, `()?` to remove auxiliary productions and show intent. Convert back to BNF only when the generator requires it.
- Eliminate **left recursion** before targeting LL parsers; keep it for LALR/LR (Yacc/CUP/Beaver handle it naturally).
- Factor common prefixes to avoid non-determinism in LL parsers. If `A → αβ | αγ`, rewrite as `A → α (β | γ)`.
- Use **precedence / associativity declarations** (in ANTLR4: implicit via rule order; in CUP/Beaver: explicit `%left`, `%right`, `%nonassoc`) rather than baroque grammar contortions to encode operator precedence.

### Ambiguity
- Ambiguity is often semantic disguised as syntactic. Detect it with ANTLR4's `TestRig -diagnostics`, or via the SLL→LL fallback becoming non-deterministic.
- Classic ambiguities: dangling-else (resolve with rule ordering in ANTLR4, or `%prec` in CUP/Beaver), overloaded operators, optional vs required productions sharing a prefix.
- When true ambiguity is *intended* (e.g., natural language fragment parsers), prefer GLR or Earley (see tool selection below).

---

## 2. Parser Types — When to Use What

| Algorithm | Strengths | Java tools | Sweet spot |
|---|---|---|---|
| LL(k) / ALL(*) | Top-down, natural recursion, great errors | ANTLR4 | Most DSLs, expression languages, config formats |
| LALR(1) | Handles more grammars than LL(1), well-studied | CUP, Beaver | SQL-like languages, classical compiler front-ends |
| LR(1) / GLR | Maximum expressive power | (hand-rolled or Bison+Java backend) | Grammars with local ambiguity |
| PEG / Packrat | No ambiguity by construction, O(n) with memoization | parboiled2, Pegdown | Markdown, lightweight markup, scannerless parsing |
| Earley | Handles *all* CFGs including ambiguous ones | (libraries: earley4j) | NLP-style grammars, research |
| Recursive descent | Full Java control, easy error messages | Hand-written | Simple DSLs, expression evaluators, fast prototypes |

**Default recommendation for new Java projects:** ANTLR4. Its ALL(*) algorithm handles nearly every practical grammar, the tooling (grun, IntelliJ ANTLR plugin, antlr4-maven-plugin) is excellent, and the Listener/Visitor split is clean.

---

## 3. ANTLR4 in Depth

### Grammar file layout (.g4)
```antlr
grammar MyLang;

// Parser rules (lowercase)
program : statement* EOF ;
statement : assignment | ifStmt | block ;
assignment : ID '=' expr ';' ;

// Lexer rules (UPPERCASE)
ID      : [a-zA-Z_][a-zA-Z_0-9]* ;
INT     : [0-9]+ ;
WS      : [ \t\r\n]+ -> skip ;
```

Key rules:
- Parser rules are lowercase; lexer rules are UPPERCASE.
- Rule ordering matters for lexer: first match wins. Put keywords *before* `ID`.
- Use **lexer modes** (`pushMode`, `popMode`) for island grammars (e.g., string interpolation).
- Use **semantic predicates** (`{condition}?`) sparingly — they work but break LL guarantee.

### Listener vs Visitor

| | Listener | Visitor |
|---|---|---|
| Driven by | `ParseTreeWalker` (depth-first auto) | You call `visit()` explicitly |
| Return value | void — side effects only | Generic return type `T` |
| Good for | Symbol table population, code gen, validation | Expression evaluation, tree rewriting |

For an expression evaluator (like JSONata), **use Visitor** — `visitExpr()` returns a value naturally.

```java
public class EvalVisitor extends MyLangBaseVisitor<Value> {
    @Override
    public Value visitAddExpr(MyLangParser.AddExprContext ctx) {
        Value left  = visit(ctx.expr(0));
        Value right = visit(ctx.expr(1));
        return left.add(right);
    }
}
```

### Two-parameter visitor `<R, C>` for multi-pass pipelines

When you need to thread context (a symbol table, scope, environment) through a tree walk without mutable visitor state, use a two-parameter visitor:

```java
public interface AstVisitor<R, C> {
    R visitBinaryOp(BinaryOp node, C context);
    R visitLiteral(Literal node, C context);
    R visitIdentifier(Identifier node, C context);
    // ...
}
```

`R` is the return type, `C` is the context passed down at each call. This makes passes composable and testable — each pass is a pure function from `(Node, Context) → Result` with no hidden state.

```java
// Type checker: returns resolved Type, passes SymbolTable as context
public class TypeChecker implements AstVisitor<Type, SymbolTable> {
    @Override
    public Type visitIdentifier(Identifier node, SymbolTable scope) {
        return scope.resolve(node.name())
            .orElseThrow(() -> new TypeError("Undefined: " + node.name()));
    }
}

// Evaluator: returns Value, passes EvalContext (variable bindings + input JSON)
public class Evaluator implements AstVisitor<Value, EvalContext> { ... }
```

The pattern also helps when passes need different context types — no need to cast or share state between them. Use it whenever a visitor pass needs more than trivial context, or when you anticipate multiple passes over the same AST.

### Error handling
ANTLR4 has built-in error recovery (single-token deletion/insertion, sync-and-return). To customize:
```java
parser.removeErrorListeners();
parser.addErrorListener(new BaseErrorListener() {
    @Override
    public void syntaxError(Recognizer<?,?> rec, Object sym,
                            int line, int col, String msg,
                            RecognitionException e) {
        throw new ParseException(line, col, msg);
    }
});
```
For **error recovery without exceptions**, implement `ANTLRErrorStrategy` or extend `DefaultErrorStrategy`.

---

## 4. JavaCC / JJTree

Use JavaCC when you need a self-contained parser with no runtime dependency. JJTree adds an AST layer on top.

```javacc
PARSER_BEGIN(MyParser)
package com.example;
public class MyParser {}
PARSER_END(MyParser)

void statement() :
{}
{
    assignment() | ifStmt()
}

void assignment() :
{ Token t; }
{
    t=<ID> "=" expr() ";"
    { System.out.println("Assigned: " + t.image); }
}
```

JavaCC is LL(k); increase lookahead with `LOOKAHEAD(k)` or `LOOKAHEAD(production)` for specific alternatives.

---

## 5. CUP / Beaver (LALR parsers)

CUP is the standard LALR(1) generator for Java. Good for languages where left-recursive grammar is natural (arithmetic, SQL).

```cup
terminal Integer NUMBER;
terminal PLUS, MINUS, TIMES;

non terminal Integer expr;

expr ::= expr:e1 PLUS  expr:e2 {: RESULT = e1 + e2; :}
       | expr:e1 MINUS expr:e2 {: RESULT = e1 - e2; :}
       | NUMBER:n              {: RESULT = n; :}
       ;
```

Use `%left`, `%right`, `%nonassoc` to declare precedence and avoid shift/reduce conflicts.

---

## 6. AST Design

### Node hierarchy pattern
```java
public sealed interface Expr permits Literal, BinaryOp, Identifier, CallExpr {}
public record Literal(Object value, Type type) implements Expr {}
public record BinaryOp(Expr left, String op, Expr right) implements Expr {}
```

- Use **sealed interfaces + records** (Java 17+) for algebraic data types. Pattern matching (`instanceof` + switch expressions) makes visitors near-boilerplate-free.
- Carry **source position** (`int line, int col`) in every node for error reporting.
- Keep AST nodes **immutable**. Tree transformations produce new trees — this makes passes composable and testable.

### Separation: CST → AST
ANTLR4's `ParseTree` is a CST (concrete syntax tree) — every grammar symbol has a node. Build a clean AST in a Visitor pass, discarding punctuation and flattening lists.

```java
@Override
public Expr visitParenExpr(MyLangParser.ParenExprContext ctx) {
    return visit(ctx.expr()); // drop the parens — they're structural, not semantic
}
```

---

## 7. Lexer / Tokenizer Implementation

### Hand-written lexer structure
```java
public class Lexer {
    private final String src;
    private int pos, line, col;

    public Token nextToken() {
        skipWhitespaceAndComments();
        if (pos >= src.length()) return token(EOF, "");
        char c = src.charAt(pos);
        if (Character.isDigit(c))  return readNumber();
        if (Character.isLetter(c)) return readIdentOrKeyword();
        return readPunctuation(c);
    }
}
```

Key considerations:
- **Maximal munch**: always consume the longest possible token (`>=` before `>`, `==` before `=`).
- **Keyword reservation**: lex as `ID`, then check against a `Set<String> KEYWORDS` — simpler than separate lexer rules.
- **Unicode**: use `Character.isLetter/isDigit` or explicit Unicode categories, not ASCII ranges, if your language needs it.

---

## 8. Error Recovery Strategies

| Strategy | Description | Best for |
|---|---|---|
| **Panic mode** | Skip tokens until a sync token (`}`, `;`, EOF) | Robust but loses context |
| **Phrase-level** | Replace/insert single token to continue | ANTLR4 default |
| **Error productions** | Grammar rules for common mistakes | Known mistake patterns |
| **Global correction** | Find minimum edit distance | Research / IDE tooling |

ANTLR4 `sync()` in the error strategy inserts virtual tokens and continues — useful for IDE integrations where you need a full (possibly error-containing) parse tree.

---

## 9. Language Semantics

### Symbol table and scoping
```java
public class Scope {
    private final Scope parent;
    private final Map<String, Symbol> symbols = new LinkedHashMap<>();

    public Optional<Symbol> resolve(String name) {
        return symbols.containsKey(name)
            ? Optional.of(symbols.get(name))
            : parent != null ? parent.resolve(name) : Optional.empty();
    }
}
```

Build the scope tree in a first Visitor/Listener pass, then resolve references in a second pass. Separating the passes avoids "forward reference" problems for most languages (C#-style, not Pascal-style).

### Type checking
- Model types as an algebraic hierarchy (sealed interface `Type` with implementations `PrimitiveType`, `FunctionType`, `ArrayType`, etc.).
- Implement a `TypeChecker` visitor that annotates AST nodes with their resolved type.
- Collect *all* type errors (don't short-circuit on first) using an `ErrorCollector`.

---

## Practical advice

- **Test grammars with `grun`** (ANTLR4 TestRig) before wiring them into Java. Visual tree output catches ambiguities immediately.
- **Fuzz your parser.** A production parser must not crash on garbage input. Use AFL or a simple random-mutation harness.
- **Profile before optimizing.** ANTLR4's LL fallback (`SLL → LL`) is the common hotspot for large inputs; memoization via `setPredictionMode(PredictionMode.SLL)` first with retry handles most cases.
- **Separate grammar concerns**: comments, whitespace, string escapes, and Unicode normalization should all be handled in the lexer, not the parser.
- **Version your grammar.** Grammar changes are breaking API changes. Tag grammar files, generate parsers into versioned packages.

# AGENTS.md - JSonata2Java Development Guide

See original spec in [CLAUDE.md](CLAUDE.md).

## Build, Test & Run Commands

### Maven Commands

```bash
# Build the project (compiles all sources)
mvn compile

# Run all tests
mvn test

# Run a single test class
mvn test -Dtest=ParserTest

# Run a single test method
mvn test -Dtest=ParserTest#test_validExpression

# Run tests with verbose output
mvn test -Dverbose

# Package as JAR
mvn package

# Clean build
mvn clean compile
```

### Quick Reference

| Task | Command |
|------|---------|
| Compile | `mvn compile` |
| Run all tests | `mvn test` |
| Run single test | `mvn test -Dtest=TestClassName#testMethod` |
| Package | `mvn package` |
| Clean | `mvn clean` |

## Code Style Guidelines

### General Rules

- **Java Version**: Java 21 (JDK required, not JRE)
- **Package Structure**:
  - `org.json_kula.jsonata_jvm` - Public API
  - `org.json_kula.jsonata_jvm.parser` - Parser
  - `org.json_kula.jsonata_jvm.parser.lexer` - Lexer/Tokenizer
  - `org.json_kula.jsonata_jvm.parser.ast` - AST nodes
  - `org.json_kula.jsonata_jvm.optimizer` - AST optimization
  - `org.json_kula.jsonata_jvm.translator` - Java code generation
  - `org.json_kula.jsonata_jvm.runtime` - Runtime helpers
  - `org.json_kula.jsonata_jvm.loader` - Class loading

### Naming Conventions

- **Classes**: PascalCase (e.g., `JsonataExpressionFactory`)
- **Interfaces**: PascalCase (e.g., `JsonataBoundFunction`)
- **Methods**: camelCase (e.g., `evaluate`, `getFunctionSignature`)
- **Variables**: camelCase (e.g., `expression`, `className`)
- **Constants**: UPPER_SNAKE_CASE (e.g., `GEN_PACKAGE`)
- **Packages**: lowercase with underscores (e.g., `json_kula`)

### Type Usage

- **Jackson Types**: Use `com.fasterxml.jackson.databind.JsonNode` as the primary type for JSON values
- **Wrapper Types**: Prefer wrapper classes for numbers (`Double`, `Integer`) when nullability matters
- **Records**: Use Java records for immutable AST nodes and data transfer objects
- **Sealed Interfaces**: Use sealed interfaces for AST node hierarchy with `permits` clause
- **Var**: Use `var` for local variables when type is obvious from right side

### Imports

```java
// Jackson - most common imports
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.ObjectMapper;

// Internal packages - use static imports where appropriate
import static org.json_kula.jsonata_jvm.runtime.JsonataRuntime.*;
```

### Formatting

- **Indentation**: 4 spaces (no tabs)
- **Line Length**: Soft limit 120 characters
- **Braces**: Same-line brace style
- **Blank Lines**: Single blank line between logical sections
- **Javadoc**: Required for public API; optional for private methods

### Error Handling

- **Checked Exceptions**: Use `JsonataCompilationException` for parse/compile errors, `JsonataEvaluationException` for runtime errors
- **Exception Messages**: Include descriptive message with cause when wrapping exceptions
- **Never swallow exceptions silently**: Always log or rethrow with context

### AST Pattern

The AST uses a sealed interface hierarchy:
- `AstNode` is a sealed interface with all node types as nested records
- Each node type has a two-parameter `Visitor<R,C>` interface
- Use the visitor pattern for tree traversal

```java
// Example: visiting AST nodes
AstNode result = node.accept(new AstNode.Visitor<>() {
    @Override
    public JsonNode visit(LiteralNode n, JsonNode ctx) { ... }
    @Override
    public JsonNode visit(FieldNode n, JsonNode ctx) { ... }
    // ... handle all node types
}, context);
```

### Translator Code Generation

- Generated classes import `static org.json_kula.jsonata_jvm.runtime.JsonataRuntime.*`
- Blocks with variable bindings emit as private helper methods
- Lambdas may be inlined or emitted as helper methods
- All generated code must be valid Java 21

### Runtime Patterns

- `JsonataRuntime` provides all static helper methods for generated classes
- `JsonataLambda` is a functional interface (`JsonNode apply(JsonNode)`)
- Use `JsonNode.isMissing()` and `JsonNode.isNull()` to distinguish missing vs null

### Testing

- Tests use JUnit 5 (`org.junit.jupiter.api.*`)
- Test classes follow pattern `*Test.java`
- Use `JsonataExpressionFactory` to compile expressions in tests
- Verify behavior against JSONata4Java reference implementation where applicable

### Thread Safety

- `JsonataExpression` instances must be thread-safe
- `evaluate()` should be stateless - no shared mutable state
- Compile expressions once at startup, reuse for evaluations

### Key Interfaces to Implement

| Interface | Purpose |
|-----------|---------|
| `JsonataExpression` | Main interface with `evaluate(json)` method |
| `JsonataBoundFunction` | For binding Java functions to expressions |
| `JsonataFunctionArguments` | Wrapper for function arguments |
| `JsonataBindings` | Container for named values and functions |

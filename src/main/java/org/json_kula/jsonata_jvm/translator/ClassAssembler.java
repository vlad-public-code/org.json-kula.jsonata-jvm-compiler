package org.json_kula.jsonata_jvm.translator;

import java.util.List;

/**
 * Java source file template builder for the code generator.
 *
 * <p>Assembles the scaffolding around the generated expression code.
 */
final class ClassAssembler {

    private ClassAssembler() {}

    static String buildClass(String pkg, String className,
                              String bodyExpr, String helperMethods,
                              String localDeclarations, String sourceExpression) {
        String pkgDecl = pkg.isEmpty() ? "" : "package " + pkg + ";\n\n";
        return pkgDecl
                + "import com.fasterxml.jackson.databind.JsonNode;\n"
                + "import com.fasterxml.jackson.databind.node.MissingNode;\n"
                + "import org.json_kula.jsonata_jvm.JsonataBindings;\n"
                + "import org.json_kula.jsonata_jvm.JsonataBoundFunction;\n"
                + "import org.json_kula.jsonata_jvm.JsonataEvaluationException;\n"
                + "import org.json_kula.jsonata_jvm.JsonataExpression;\n"
                + "import org.json_kula.jsonata_jvm.runtime.JsonataLambda;\n"
                + "import org.json_kula.jsonata_jvm.runtime.RuntimeEvaluationException;\n"
                + "import static org.json_kula.jsonata_jvm.runtime.JsonataRuntime.*;\n"
                + "import java.util.concurrent.ConcurrentHashMap;\n"
                + "\n"
                + "public final class " + className + " implements JsonataExpression {\n"
                + "\n"
                + "    private static final String __SOURCE = " + javaString(sourceExpression) + ";\n"
                + "\n"
                + "    private final ConcurrentHashMap<String, JsonNode> __values = new ConcurrentHashMap<>();\n"
                + "    private final ConcurrentHashMap<String, JsonataBoundFunction> __functions = new ConcurrentHashMap<>();\n"
                + "\n"
                + "    @Override\n"
                + "    public String getSourceJsonata() { return __SOURCE; }\n"
                + "\n"
                + "    @Override\n"
                + "    public void assign(String __name, JsonNode __value) { __values.put(__name, __value); }\n"
                + "\n"
                + "    @Override\n"
                + "    public void registerFunction(String __name, JsonataBoundFunction __fn) { __functions.put(__name, __fn); }\n"
                + "\n"
                + "    @Override\n"
                + "    public JsonNode evaluate(JsonNode __input) throws JsonataEvaluationException {\n"
                + "        return evaluate(__input, null);\n"
                + "    }\n"
                + "\n"
                + "    @Override\n"
                + "    public JsonNode evaluate(JsonNode __input, JsonataBindings __perEval) throws JsonataEvaluationException {\n"
                + "        beginEvaluation(__values, __functions, __perEval);\n"
                + "        try {\n"
                + "            if (__input == null) throw new JsonataEvaluationException(null, \"Input JSON cannot be null\");\n"
                + "            final JsonNode __root = __input;\n"
                + "            final JsonNode __ctx = __root;\n"
                + (localDeclarations.isEmpty() ? "" : "            " + localDeclarations)
                + "            JsonNode __result = " + bodyExpr + ";\n"
                + "            return __result;\n"
                + "        } catch (JsonataEvaluationException __e) {\n"
                + "            throw __e;\n"
                + "        } catch (RuntimeEvaluationException __e) {\n"
                + "            throw new JsonataEvaluationException(__e.getErrorCode(), __e.getMessage(), __e);\n"
                + "        } catch (Exception __e) {\n"
                + "            throw new JsonataEvaluationException(null, __e.getMessage(), __e);\n"
                + "        } finally {\n"
                + "            endEvaluation();\n"
                + "        }\n"
                + "    }\n"
                + helperMethods
                + "}\n";
    }

    /** Wraps {@code s} as a Java string literal with proper escaping. */
    static String javaString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default   -> sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    static String oneArg(List<String> args) {
        return args.isEmpty() ? "NULL" : args.get(0);
    }

    /**
     * Returns the first argument expression, or {@code ctxVar} when the argument
     * list is empty.  Use this for built-in functions whose JSONata signature carries
     * the {@code -} modifier, meaning "use the context value as the default argument".
     */
    static String ctxArg(List<String> args, String ctxVar) {
        return args.isEmpty() ? ctxVar : args.get(0);
    }
}

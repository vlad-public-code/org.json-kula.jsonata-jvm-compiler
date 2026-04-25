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
                + "import org.json_kula.jsonata_jvm.AbstractJsonataExpression;\n"
                + "import org.json_kula.jsonata_jvm.runtime.JsonataLambda;\n"
                + "import org.json_kula.jsonata_jvm.runtime.RuntimeEvaluationException;\n"
                + "import static org.json_kula.jsonata_jvm.runtime.JsonataRuntime.*;\n"
                + "import java.util.Map;\n"

                + "\n"
                + "public final class " + className + " extends AbstractJsonataExpression {\n"
                + "\n"
                + "    private static final String __SOURCE = " + javaString(sourceExpression) + ";\n"
                + "\n"
                + "    @Override\n"
                + "    public String getSourceJsonata() { return __SOURCE; }\n"
                + "\n"
                + "    @Override\n"
                + "    protected JsonNode doEvaluate(JsonNode __root) throws Exception {\n"
                + "        final JsonNode __ctx = __root;\n"
                + (localDeclarations.isEmpty() ? "" : "        " + localDeclarations)
                + "        JsonNode __result = " + bodyExpr + ";\n"
                + "        return __result;\n"
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

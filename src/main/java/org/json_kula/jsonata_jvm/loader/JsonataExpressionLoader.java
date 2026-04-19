package org.json_kula.jsonata_jvm.loader;

import org.json_kula.jsonata_jvm.JsonataExpression;

import javax.tools.*;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compiles a Java 21 source string into an instance of {@link JsonataExpression}.
 *
 * <p>The source must declare exactly one public class that implements
 * {@link JsonataExpression}. Compilation happens entirely in memory — no
 * temporary files are written to disk.
 *
 * <p>This class is thread-safe: each {@link #load} call is fully independent.
 *
 * <p>Requires a JDK at runtime (not just a JRE) so that
 * {@link ToolProvider#getSystemJavaCompiler()} returns a non-null compiler.
 */
public class JsonataExpressionLoader {

    private static final Pattern PACKAGE_PATTERN =
            Pattern.compile("\\bpackage\\s+([\\w.]+)\\s*;");
    private static final Pattern CLASS_PATTERN =
            Pattern.compile("\\bclass\\s+(\\w+)");

    private static final List<String> COMPILE_OPTIONS = List.of(
            "--release", "21", "-classpath", System.getProperty("java.class.path"));

    /**
     * Compiles {@code javaSource} and returns a new instance of the class it defines.
     *
     * @param javaSource full text of a Java 21 source file whose top-level class
     *                   implements {@link JsonataExpression}
     * @return a fresh instance of the compiled class
     * @throws JsonataLoadException if the source cannot be compiled, the class does
     *                              not implement {@link JsonataExpression}, or
     *                              instantiation fails
     */
    public JsonataExpression load(String javaSource) throws JsonataLoadException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new JsonataLoadException(
                    "Java compiler not available — run on a JDK, not a JRE.");
        }

        String className = extractClassName(javaSource);

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        Map<String, byte[]> classBytes;
        try (InMemoryFileManager fileManager = new InMemoryFileManager(
                compiler.getStandardFileManager(diagnostics, null, null))) {

            JavaCompiler.CompilationTask task = compiler.getTask(
                    null,
                    fileManager,
                    diagnostics,
                    COMPILE_OPTIONS,
                    null,
                    List.of(new InMemorySourceFile(className, javaSource)));

            if (!task.call()) {
                StringBuilder sb = new StringBuilder("Compilation failed for class '")
                        .append(className).append("':\n");
                for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                    if (d.getKind() == Diagnostic.Kind.ERROR) {
                        sb.append("  Line ").append(d.getLineNumber())
                          .append(": ").append(d.getMessage(null)).append('\n');
                    }
                }
                throw new JsonataLoadException(sb.toString().stripTrailing());
            }

            classBytes = fileManager.classBytes();
        } catch (java.io.IOException e) {
            throw new JsonataLoadException("Failed to close file manager: " + e.getMessage(), e);
        }

        return instantiate(className, classBytes);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static String extractClassName(String source) throws JsonataLoadException {
        Matcher classMatcher = CLASS_PATTERN.matcher(source);
        if (!classMatcher.find()) {
            throw new JsonataLoadException("Cannot find a class declaration in the provided source.");
        }
        String simpleName = classMatcher.group(1);

        Matcher pkgMatcher = PACKAGE_PATTERN.matcher(source);
        return pkgMatcher.find()
                ? pkgMatcher.group(1) + "." + simpleName
                : simpleName;
    }

    private static JsonataExpression instantiate(String className,
                                                  Map<String, byte[]> classBytes)
            throws JsonataLoadException {
        InMemoryClassLoader classLoader = new InMemoryClassLoader(classBytes);
        try {
            Class<?> clazz = classLoader.loadClass(className);
            Object instance = clazz.getDeclaredConstructor().newInstance();
            if (!(instance instanceof JsonataExpression expr)) {
                throw new JsonataLoadException(
                        "Class '" + className + "' does not implement JsonataExpression.");
            }
            return expr;
        } catch (ReflectiveOperationException e) {
            throw new JsonataLoadException(
                    "Failed to instantiate '" + className + "': " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // In-memory javax.tools infrastructure
    // -------------------------------------------------------------------------

    private static final class InMemorySourceFile extends SimpleJavaFileObject {

        private final String source;

        InMemorySourceFile(String className, String source) {
            super(URI.create("string:///" + className.replace('.', '/') + Kind.SOURCE.extension),
                  Kind.SOURCE);
            this.source = source;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
        }
    }

    private static final class InMemoryClassFile extends SimpleJavaFileObject {

        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        InMemoryClassFile(String className) {
            super(URI.create("mem:///" + className.replace('.', '/') + Kind.CLASS.extension),
                  Kind.CLASS);
        }

        @Override
        public OutputStream openOutputStream() {
            return bytes;
        }

        byte[] toByteArray() {
            return bytes.toByteArray();
        }
    }

    private static final class InMemoryFileManager
            extends ForwardingJavaFileManager<StandardJavaFileManager> {

        private final Map<String, InMemoryClassFile> files = new HashMap<>();

        InMemoryFileManager(StandardJavaFileManager delegate) {
            super(delegate);
        }

        @Override
        public JavaFileObject getJavaFileForOutput(Location location,
                                                   String className,
                                                   JavaFileObject.Kind kind,
                                                   FileObject sibling) {
            InMemoryClassFile file = new InMemoryClassFile(className);
            files.put(className, file);
            return file;
        }

        Map<String, byte[]> classBytes() {
            Map<String, byte[]> result = new HashMap<>(files.size());
            files.forEach((name, file) -> result.put(name, file.toByteArray()));
            return result;
        }
    }

    private static final class InMemoryClassLoader extends ClassLoader {

        private final Map<String, byte[]> classBytes;

        InMemoryClassLoader(Map<String, byte[]> classBytes) {
            // Parent = this class's loader so the generated class can see
            // JsonataExpression, Jackson, etc. from the same classpath.
            super(JsonataExpressionLoader.class.getClassLoader());
            this.classBytes = classBytes;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = classBytes.get(name);
            if (bytes == null) {
                throw new ClassNotFoundException(name);
            }
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}

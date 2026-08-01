package org.json_kula.jsonata_jvm.loader;

import org.json_kula.jsonata_jvm.JsonataExpression;

import javax.tools.*;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compiles Java 21 source strings into instances of {@link JsonataExpression}.
 *
 * <p>Each source must declare exactly one public top-level class that implements
 * {@link JsonataExpression}. Compilation happens entirely in memory — no
 * temporary files are written to disk.
 *
 * <p>{@link #load} compiles a single source; {@link #loadAll} compiles a whole batch in one
 * {@code javac} invocation, amortising the compiler's large fixed per-invocation cost across every
 * expression in the batch rather than paying it once per expression.
 *
 * <p>This class is thread-safe: each {@link #load} / {@link #loadAll} call is fully independent.
 *
 * <p>Requires a JDK at runtime (not just a JRE) so that
 * {@link ToolProvider#getSystemJavaCompiler()} returns a non-null compiler.
 */
public class JsonataExpressionLoader {

    private static final Pattern PACKAGE_PATTERN =
            Pattern.compile("\\bpackage\\s+([\\w.]+)\\s*;");
    private static final Pattern CLASS_PATTERN =
            Pattern.compile("\\bclass\\s+(\\w+)");

    /**
     * Builds the compilation classpath from all available sources:
     * the {@code java.class.path} system property (set by surefire, Gradle, etc.)
     * plus any additional URLs from the thread-context and own classloaders
     * (needed when running inside exec:java or other embedding environments).
     */
    private static String buildClasspath() {
        Set<String> entries = new LinkedHashSet<>();
        String sysCp = System.getProperty("java.class.path", "");
        if (!sysCp.isEmpty()) {
            Collections.addAll(entries, sysCp.split(Pattern.quote(File.pathSeparator)));
        }
        collectUrlClassLoaderEntries(Thread.currentThread().getContextClassLoader(), entries);
        collectUrlClassLoaderEntries(JsonataExpressionLoader.class.getClassLoader(), entries);
        return String.join(File.pathSeparator, entries);
    }

    private static void collectUrlClassLoaderEntries(ClassLoader cl, Set<String> out) {
        for (ClassLoader c = cl; c != null; c = c.getParent()) {
            if (c instanceof URLClassLoader ucl) {
                for (URL url : ucl.getURLs()) {
                    try { out.add(Paths.get(url.toURI()).toString()); }
                    catch (Exception e) { out.add(url.getPath()); }
                }
            }
        }
    }

    // Computed once per class load; the loader is thread-safe and stateless so this is fine.
    private static final List<String> COMPILE_OPTIONS =
            List.of("--release", "21", "-classpath", buildClasspath());

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
        return loadAll(List.of(javaSource)).get(0);
    }

    /**
     * Compiles a batch of Java sources in a <b>single</b> {@code javac} invocation and returns one
     * {@link JsonataExpression} per source, in input order.
     *
     * <p>Each {@code javac} invocation pays a large fixed cost — bootstrapping the compiler,
     * reading the platform symbol file, and indexing the (Spring-sized) classpath — that dwarfs the
     * marginal cost of a single small generated class. Compiling N sources one at a time pays that
     * fixed cost N times; batching pays it once, which is the dominant win when a model registers
     * its dozens of expressions up front. See {@code JsonataExpressionFactory#compileAll}.
     *
     * <p>Every source must declare a distinctly-named top-level class implementing
     * {@link JsonataExpression}; the translator guarantees this by minting a globally-unique class
     * name per expression. All classes produced by the batch share one classloader — safe because
     * the names do not collide, and cheaper on metaspace than one loader per expression.
     *
     * <p>Failure semantics match {@link #load}: any source that cannot be parsed for its class name,
     * fails to compile, or cannot be instantiated aborts the whole batch with a
     * {@link JsonataLoadException}. A batch compile error names every class in the batch and each
     * diagnostic's originating source, so the offending expression is still identifiable.
     *
     * @param javaSources full texts of the Java 21 source files to compile together
     * @return one fresh {@link JsonataExpression} per source, in the same order; empty if the input
     *         is empty
     * @throws JsonataLoadException if any source cannot be compiled, does not implement
     *                              {@link JsonataExpression}, or cannot be instantiated
     */
    public List<JsonataExpression> loadAll(List<String> javaSources) throws JsonataLoadException {
        if (javaSources.isEmpty()) {
            return List.of();
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new JsonataLoadException(
                    "Java compiler not available — run on a JDK, not a JRE.");
        }

        // Extract each source's top-level class name up front (order-preserving) so a source with
        // no class declaration fails fast, before the compiler is even invoked.
        List<String> classNames = new ArrayList<>(javaSources.size());
        List<JavaFileObject> sourceFiles = new ArrayList<>(javaSources.size());
        for (String javaSource : javaSources) {
            String className = extractClassName(javaSource);
            classNames.add(className);
            sourceFiles.add(new InMemorySourceFile(className, javaSource));
        }

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
                    sourceFiles);

            if (!task.call()) {
                throw new JsonataLoadException(formatCompileErrors(classNames, diagnostics));
            }

            classBytes = fileManager.classBytes();

            // Fail loud on a degraded compile: a resource-exhausted in-process javac can return
            // success from task.call() yet emit no bytecode for a class. Left unchecked, the class
            // load fails later with an opaque error — or, worse, a partially-written class evaluates
            // wrongly. Verify every expected top-level class produced non-empty bytecode up front.
            for (String expected : classNames) {
                byte[] bytes = classBytes.get(expected);
                if (bytes == null || bytes.length == 0) {
                    throw new JsonataLoadException(
                            "Compiler reported success but produced no bytecode for class '" + expected
                            + "' (batch of " + classNames.size() + "). This usually means the in-process "
                            + "javac ran out of resources — run on a JVM with more metaspace/heap.");
                }
            }
        } catch (java.io.IOException e) {
            throw new JsonataLoadException("Failed to close file manager: " + e.getMessage(), e);
        }

        // One classloader for the whole batch: the top-level class names are globally unique, so
        // there is no collision, and a single loader keeps metaspace lower than one per expression.
        InMemoryClassLoader classLoader = new InMemoryClassLoader(classBytes);
        List<JsonataExpression> result = new ArrayList<>(classNames.size());
        for (String className : classNames) {
            result.add(instantiate(className, classLoader));
        }
        return List.copyOf(result);
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
                                                  ClassLoader classLoader)
            throws JsonataLoadException {
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

    /**
     * Builds a human-readable message from the ERROR diagnostics of a failed batch compile. Names
     * every class in the batch and attributes each error to its originating source file so that,
     * even when many expressions compile together, the offending one is identifiable.
     */
    private static String formatCompileErrors(List<String> classNames,
                                              DiagnosticCollector<JavaFileObject> diagnostics) {
        StringBuilder sb = new StringBuilder("Compilation failed for ")
                .append(classNames.size() == 1
                        ? "class '" + classNames.get(0) + "'"
                        : classNames.size() + " classes " + classNames)
                .append(":\n");
        for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
            if (d.getKind() == Diagnostic.Kind.ERROR) {
                JavaFileObject source = d.getSource();
                if (source != null) {
                    sb.append("  ").append(simpleSourceName(source.getName())).append(' ');
                } else {
                    sb.append("  ");
                }
                sb.append("Line ").append(d.getLineNumber())
                  .append(": ").append(d.getMessage(null)).append('\n');
            }
        }
        return sb.toString().stripTrailing();
    }

    /** The trailing {@code File.java} of a generated source URI, for compact error attribution. */
    private static String simpleSourceName(String uri) {
        int slash = uri.lastIndexOf('/');
        return slash >= 0 ? uri.substring(slash + 1) : uri;
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

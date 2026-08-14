package org.json_kula.jsonata_jvm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import static org.json_kula.jsonata_jvm.JsonNodeTestHelper.EMPTY_OBJECT;
import static org.junit.jupiter.api.Assertions.*;

public class JsonataTestSuiteTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TEST_SUITE_PATH = "jsonata-test-suite";
    private static JsonNode[] DATASETS;
    private static java.util.Map<String, JsonNode> NAMED_DATASETS;
    private static JsonataExpressionFactory FACTORY;

    /** Expressions per {@code javac} invocation during {@link #precompile}. */
    private static final int COMPILE_BATCH = 250;

    /** Pre-compiled expressions, keyed by source text. Shared by every case that uses one. */
    private static final Map<String, JsonataExpression> COMPILED = new ConcurrentHashMap<>();

    @BeforeAll
    static void loadDatasets() throws IOException {
        FACTORY = new JsonataExpressionFactory();

        Path datasetsDir = Path.of("src/test/resources", TEST_SUITE_PATH, "datasets");
        List<JsonNode> datasetList = new ArrayList<>();
        NAMED_DATASETS = new java.util.HashMap<>();

        Files.list(datasetsDir)
            .filter(p -> p.toString().endsWith(".json"))
            .sorted(Comparator.comparingInt(p -> extractDatasetIndex(p.getFileName().toString())))
            .forEach(p -> {
                try {
                    JsonNode data = MAPPER.readTree(p.toFile());
                    String name = p.getFileName().toString().replace(".json", "");
                    NAMED_DATASETS.put(name, data);
                    datasetList.add(data);
                } catch (IOException e) {
                    System.err.println("Failed to load dataset: " + p);
                }
            });

        DATASETS = datasetList.toArray(new JsonNode[0]);
    }

    private static int extractDatasetIndex(String filename) {
        String numStr = filename.replaceAll(".*dataset(\\d+)\\.json.*", "$1");
        try {
            return Integer.parseInt(numStr);
        } catch (NumberFormatException e) {
            return 9999;
        }
    }

    @TestFactory
    Stream<DynamicTest> runAllTestCases() throws IOException {
        Path groupsDir = Path.of("src/test/resources", TEST_SUITE_PATH, "groups");
        List<Path> testFiles;
        try (Stream<Path> walk = Files.walk(groupsDir)) {
            testFiles = walk.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }

        precompile(testFiles);

        List<DynamicTest> tests = new ArrayList<>();
        for (Path testFile : testFiles) {
            String testName = groupsDir.relativize(testFile).toString().replace('\\', '/');
            tests.add(DynamicTest.dynamicTest(testName, () -> runTestCaseByFile(testFile)));
        }
        return tests.stream();
    }

    /**
     * Compiles every expression the suite expects to compile, in batches.
     *
     * <p>{@code javac} costs far more per invocation than per class, so compiling ~1 500 expressions
     * one at a time dominated this suite's wall time. Batching them cuts it several-fold.
     *
     * <p>Cases that expect a <em>compilation error</em> are left out: a batch aborts as a whole, so a
     * deliberately invalid expression would take its batch with it. Those still compile on demand in
     * {@link #evaluate}, which is where their error is asserted. If a batch fails anyway — a real
     * regression — its expressions are recompiled one by one, so the failure lands on the case that
     * owns it rather than on every case in the batch.
     */
    private static void precompile(List<Path> testFiles) {
        LinkedHashSet<String> expressions = new LinkedHashSet<>();
        for (Path testFile : testFiles) {
            try {
                JsonNode content = MAPPER.readTree(testFile.toFile());
                for (JsonNode testCase : content.isArray() ? content : List.of(content)) {
                    if (expectsError(testCase)) continue;
                    String expression = expressionOf(testFile, testCase);
                    if (expression != null) expressions.add(expression);
                }
            } catch (IOException e) {
                // A file that cannot be read fails its own test, with a better message than here.
            }
        }

        List<String> pending = new ArrayList<>(expressions);
        for (int start = 0; start < pending.size(); start += COMPILE_BATCH) {
            List<String> batch = pending.subList(start, Math.min(start + COMPILE_BATCH, pending.size()));
            try {
                List<JsonataExpression> compiled = FACTORY.compileAll(batch);
                for (int i = 0; i < batch.size(); i++) COMPILED.put(batch.get(i), compiled.get(i));
            } catch (JsonataCompilationException batchFailure) {
                for (String expression : batch) {
                    try {
                        COMPILED.put(expression, FACTORY.compile(expression));
                    } catch (JsonataCompilationException individualFailure) {
                        // Left out on purpose: the case that uses it compiles on demand and reports.
                    }
                }
            }
        }
    }

    /** True if the case asserts an error code rather than a value. */
    private static boolean expectsError(JsonNode testCase) {
        return testCase.has("code")
                || (testCase.has("error") && testCase.path("error").has("code"));
    }

    /** The expression a case runs, read from {@code expr} or {@code expr-file}. */
    private static String expressionOf(Path testFile, JsonNode testCase) {
        try {
            if (testCase.has("expr-file")) {
                return Files.readString(testFile.resolveSibling(testCase.get("expr-file").asText()));
            }
            return testCase.has("expr") ? testCase.get("expr").asText() : null;
        } catch (IOException e) {
            return null;
        }
    }

    private void runTestCaseByFile(Path testFile) throws IOException {
        JsonNode testCase = MAPPER.readTree(testFile.toFile());
        if (testCase.isArray()) {
            for (JsonNode subTestCase : testCase) {
                runTestCaseWithTimelimit(testFile, subTestCase);
            }
        }
        else {
            runTestCaseWithTimelimit(testFile, testCase);
        }
    }

    private void runTestCaseWithTimelimit(Path testFile, JsonNode testCase) throws IOException {
        JsonNode tl = testCase.get("timelimit");
        if (tl != null && tl.isNumber()) {
            // Use the spec timelimit as a hard wall-clock deadline.
            // assertTimeoutPreemptively runs the evaluation on a fresh thread;
            // EvaluationContext sets up its ThreadLocals inside evaluate() itself, so this is safe.
            assertTimeoutPreemptively(Duration.ofMillis(tl.longValue()), () -> runTestCase(testFile, testCase),
                    "Test case exceeded timelimit of " + tl.longValue() + " ms: " + testFile);
        } else {
            runTestCase(testFile, testCase);
        }
    }

    private void runTestCase(Path testFile, JsonNode testCase) throws IOException {
        String expression;
        if (testCase.has("expr-file")) {
            Path exprFile = testFile.resolveSibling(testCase.get("expr-file").asText());
            expression = Files.readString(exprFile);
        } else {
            expression = testCase.get("expr").asText();
        }
        
        JsonNode data = getData(testCase);
        JsonNode bindings = testCase.has("bindings") ? testCase.get("bindings") : MAPPER.createObjectNode();
        
        JsonNode expectedResult = testCase.has("result") ? testCase.get("result") : null;
        boolean undefinedResult = testCase.has("undefinedResult") && testCase.get("undefinedResult").asBoolean();
        String expectedCode = testCase.has("code") ? testCase.get("code").asText()
                           : testCase.has("error") ? testCase.get("error").path("code").asText(null)
                           : null;
        
        try {
            JsonNode result = evaluate(expression, data, bindings);
            
            if (expectedCode != null) {
                fail("Expected error code '" + expectedCode + "' but got result: " + result + " for expression: " + expression);
            }
            
            if (undefinedResult) {
                assertTrue(result.isMissingNode(), "Expected undefined result, got: " + result +  " for expression: " + expression);
            } else if (expectedResult != null) {
                JsonNodeTestHelper.assertJsonEquals(expectedResult, result, "Expression: " + expression);
            } else {
                fail("Expected either result, undefinedResult, or code. Got: " + testCase + " for expression: " + expression);
            }
            
        } catch (JsonataCompilationException | JsonataEvaluationException e) {
            if (expectedCode != null) {
                String actualCode = e.getErrorCode();
                assertEquals(expectedCode, actualCode, "Error code mismatch for expression: " + expression);
            } else {
                // Covers both "expected a result" and "the case declares no expectation at all" —
                // the latter must not pass silently just because the expression happened to throw.
                fail("Expected success but got error: " + e.getMessage() +  " for expression: " + expression);
            }
        }
    }

    private JsonNode getData(JsonNode testCase) throws IOException {
        if (testCase.has("data")) {
            return testCase.get("data");
        }
        
        if (testCase.has("dataset")) {
            JsonNode datasetName = testCase.get("dataset");
            if (datasetName.isNull()) {
                return MissingNode.getInstance();
            }
            String name = datasetName.asText();
            if (NAMED_DATASETS.containsKey(name)) {
                return NAMED_DATASETS.get(name);
            }
            int idx = Integer.parseInt(name.replace("dataset", ""));
            return DATASETS[idx];
        }
        
        return EMPTY_OBJECT;
    }

    private JsonNode evaluate(String expression, JsonNode data, JsonNode bindings)
            throws JsonataCompilationException, JsonataEvaluationException {
        JsonataBindings jsonataBindings = new JsonataBindings();
        
        bindings.fields().forEachRemaining(entry -> {
            jsonataBindings.bindValue(entry.getKey(), entry.getValue());
        });
        
        JsonataExpression compiled = COMPILED.get(expression);
        if (compiled == null) compiled = FACTORY.compile(expression);
        return compiled.evaluate(data, jsonataBindings);
    }
}
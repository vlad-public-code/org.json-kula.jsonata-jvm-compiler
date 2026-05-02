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
import java.util.List;
import java.util.stream.Stream;

import static org.json_kula.jsonata_jvm.JsonNodeTestHelper.EMPTY_OBJECT;
import static org.junit.jupiter.api.Assertions.*;

public class JsonataTestSuiteTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TEST_SUITE_PATH = "jsonata-test-suite";
    private static JsonNode[] DATASETS;
    private static java.util.Map<String, JsonNode> NAMED_DATASETS;
    private static JsonataExpressionFactory FACTORY;

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
        List<DynamicTest> tests = new ArrayList<>();
        
        Files.walk(groupsDir)
            .filter(p -> p.toString().endsWith(".json"))
            .filter(p -> !p.toString().endsWith(".jsonata"))
            .filter(p -> !p.getFileName().toString().contains("sequences"))
            .filter(p -> !p.getFileName().toString().contains("large.json"))
            .sorted()
            .forEach(testFile -> {
                String testName = groupsDir.relativize(testFile).toString().replace('\\', '/');
                
                DynamicTest test = DynamicTest.dynamicTest(testName, () -> {
                    runTestCaseByFile(testFile);
                });
                tests.add(test);
            });
        
        return tests.stream();
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
            } else if (undefinedResult || expectedResult != null) {
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
        
        JsonataExpression compiled = FACTORY.compile(expression);
        return compiled.evaluate(data, jsonataBindings);
    }
}
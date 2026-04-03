package org.json_kula.jsonata_jvm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.json_kula.jsonata_jvm.JsonNodeTestHelper.EMPTY_OBJECT;
import static org.junit.jupiter.api.Assertions.*;

@Disabled("Please start it manually")
public class JsonataTestSuiteTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TEST_SUITE_PATH = "jsonata-test-suite";
    private static JsonNode[] DATASETS;
    private static JsonataExpressionFactory FACTORY;

    @BeforeAll
    static void loadDatasets() throws IOException {
        FACTORY = new JsonataExpressionFactory();
        
        Path datasetsDir = Path.of("src/test/resources", TEST_SUITE_PATH, "datasets");
        List<JsonNode> datasetList = new ArrayList<>();
        
        Files.list(datasetsDir)
            .filter(p -> p.toString().endsWith(".json"))
            .sorted()
            .forEach(p -> {
                try {
                    JsonNode data = MAPPER.readTree(p.toFile());
                    datasetList.add(data);
                } catch (IOException e) {
                    System.err.println("Failed to load dataset: " + p);
                }
            });
        
        DATASETS = datasetList.toArray(new JsonNode[0]);
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
                    runTestCase(testFile);
                });
                tests.add(test);
            });
        
        return tests.stream();
    }

    private void runTestCase(Path testFile) throws IOException {
        JsonNode testCase = MAPPER.readTree(testFile.toFile());
        String expression = testCase.get("expr").asText();
        
        JsonNode data = getData(testCase);
        JsonNode bindings = testCase.has("bindings") ? testCase.get("bindings") : MAPPER.createObjectNode();
        
        JsonNode expectedResult = testCase.has("result") ? testCase.get("result") : null;
        boolean undefinedResult = testCase.has("undefinedResult") && testCase.get("undefinedResult").asBoolean();
        String expectedCode = testCase.has("code") ? testCase.get("code").asText() : null;
        
        try {
            JsonNode result = evaluate(expression, data, bindings);
            
            if (expectedCode != null) {
                fail("Expected error code '" + expectedCode + "' but got result: " + result);
            }
            
            if (undefinedResult) {
                assertTrue(result.isMissingNode(), "Expected undefined result, got: " + result);
            } else if (expectedResult != null) {
                JsonNodeTestHelper.assertJsonEquals(expectedResult, result, "Expression: " + expression);
            } else {
                fail("Expected either result, undefinedResult, or code. Got: " + testCase);
            }
            
        } catch (RuntimeException e) {
            if (expectedCode != null) {
                String actualCode = extractErrorCode(e.getMessage());
                assertEquals(expectedCode, actualCode, "Error code mismatch for expression: " + expression);
            } else if (undefinedResult || expectedResult != null) {
                fail("Expected success but got error: " + e.getMessage());
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
                return EMPTY_OBJECT;
            }
            String name = datasetName.asText();
            if (name.equals("employees")) {
                return DATASETS[27];
            } else if (name.equals("items")) {
                return DATASETS[28];
            } else if (name.equals("library")) {
                return DATASETS[29];
            }
            
            int idx = Integer.parseInt(name.replace("dataset", ""));
            return DATASETS[idx];
        }
        
        return EMPTY_OBJECT;
    }

    private JsonNode evaluate(String expression, JsonNode data, JsonNode bindings) {
        JsonataBindings jsonataBindings = new JsonataBindings();
        
        bindings.fields().forEachRemaining(entry -> {
            jsonataBindings.bindValue(entry.getKey(), entry.getValue());
        });
        
        try {
            JsonataExpression compiled = FACTORY.compile(expression);
            return compiled.evaluate(data, jsonataBindings);
        } catch (JsonataCompilationException | JsonataEvaluationException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    private String extractErrorCode(String message) {
        if (message.contains("T0001")) return "T0001";
        if (message.contains("T0002")) return "T0002";
        if (message.contains("T0101")) return "T0101";
        if (message.contains("T0102")) return "T0102";
        if (message.contains("T0103")) return "T0103";
        if (message.contains("T0104")) return "T0104";
        if (message.contains("T0110")) return "T0110";
        if (message.contains("T0111")) return "T0111";
        if (message.contains("T0201")) return "T0201";
        if (message.contains("T0202")) return "T0202";
        if (message.contains("T0203")) return "T0203";
        if (message.contains("T0204")) return "T0204";
        if (message.contains("T0205")) return "T0205";
        if (message.contains("T0301")) return "T0301";
        if (message.contains("T0400")) return "T0400";
        if (message.contains("T0401")) return "T0401";
        if (message.contains("T0410")) return "T0410";
        if (message.contains("T0411")) return "T0411";
        if (message.contains("T0500")) return "T0500";
        if (message.contains("T0600")) return "T0600";
        if (message.contains("T0700")) return "T0700";
        if (message.contains("S0001")) return "S0001";
        if (message.contains("S0002")) return "S0002";
        if (message.contains("S0003")) return "S0003";
        if (message.contains("S0200")) return "S0200";
        if (message.contains("S0300")) return "S0300";
        
        return "UNKNOWN";
    }
}
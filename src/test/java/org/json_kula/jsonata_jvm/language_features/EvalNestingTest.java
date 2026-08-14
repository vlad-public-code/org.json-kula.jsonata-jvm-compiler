package org.json_kula.jsonata_jvm.language_features;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import org.json_kula.jsonata_jvm.JsonataBindings;
import org.json_kula.jsonata_jvm.JsonataExpression;
import org.json_kula.jsonata_jvm.JsonataExpressionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.json_kula.jsonata_jvm.JsonNodeTestHelper.EMPTY_OBJECT;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code $eval} evaluates an expression <em>inside</em> the evaluation that called it.
 *
 * <p>The evaluation frame used to be a single slot per thread, so the inner evaluation overwrote the
 * outer one and cleared it on the way out: everything the enclosing expression had bound — its
 * variables, its functions, its per-evaluation bindings — vanished from the point of the
 * {@code $eval} onwards. These tests pin the nesting.
 */
class EvalNestingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static JsonataExpressionFactory FACTORY;

    @BeforeAll
    static void setup() {
        FACTORY = new JsonataExpressionFactory();
    }

    private JsonNode eval(String expression) throws Exception {
        return FACTORY.compile(expression).evaluate(EMPTY_OBJECT);
    }

    @Test
    void userFunctionDefinedBeforeEval_isStillCallableAfterIt() throws Exception {
        assertEquals(6, eval("( $g := function($a){ $a * 2 }; $eval(\"1+1\"); $g(3) )").intValue());
    }

    @Test
    void permanentBinding_survivesEval() throws Exception {
        JsonataExpression expr = FACTORY.compile("( $eval(\"1+1\"); $v )");
        expr.assign("v", IntNode.valueOf(42));
        assertEquals(42, expr.evaluate(EMPTY_OBJECT).intValue());
    }

    @Test
    void perEvaluationBinding_survivesEval() throws Exception {
        JsonataExpression expr = FACTORY.compile("( $eval(\"1+1\"); $v * 2 )");
        JsonataBindings bindings = new JsonataBindings().bindValue("v", IntNode.valueOf(21));
        assertEquals(42, expr.evaluate(EMPTY_OBJECT, bindings).intValue());
    }

    @Test
    void boundFunction_survivesEval() throws Exception {
        JsonataExpression expr = FACTORY.compile("( $eval(\"1+1\"); $double(21) )");
        expr.registerFunction("double", new org.json_kula.jsonata_jvm.JsonataBoundFunction() {
            public String getFunctionSignature() { return "<n:n>"; }
            public JsonNode apply(org.json_kula.jsonata_jvm.JsonataFunctionArguments args) {
                return IntNode.valueOf(args.get(0).intValue() * 2);
            }
        });
        assertEquals(42, expr.evaluate(EMPTY_OBJECT).intValue());
    }

    @Test
    void evalInsideALambda_leavesTheEnclosingExpressionIntact() throws Exception {
        assertEquals("[2,2]",
                eval("( $f := function($x){ $eval(\"1+1\") }; $map([1,2], $f) )").toString());
        assertEquals(3, eval("( $n := 3; $map([1], function($x){ $eval(\"1+1\") }); $n )").intValue());
    }

    @Test
    void nestedEvals() throws Exception {
        assertEquals(2, eval("$eval(\"$eval(\\\"1+1\\\")\")").intValue());
    }

    @Test
    void evalSeesItsContextArgument() throws Exception {
        JsonNode result = FACTORY.compile("$eval(\"a.b\", $)")
                .evaluate(MAPPER.readTree("{\"a\": {\"b\": 7}}"));
        assertEquals(7, result.intValue());
    }

    @Test
    void repeatedEvalOfTheSameExpression_isConsistent() throws Exception {
        // Compilations are cached by expression text; the cache must not change results.
        assertEquals("[2,2,2,2,2]",
                eval("$map([1..5], function($i){ $eval(\"1+1\") })").toString());
        assertEquals("[2,3,4]",
                eval("$map([1..3], function($i){ $eval(\"1+\" & $i) })").toString());
    }
}

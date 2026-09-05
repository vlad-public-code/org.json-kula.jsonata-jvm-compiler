package org.json_kula.jsonata_jvm.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.jsonata_jvm.JsonataExpressionFactory;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A path expression whose first step is an array constructor.
 *
 * <p>JSONata treats that position specially: the constructor is evaluated as a <em>value</em>
 * rather than iterated over, and if it comes out empty the path ends there — the remaining
 * steps are never evaluated, and the empty array is returned <em>uncollapsed</em>. So
 * {@code [].x} is {@code []}, while {@code empty.x} — the same value through the same step
 * — is undefined, because that one is an empty sequence and sequences collapse.
 *
 * <p>The trigger is syntactic and dynamic at once: the constructor must be written as the
 * first step ({@code ([]).x} and {@code nums.[].x} are unaffected) <em>and</em> must
 * evaluate to empty ({@code ["a"].x} is unaffected). Its emptiness may come from data, as
 * {@code [nums[false]].x} shows.
 *
 * <p>All three sibling ports collapse this case to undefined; only the reference keeps the
 * array. Expectations here come from the reference (jsonata 2.2.2). The official test suite
 * exercises the shape 18 times but never with an empty constructor, so it does not guard
 * this in either direction — these tests are the only thing that does. Written up in
 * {@code ../../jsonata-conformance.md}.
 */
class ArrayConstructorPathHeadTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonataExpressionFactory FACTORY = new JsonataExpressionFactory();

    private static JsonNode data() throws Exception {
        return MAPPER.readTree("""
                {"nums": [1, 2, 3], "empty": [], "objs": [{"x": 1}]}""");
    }

    private static JsonNode eval(String expression) throws Exception {
        return FACTORY.compile(expression).evaluate(data());
    }

    private static String json(String expression) throws Exception {
        return MAPPER.writeValueAsString(eval(expression));
    }

    // =====================================================================
    @Nested
    class EmptyConstructorEndsThePath {

        @ParameterizedTest
        @CsvSource(delimiter = '|', value = {
            "[].x                | []",
            "[].x.y              | []",
            "[].$count()         | []",
            "[].*                | []",
            "[].**               | []",
            "[].{}               | []",
            "[].[1]              | []",
            "[nope].x            | []",
            "[nope, nope].x      | []",
            "[empty].x           | []",
            "[]^(x).y            | []",
        })
        void yieldsTheEmptyArray(String expression, String expected) throws Exception {
            assertEquals(expected, json(expression));
        }

        /** The emptiness may be decided by the data, not just written literally. */
        @Test
        void emptinessMayComeFromData() throws Exception {
            assertEquals("[]", json("[nums[false]].x"));
        }

        /**
         * The remaining steps are genuinely skipped, not evaluated to nothing — a step
         * body that would throw does not run.
         */
        @Test
        void remainingStepsAreNotEvaluated() throws Exception {
            assertEquals("[]", json("[].($error(\"boom\"))"));
        }

        /** The step body does run when the constructor is non-empty, and then it throws. */
        @Test
        void remainingStepsRunWhenTheConstructorIsNonEmpty() {
            assertThrows(Exception.class, () -> eval("[\"a\"].($error(\"boom\"))"));
        }
    }

    // =====================================================================
    @Nested
    class ObservableThroughConsumers {

        /** The result is a real array, so it is not merely a different spelling of absent. */
        @ParameterizedTest
        @CsvSource(delimiter = '|', value = {
            "$exists([].x)       | true",
            "$type([].x)         | \"array\"",
            "$string([].x)       | \"[]\"",
            "$boolean([].x)      | false",
            "[].x = []           | true",
            "$append([].x, 1)    | [1]",
        })
        void isVisibleToConsumers(String expression, String expected) throws Exception {
            assertEquals(expected, json(expression));
        }

        /** Some consumers cannot tell the difference, and must not be read as coverage. */
        @ParameterizedTest
        @CsvSource(delimiter = '|', value = {
            "$count([].x)              | 0",
            "[].x ? \"yes\" : \"no\"   | \"no\"",
        })
        void someConsumersHideIt(String expression, String expected) throws Exception {
            assertEquals(expected, json(expression));
        }
    }

    // =====================================================================
    @Nested
    class RegressionGuards {

        /**
         * These must stay undefined. The general collapse of an empty sequence is
         * load-bearing everywhere, and the short-circuit above must not leak into it.
         */
        @ParameterizedTest
        @ValueSource(strings = {
            "empty.x",
            "nums[false].x",
            "objs[x>99].y",
            "$filter(nums, function($v){false}).x",
            "$map([], function($v){$v}).x",
            "$each({}, function($v){$v}).x",
        })
        void otherRoutesToAnEmptySequenceStayUndefined(String expression) throws Exception {
            assertTrue(eval(expression).isMissingNode(), expression);
        }

        /**
         * Parenthesising defeats it: the trigger is the syntactic first step, and the
         * reference flags only a literal {@code [}. This is the case the parser has to
         * record, because the optimizer unwraps the {@code Parenthesized} that
         * distinguishes them.
         */
        @Test
        void parenthesisedConstructorIsUnaffected() throws Exception {
            assertTrue(eval("([]).x").isMissingNode());
        }

        /** A constructor that is not the first step is unaffected. */
        @ParameterizedTest
        @ValueSource(strings = { "nums.[].x", "objs.[].x", "$.[].x" })
        void constructorAfterTheFirstStepIsUnaffected(String expression) throws Exception {
            assertTrue(eval(expression).isMissingNode(), expression);
        }

        /** A subscript makes it a different node, and is unaffected. */
        @Test
        void subscriptedConstructorIsUnaffected() throws Exception {
            assertTrue(eval("[][0].x").isMissingNode());
            assertTrue(eval("[][0]").isMissingNode());
        }

        /** A non-empty constructor keeps ordinary path semantics. */
        @ParameterizedTest
        @ValueSource(strings = { "[\"a\"].x", "[1].x", "[[]].x" })
        void nonEmptyConstructorIsUnaffected(String expression) throws Exception {
            assertTrue(eval(expression).isMissingNode(), expression);
        }

        @Test
        void nonEmptyConstructorStillNavigates() throws Exception {
            assertEquals("1", json("[{\"x\":1}].x"));
            assertEquals("[1,2,3]", json("[{\"a\":[1,2]}, {\"a\":[3]}].a"));
            assertEquals("[\"1\",\"2\",\"3\"]", json("[1,2,3].$string()"));
        }

        /** A variable holding an empty array is a value, not a constructor. */
        @Test
        void variableHoldingAnEmptyArrayIsUnaffected() throws Exception {
            assertTrue(eval("($e := []; $e.x)").isMissingNode());
        }

        /** Object constructors are not flagged — only {@code [} is. */
        @Test
        void objectConstructorIsUnaffected() throws Exception {
            assertTrue(eval("{}.x").isMissingNode());
            assertTrue(eval("({}).x").isMissingNode());
        }

        /** The chain operator is not a path step and is unaffected. */
        @Test
        void chainOperatorIsUnaffected() throws Exception {
            assertEquals("0", json("[] ~> $count()"));
        }
    }
}

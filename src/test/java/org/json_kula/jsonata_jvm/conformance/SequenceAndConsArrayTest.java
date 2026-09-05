package org.json_kula.jsonata_jvm.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.jsonata_jvm.JsonataCompilationException;
import org.json_kula.jsonata_jvm.JsonataEvaluationException;
import org.json_kula.jsonata_jvm.JsonataExpressionFactory;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a value <em>is</em>, as opposed to what it looks like: sequences, constructor arrays and
 * the marks JSONata puts on them.
 *
 * <p>A JSONata path builds sequences, and a sequence flattens into its parent and collapses when
 * it holds one element. Two kinds of array opt out. A {@code [...]} written as a path step builds
 * a <em>value</em> that happens to be an array — it does neither, so {@code nums.[1,2]} is three
 * arrays and {@code a.[1]} is {@code [1]}. And {@code []} asks for a singleton to be kept, which
 * survives further steps and a sort. Both are properties of the value, not of the syntax: they
 * travel with the array, which is why {@code a.[1].$[]} is {@code [[1]]}.
 *
 * <p>Around that sit the rules for where a stage lands, when {@code []} has anything to keep, and
 * which steps consume a sequence. Each group below is one rule, with the row that distinguishes it
 * from the neighbouring rule kept alongside — a test that only pins the interesting case tends to
 * be satisfied by an implementation that has stopped doing the ordinary one.
 *
 * <p>Expectations are the reference interpreter's (jsonata 2.2.2), taken from §10 and §11 of
 * {@code ../../jsonata-conformance.md}, which characterises these families across all three ports.
 * The official suite reaches almost none of them.
 */
class SequenceAndConsArrayTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonataExpressionFactory FACTORY = new JsonataExpressionFactory();

    private static JsonNode data() throws Exception {
        return MAPPER.readTree("""
                {"nums": [1,2,3], "objs": [{"x":1},{"x":2}], "one": [{"x":1}],
                 "a": {"b": 1, "c": [7,8]},
                 "o": [{"p":[{"v":2},{"v":1}],"q":5}, {"p":[{"v":4},{"v":3}],"q":6}],
                 "w1": {"e": [], "f": {"g": 9}}, "w2": {"f": {"g": 9}},
                 "w3": {"a": [], "b": []}}""");
    }

    private static String json(String expression) throws Exception {
        return MAPPER.writeValueAsString(FACTORY.compile(expression).evaluate(data()));
    }

    private static void check(String expression, String expected) throws Exception {
        assertEquals(expected, json(expression), expression);
    }

    /**
     * The error code, whether the compiler or the evaluator is the one to raise it. Which phase
     * reports a static error is a property of compiling ahead of time, not of the language: the
     * reference has no compile phase to raise it in.
     */
    private static void checkError(String expression, String code) {
        Throwable thrown = assertThrows(Throwable.class, () -> json(expression), expression);
        String actual = switch (thrown) {
            case JsonataCompilationException e -> e.getErrorCode();
            case JsonataEvaluationException e  -> e.getErrorCode();
            default -> fail(expression + " threw " + thrown);
        };
        assertEquals(code, actual, expression);
    }

    // =====================================================================
    @Nested
    class AConstructorStepIsAValue {

        @ParameterizedTest
        @CsvSource(delimiter = '|', value = {
            // It does not flatten into the sequence around it...
            "nums.[1,2]        | [[1,2],[1,2],[1,2]]",
            "objs.[x]          | [[1],[2]]",
            // ...and it does not collapse when it is alone in one.
            "a.[1]             | [1]",
            "a.[]              | []",
            "a.[[]]            | [[]]",
            "one.[1]           | [1]",
            // A constructor drops an element that evaluates to nothing: it gets shorter, not absent.
            "a.[nope]          | []",
            "nums.[nope]       | [[],[],[]]",
            // Parentheses make it an ordinary step again, and then it does both.
            "nums.([1,2])      | [1,2,1,2,1,2]",
            "nums.([$])        | [1,2,3]",
            "nums.([[1,2]])    | [[1,2],[1,2],[1,2]]",
            "[1,2].([3,4])     | [3,4,3,4]",
        })
        void distinguishesAValueFromASequence(String expression, String expected) throws Exception {
            check(expression, expected);
        }

        /** `nums.([])` is undefined where `nums.[]` is three empty arrays — the same difference. */
        @ParameterizedTest
        @CsvSource(delimiter = '|', value = {
            "$type(a.[])       | \"array\"",
            "nums.([])         | null",
            "$count(nums.([1,2,3].$)) | 9",
            "$sum(nums.([1,2,3].$))   | 18",
        })
        void isVisibleToConsumers(String expression, String expected) throws Exception {
            check(expression, expected);
        }
    }

    // =====================================================================
    @Nested
    class TheMarkTravelsWithTheValue {

        @ParameterizedTest
        @CsvSource(delimiter = '|', value = {
            // `[]` promotes a constructor's array rather than passing it through: the array is one
            // value, not a sequence of one, so keeping its singleton means wrapping it.
            "a.[][]            | [[]]",
            "a.[1][]           | [[1]]",
            "a.[1,2][]         | [[1,2]]",
            "a.[nope][]        | [[]]",
            "a.[[]][]          | [[[]]]",
            // A sequence was already an array, so there it only stops the collapse.
            "a.b[]             | [1]",
            "nums[]            | [1,2,3]",
            // A `$` step is transparent to the mark, and so is a sort.
            "a.[1].$           | [1]",
            "a.[1].$[]         | [[1]]",
            "a.[1][].$         | [[1]]",
            "a.[1]^(x)         | [1]",
            "a.[]^(x)          | []",
            "a.b[]^($)         | [1]",
            "one.x[]^($)       | [1]",
            // Parenthesised, so neither mark is set — this is the control for the row above.
            "a.([1])^($)       | 1",
        })
        void survivesLaterStepsAndASort(String expression, String expected) throws Exception {
            check(expression, expected);
        }

        /** `[]` keeps a *singleton*; the length-0 collapse is not guarded by it. */
        @ParameterizedTest
        @CsvSource(delimiter = '|', value = {
            "nums[false][]^($) | null",
            "a.[]^(x)          | []",
        })
        void doesNotKeepAnEmptySequence(String expression, String expected) throws Exception {
            check(expression, expected);
        }
    }

    // =====================================================================
    @Nested
    class KeepArrayFiresOnlyWhereASequenceIs {

        @ParameterizedTest
        @CsvSource(delimiter = '|', value = {
            // Not a sequence, so `[]` does nothing at all.
            "1[]               | 1",
            "\"s\"[]           | \"s\"",
            "{}[]              | {}",
            "(a.b)[]           | 1",
            "($v:=1;$v)[]      | 1",
            "$sum(nums)[]      | 6",
            "$string(1)[]      | \"1\"",
            "$append(1, nope)[]| 1",
            "nums ~> $sum()[]  | 6",
            "a{\"k\":b}[]      | {\"k\":1}",
            // ...but the mark stays on the node, and fires if something later does make one.
            "1[][0]            | [1]",
            "{}[].$            | [{}]",
            "$sum(nums)[0][]   | [6]",
            // A sequence, so it applies at once.
            "a.b[]             | [1]",
            "nums[0][]         | [1]",
            "nums^($)[]        | [1,2,3]",
            "a.*[]             | [1,7,8]",
            "$keys(a)[]        | [\"b\",\"c\"]",
            "$map(one, function($v){$v.x})[] | [1]",
            // $lookup is the one built-in whose answer depends on its argument, not its name.
            "$lookup(a, \"b\")[]   | 1",
            "$lookup(one, \"x\")[] | [1]",
            "$lookup(objs, \"x\")[]| [1,2]",
        })
        void isDecidedByWhetherTheResultIsASequence(String expression, String expected) throws Exception {
            check(expression, expected);
        }

        /** `@$v` never wraps its source into a path; `#$i` does. */
        @ParameterizedTest
        @CsvSource(delimiter = '|', value = {
            "$type($@$e[])     | \"object\"",
            "$type($#$i[])     | \"array\"",
        })
        void followsWhereTheBindingLeftTheNode(String expression, String expected) throws Exception {
            check(expression, expected);
        }
    }

    // =====================================================================
    @Nested
    class AStageBelongsToItsStep {

        @ParameterizedTest
        @CsvSource(delimiter = '|', value = {
            // Every `[...]` over a path folds onto its last step and runs per element, whatever
            // the predicate. Filtering the collected sequence instead compares arrays: T2010.
            "objs.[1,2][$>1]   | [2,2]",
            "a.[1,2][$>1]      | 2",
            "objs.[1,2][0]     | [1,1]",
            "objs.$[0]         | [{\"x\":1},{\"x\":2}]",
            "a.[1,2][-1]       | 2",
            "a.[nope][0]       | null",
            // A `[]` between the step and its stage does not separate them.
            "o.p[][0]          | [{\"v\":2},{\"v\":4}]",
            "o.p[][1]          | [{\"v\":1},{\"v\":3}]",
            // Two stages on one step run in the order written, on the same per-element value.
            "o.p[v>1][0].v     | [2,4]",
            // A literal-index stage returns the selected array itself, not a sequence holding it.
            "a.[[1,2]][0]      | [1,2]",
            "objs.[[1]][0]     | [1,1]",
            // Ordinary navigation steps are unaffected — the two models agree there.
            "o.p[0]            | [{\"v\":2},{\"v\":4}]",
            "objs[0]           | {\"x\":1}",
            "o.p[v>1]          | [{\"v\":2},{\"v\":4},{\"v\":3}]",
        })
        void runsPerElement(String expression, String expected) throws Exception {
            check(expression, expected);
        }

        /** Except in tuple mode: `%` expands the whole stream before its stages run. */
        @ParameterizedTest
        @CsvSource(delimiter = '|', value = {
            "o.p.v.%[0]        | {\"v\":2}",
            "o.p.v.%[1]        | {\"v\":1}",
            "a.%[0]            | null",
            "a.b.%[0]          | {\"b\":1,\"c\":[7,8]}",
        })
        void isGlobalOnAParentStep(String expression, String expected) throws Exception {
            if ("null".equals(expected)) {           // `a.%[0]` is the whole document
                assertEquals(json("$"), json(expression));
                return;
            }
            check(expression, expected);
        }
    }

    // =====================================================================
    @Nested
    class AGroupByHangsOffTheWholePath {

        @ParameterizedTest
        @CsvSource(delimiter = '|', value = {
            // A `[...]`, `^(…)` or `.step` after a group-by lands on the path's last step and runs
            // before the grouping: this groups objs[0], it does not index the grouped object.
            "objs{\"k\":x}[0]      | {\"k\":1}",
            "nums{\"k\":$}[0]      | {\"k\":1}",
            "a{\"k\":b}[0]         | {\"k\":1}",
            "objs{\"k\":x}[0].k    | {}",
            "objs{\"k\":x}         | {\"k\":[1,2]}",
            // An empty or absent source still runs the pairs once, with an absent context.
            "nope{\"k\":\"v\"}     | {\"k\":\"v\"}",
            "nope{\"k\":$}         | {}",
        })
        void appliesLast(String expression, String expected) throws Exception {
            check(expression, expected);
        }

        /** S0209 is not "a predicate after a group-by" — it is "the source was not a path". */
        @ParameterizedTest
        @CsvSource(delimiter = '|', value = {
            "(1){\"k\":$}[0]   | S0209",
            "objs{\"k\":x}^($) | T2008",
        })
        void rejectsOnlyWhatTheReferenceRejects(String expression, String code) {
            checkError(expression, code);
        }
    }

    // =====================================================================
    @Nested
    class AWildcardStepIsStillAStep {

        @ParameterizedTest
        @CsvSource(delimiter = '|', value = {
            // One array-valued key costs the result its sequence flag, so it does not collapse.
            "w1.*              | [{\"g\":9}]",
            "w2.*              | {\"g\":9}",
            "w3.*              | []",
            "$type(w1.*)       | \"array\"",
            // A `*` after another step sees one element at a time, and a scalar yields nothing.
            "nums.*            | null",
            "a.c.*             | null",
            "objs.*            | [1,2]",
            // `**` collects every non-array value it reaches, leaves included.
            "w1.**             | [{\"e\":[],\"f\":{\"g\":9}},{\"g\":9},9]",
        })
        void distinguishesTheHeadFromALaterStep(String expression, String expected) throws Exception {
            check(expression, expected);
        }
    }

    // =====================================================================
    @Nested
    class AConsarrayHeadCarryingAStage {

        /**
         * The short-circuit hands the stage's <em>value</em> to the remaining steps, and the next
         * step then walks it by index — which a scalar cannot be.
         */
        @ParameterizedTest
        @CsvSource(delimiter = '|', value = {
            "[1,2][0].$        | null",
            "[1,2][-1].$       | null",
            "[{\"x\":1}][0].x  | null",
            "[1,2][$>1].$      | null",
            // A string can: it yields its characters, and an empty one ends the path.
            "[\"ab\"][0].$     | [\"a\",\"b\"]",
            "[\"\"][0].x       | \"\"",
            // An array is walked as usual.
            "[[1,2]][0].$      | [1,2]",
            "[[1],[2]][0].$    | 1",
            // Parenthesising defeats it, exactly as it defeats the empty-head short-circuit.
            "([1,2][0]).$      | 1",
            // Per-step keepArray keeps the value a sequence; keepArray on the path does not.
            "[1,2][0][].$      | [1]",
            "[1,2][0].$[]      | null",
        })
        void handsOnAValueRatherThanASequence(String expression, String expected) throws Exception {
            check(expression, expected);
        }
    }

    // =====================================================================
    @Nested
    class StepsThatMustStillBeRejected {

        /** A literal cannot be a path step in any position, not just after a dot. */
        @ParameterizedTest
        @CsvSource(delimiter = '|', value = {
            "1[].$             | S0213",
            "true[].$          | S0213",
            "null[].$          | S0213",
            "a.true            | S0213",
            "true.x            | S0213",
            "1^($).$           | S0213",
            // A `%` with nothing above it, even when a stage or an unreachable head hides it.
            "$.%               | S0217",
            "$.%[0]            | S0217",
            "$$.%[0]           | S0217",
            "[].%              | S0217",
        })
        void areRejected(String expression, String code) {
            checkError(expression, code);
        }

        /** A parenthesised literal is a block, and constant folding legitimately makes one. */
        @ParameterizedTest
        @CsvSource(delimiter = '|', value = {
            "a.(1)             | 1",
            "a.(1+1)           | 2",
            "nums.(2)          | [2,2,2]",
            "\"nums\".$        | [1,2,3]",
        })
        void areNotOverReached(String expression, String expected) throws Exception {
            check(expression, expected);
        }
    }

    // =====================================================================
    @Nested
    class ALoneElementIsNeverSorted {

        /** No comparison happens, so no key is evaluated and none can be reported bad. */
        @ParameterizedTest
        @CsvSource(delimiter = '|', value = {
            "[[1]]^($)         | [1]",
            "[{\"k\":1}]^($)   | {\"k\":1}",
            "[{\"k\":true}]^(k)| {\"k\":true}",
            "one^($)           | {\"x\":1}",
            "$sort([[1]])      | [[1]]",
        })
        void succeeds(String expression, String expected) throws Exception {
            check(expression, expected);
        }

        /**
         * With something to compare it throws again — and <em>which</em> error depends on which
         * pair the reference's merge sort reaches first, so both orders are pinned.
         */
        @ParameterizedTest
        @CsvSource(delimiter = '|', value = {
            "[1,\"z\",{\"q\":1}]^($)       | T2008",
            "[{\"q\":1},1,\"z\"]^($)       | T2007",
            "[1,\"z\",2,{\"q\":1}]^($)     | T2007",
            "[1,2,\"z\",{\"q\":1},3]^($)   | T2008",
            "[{\"k\":true},{\"k\":false}]^(k) | T2008",
            "$sort([[1],[2]])              | D3070",
        })
        void reportsTheReferencesError(String expression, String code) {
            checkError(expression, code);
        }
    }
}

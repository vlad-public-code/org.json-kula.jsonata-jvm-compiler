package org.json_kula.jsonata_jvm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests covering examples from https://docs.jsonata.org/programming.
 *
 * Six sections:
 *   1. Conditional logic — ternary (? :), elvis (?:) and coalescing (??) operators
 *   2. Variable binding
 *   3. Invoking functions — built-in examples from the page
 *   4. Defining and invoking lambda functions
 *   5. Recursive functions
 *   6. Higher-order functions and function composition via ~>
 *
 * The Fred Smith document is used throughout (same as SimpleQueriesTest).
 */
class ProgrammingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonataExpressionFactory FACTORY = new JsonataExpressionFactory();

    private static final String FRED = """
            {
              "FirstName": "Fred",
              "Surname": "Smith",
              "Age": 28,
              "Address": {
                "Street": "Hursley Park",
                "City": "Winchester",
                "Postcode": "SO21 2JN"
              },
              "Phone": [
                { "type": "home",   "number": "0203 544 1234" },
                { "type": "office", "number": "01962 001234"  },
                { "type": "office", "number": "01962 001235"  },
                { "type": "mobile", "number": "077 7700 1234" }
              ],
              "Email": [
                { "type": "work",
                  "address": ["fred.smith@my-work.com", "fsmith@my-work.com"] },
                { "type": "home",
                  "address": ["freddy@my-social.com", "frederic.smith@very-serious.com"] }
              ],
              "Other": {
                "Over 18 ?": true,
                "Misc": null,
                "Alternative.Address": {
                  "Street": "Brick Lane",
                  "City": "London",
                  "Postcode": "E1 6RF"
                }
              }
            }
            """;

    private JsonNode eval(String expr, String json) throws Exception {
        return FACTORY.compile(expr).evaluate(json);
    }

    private void assertJsonEqual(String expected, JsonNode actual) throws Exception {
        assertEquals(MAPPER.readTree(expected).toString(), actual.toString());
    }

    // =========================================================================
    // Section 1 — Conditional logic (ternary ? :)
    // =========================================================================

    @Test
    void conditional_ternary_adultOrMinor() throws Exception {
        // Age > 18 ? "Adult" : "Minor"  →  "Adult"  (Age = 28)
        assertJsonEqual("\"Adult\"", eval("Age > 18 ? \"Adult\" : \"Minor\"", FRED));
    }

    @Test
    void conditional_ternary_fieldExists_trueBranch() throws Exception {
        // Phone[type='home'].number ? "Has home phone" : "No home phone"
        assertJsonEqual("\"Has home phone\"",
                eval("Phone[type='home'].number ? \"Has home phone\" : \"No home phone\"", FRED));
    }

    @Test
    void conditional_ternary_fieldMissing_falseBranch() throws Exception {
        // Phone[type='fax'].number is missing → false branch
        assertJsonEqual("\"No fax\"",
                eval("Phone[type='fax'].number ? \"Has fax\" : \"No fax\"", FRED));
    }

    @Test
    void conditional_ternary_mapOverArray() throws Exception {
        // Phone.{type: $length(number) > 12 ? "long" : "short"}
        // Lengths: "0203 544 1234"=13, "01962 001234"=12, "01962 001235"=12, "077 7700 1234"=13
        assertJsonEqual(
                "[{\"home\":\"long\"},{\"office\":\"short\"},{\"office\":\"short\"},{\"mobile\":\"long\"}]",
                eval("Phone.{type: $length(number) > 12 ? \"long\" : \"short\"}", FRED));
    }

    // =========================================================================
    // Section 1b — Elvis / Default operator (?:)
    // =========================================================================

    @Test
    void conditional_elvis_missingFieldFallback() throws Exception {
        // Address.Country ?: "Unknown"  →  "Unknown"  (Country not in Address)
        assertJsonEqual("\"Unknown\"", eval("Address.Country ?: \"Unknown\"", FRED));
    }

    @Test
    void conditional_elvis_presentFieldPassThrough() throws Exception {
        // Address.City ?: "Unknown"  →  "Winchester"  (City is present)
        assertJsonEqual("\"Winchester\"", eval("Address.City ?: \"Unknown\"", FRED));
    }

    // =========================================================================
    // Section 1c — Coalescing operator (??)
    // =========================================================================

    @Test
    void conditional_coalescing_missingFallback() throws Exception {
        // Other.Misc ?? "default"  →  "default"  (Misc is null, not missing — still returns null,
        // but a missing path should give the fallback)
        assertJsonEqual("\"default\"", eval("Other.Nothing ?? \"default\"", FRED));
    }

    // =========================================================================
    // Section 2 — Variable binding
    // =========================================================================

    @Test
    void variable_bindScalar_useInExpression() throws Exception {
        // ($city := Address.City; $city)  →  "Winchester"
        assertJsonEqual("\"Winchester\"", eval("($city := Address.City; $city)", FRED));
    }

    @Test
    void variable_bindAndCombine() throws Exception {
        // ($greeting := "Hello, " & FirstName; $greeting)  →  "Hello, Fred"
        assertJsonEqual("\"Hello, Fred\"",
                eval("($greeting := \"Hello, \" & FirstName; $greeting)", FRED));
    }

    @Test
    void variable_multipleBindings_useInArithmetic() throws Exception {
        // ($a := Age; $b := 2; $a * $b)  →  56
        assertEquals(56, eval("($a := Age; $b := 2; $a * $b)", FRED).intValue());
    }

    // =========================================================================
    // Section 3 — Invoking built-in functions
    // =========================================================================

    @Test
    void function_uppercase_string() throws Exception {
        // $uppercase("Hello")  →  "HELLO"
        assertJsonEqual("\"HELLO\"", eval("$uppercase(\"Hello\")", FRED));
    }

    @Test
    void function_substring_positional() throws Exception {
        // $substring("hello world", 0, 5)  →  "hello"
        assertJsonEqual("\"hello\"", eval("$substring(\"hello world\", 0, 5)", FRED));
    }

    @Test
    void function_sum_literalArray() throws Exception {
        // $sum([1,2,3])  →  6
        assertEquals(6, eval("$sum([1,2,3])", FRED).intValue());
    }

    // =========================================================================
    // Section 4 — Defining and invoking lambda functions
    // =========================================================================

    @Test
    void lambda_assignAndInvoke() throws Exception {
        // ($volume := function($l, $w, $h){ $l * $w * $h }; $volume(10, 10, 5))  →  500
        assertEquals(500, eval("($volume := function($l, $w, $h){ $l * $w * $h }; $volume(10, 10, 5))", FRED).intValue());
    }

    @Test
    void lambda_usesClosureOverContext() throws Exception {
        // Lambda capturing a variable from the enclosing block
        // ($prefix := "Ph: "; $fmt := function($n){ $prefix & $n }; $fmt(Phone[0].number))
        // → "Ph: 0203 544 1234"
        assertJsonEqual("\"Ph: 0203 544 1234\"",
                eval("($prefix := \"Ph: \"; $fmt := function($n){ $prefix & $n }; $fmt(Phone[0].number))", FRED));
    }

    // =========================================================================
    // Section 5 — Recursive functions
    // =========================================================================

    @Test
    void recursive_factorial() throws Exception {
        // ($factorial := function($x){ $x <= 1 ? 1 : $x * $factorial($x-1) }; $factorial(4))
        // →  4! = 24
        assertEquals(24,
                eval("($factorial := function($x){ $x <= 1 ? 1 : $x * $factorial($x-1) }; $factorial(4))", FRED).intValue());
    }

    @Test
    void recursive_factorial_largish() throws Exception {
        // Tail-recursive accumulator variant handles larger inputs without stack overflow
        // ($factorial := function($x){( $iter := function($x, $acc) {
        //     $x <= 1 ? $acc : $iter($x - 1, $x * $acc) }; $iter($x, 1) )};
        //  $factorial(10))  →  3628800
        assertEquals(3628800,
                eval("""
                        ($factorial := function($x){(
                          $iter := function($x, $acc) {
                            $x <= 1 ? $acc : $iter($x - 1, $x * $acc)
                          };
                          $iter($x, 1)
                        )};
                        $factorial(10))""", FRED).intValue());
    }

    // =========================================================================
    // Section 6 — Higher-order functions and function chaining
    // =========================================================================

    @Test
    void higherOrder_twiceApplied() throws Exception {
        // ($twice := function($f) { function($x){ $f($f($x)) } };
        //  $add3 := function($y){ $y + 3 };
        //  $add6 := $twice($add3);
        //  $add6(7))  →  13
        assertEquals(13, eval("""
                ($twice := function($f) { function($x){ $f($f($x)) } };
                 $add3 := function($y){ $y + 3 };
                 $add6 := $twice($add3);
                 $add6(7))""", FRED).intValue());
    }

    @Test
    void functionChaining_normalizeWhitespace() throws Exception {
        // ($normalize := $uppercase ~> $trim; $normalize("   Some   Words   "))
        // uppercase → "   SOME   WORDS   ", trim (+ collapse) → "SOME WORDS"
        assertJsonEqual("\"SOME WORDS\"",
                eval("($normalize := $uppercase ~> $trim; $normalize(\"   Some   Words   \"))", FRED));
    }

    @Test
    void functionComposition_first5Capitalized_onFredCity() throws Exception {
        // From page: ($first5Capitalized := $substring(?, 0, 5) ~> $uppercase(?);
        //             $first5Capitalized(Address.City))
        assertJsonEqual("\"WINCH\"",
                eval("($first5Capitalized := $substring(?, 0, 5) ~> $uppercase(?); $first5Capitalized(Address.City))", FRED));
    }

    // =========================================================================
    // Section 6b — Partial function application
    // =========================================================================

    @Test
    void partial_substringFirst5() throws Exception {
        // ($first5 := $substring(?, 0, 5); $first5("Hello, World"))  →  "Hello"
        assertJsonEqual("\"Hello\"",
                eval("($first5 := $substring(?, 0, 5); $first5(\"Hello, World\"))", FRED));
    }

    @Test
    void partial_chainedPartialApplication() throws Exception {
        // ($firstN := $substring(?, 0, ?); $first5 := $firstN(?, 5); $first5("Hello, World"))
        // →  "Hello"
        assertJsonEqual("\"Hello\"",
                eval("($firstN := $substring(?, 0, ?); $first5 := $firstN(?, 5); $first5(\"Hello, World\"))", FRED));
    }

    @Test
    void partial_compositionWithPartial_first5CapitalizedOfCity() throws Exception {
        // ($first5Capitalized := $substring(?, 0, 5) ~> $uppercase(?);
        //  $first5Capitalized(Address.City))  →  "WINCH"
        assertJsonEqual("\"WINCH\"",
                eval("($first5Capitalized := $substring(?, 0, 5) ~> $uppercase(?); $first5Capitalized(Address.City))", FRED));
    }

    @Test
    void comments() throws Exception {
        assertJsonEqual("""
                [
                  ".....................O.....................",
                  "..........................O................",
                  "...............................O...........",
                  "...................................O.......",
                  "......................................O....",
                  "........................................O..",
                  ".........................................O.",
                  "........................................O..",
                  "......................................O....",
                  "...................................O.......",
                  "...............................O...........",
                  "..........................O................",
                  ".....................O.....................",
                  "................O..........................",
                  "...........O...............................",
                  ".......O...................................",
                  "....O......................................",
                  "..O........................................",
                  ".O.........................................",
                  "..O........................................",
                  "....O......................................",
                  ".......O...................................",
                  "...........O...............................",
                  "................O..........................",
                  ".....................O....................."
                ]
                """,
                eval("""
                    /* Long-winded expressions might need some explanation */
                    (
                      $pi := 3.1415926535897932384626;
                      /* JSONata is not known for its graphics support! */
                      $plot := function($x) {(
                        $floor := $string ~> $substringBefore(?, '.') ~> $number;
                        $index := $floor(($x + 1) * 20 + 0.5);
                        $join([0..$index].('.')) & 'O' & $join([$index..40].('.'))
                      )};
                    
                      /* Factorial is the product of the integers 1..n */
                      $product := function($a, $b) { $a * $b };
                      $factorial := function($n) { $n = 0 ? 1 : $reduce([1..$n], $product) };
                    
                      $sin := function($x){ /* define sine in terms of cosine */
                        $cos($x - $pi/2)
                      };
                      $cos := function($x){ /* Derive cosine by expanding Taylor's series */
                        $x > $pi ? $cos($x - 2 * $pi) : $x < -$pi ? $cos($x + 2 * $pi) :
                          $sum([0..12].($power(-1, $) * $power($x, 2*$) / $factorial(2*$)))
                      };
                    
                      [0..24].$sin($*$pi/12).$plot($)
                    )
                    """,
            "{}"));
    }
}

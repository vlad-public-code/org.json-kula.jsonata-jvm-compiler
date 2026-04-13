package org.json_kula.jsonata_jvm.language_features;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.jsonata_jvm.JsonataCompilationException;
import org.json_kula.jsonata_jvm.JsonataEvaluationException;
import org.json_kula.jsonata_jvm.JsonataExpressionFactory;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ErrorCodesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonataExpressionFactory FACTORY = new JsonataExpressionFactory();
    private static final ObjectNode EMPTY = MAPPER.createObjectNode();

    private String code(String expr) {
        try {
            FACTORY.compile(expr).evaluate(EMPTY);
            return "none";
        } catch (JsonataCompilationException | JsonataEvaluationException ex) {
            String msg = ex.getMessage();
            if (msg == null) return "null";
            return ex.getErrorCode() != null ? ex.getErrorCode() : "UNKNOWN(" + msg.substring(0, Math.min(40, msg.length())) + ")";
        }
    }

    @Test void t2001() { assertEquals("T2001", code("\"s\" - 1")); }
    @Test void t2002() { assertEquals("T2002", code("1 + null")); }
    @Test void d1002() { assertEquals("D1002", code("- \"s\"")); }
    @Test void t1006_foo()  { assertEquals("T1006", code("$foo()")); }
    @Test void t1006_unknown() { assertEquals("T1006", code("unknown(function)")); }
    @Test void s0201() { assertEquals("S0201", code("Account.Order[0].Product;")); }
    @Test void s0202() { assertEquals("S0202", code("[1,2)")); }
    @Test void s0202b() { assertEquals("S0202", code("[1:2]")); }
    @Test void s0204() { assertEquals("S0204", code("[1!2]")); }
    @Test void s0207() { assertEquals("S0207", code("1=")); }
    @Test void s0208() { assertEquals("S0208", code("function(x){$x}(3)")); }
    @Test void s0209() { assertEquals("S0209", code("[1,2,3]{\"num\": $}[true]")); }
    @Test void s0210() { assertEquals("S0210", code("[1,2,3]{\"num\": $}{\"num\": $}")); }
    @Test void s0211_at() { assertEquals("S0211", code("@ bar")); }
    @Test void s0212_x()  { assertEquals("S0212", code("x:=1")); }
    @Test void s0212_2()  { assertEquals("S0212", code("2:=1")); }
    @Test void t1006_2blah() { assertEquals("T1006", code("2(blah)")); }
    @Test void t1006_2empty() { assertEquals("T1006", code("2()")); }
    @Test void t1008() { assertEquals("T1008", code("3(?)")); }
    @Test void t0410() { assertEquals("T0410", code("( $A := function(){$min(2, 3)}; $A() )")); }
    @Test void t2002_body() { assertEquals("T2002", code("( $B := function(){''}; $A := function(){2 + $B()}; $A() )")); }
    @Test void s0101() { assertEquals("S0101", code("\"no closing quote")); }
    @Test void s0105() { assertEquals("S0105", code("`no closing backtick")); }
    @Test void s0202_replace() { assertEquals("S0202", code("$replace(\"foo\", \"o, \"rr\")")); }
    @Test void s0211_arrow() { assertEquals("S0211", code("55=>5")); }
    @Test void s0211_colon() { assertEquals("S0211", code("Ssum(:)")); }
}

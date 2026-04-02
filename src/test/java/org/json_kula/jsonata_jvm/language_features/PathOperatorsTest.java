package org.json_kula.jsonata_jvm.language_features;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.jsonata_jvm.JsonataExpressionFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the three JSONata path operators:
 * <ul>
 *   <li>{@code #$var} — positional variable binding</li>
 *   <li>{@code @$var} — context variable binding / cross-join</li>
 *   <li>{@code %}  — parent operator</li>
 * </ul>
 */
class PathOperatorsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonataExpressionFactory FACTORY = new JsonataExpressionFactory();

    // JSON used for positional and context binding tests
    private static final String LIBRARY = """
            {
              "library": {
                "books": [
                  {"title": "The C Programming Language", "authors": ["Kernighan", "Ritchie"], "isbn": "isbn1"},
                  {"title": "The AWK Programming Language", "authors": ["Aho", "Kernighan", "Weinberger"], "isbn": "isbn2"},
                  {"title": "Java Concurrency in Practice", "authors": ["Goetz"], "isbn": "isbn3"}
                ],
                "loans": [
                  {"customer": "Alice", "isbn": "isbn1"},
                  {"customer": "Bob",   "isbn": "isbn2"}
                ]
              }
            }
            """;

    // JSON used for parent operator tests
    private static final String ACCOUNT = """
            {
              "Account": {
                "Account Name": "Firefly",
                "Order": [
                  {
                    "OrderID": "order1",
                    "Product": [
                      {"Product Name": "Widget",    "ProductID": "p1", "Quantity": 2, "Price": 5.0},
                      {"Product Name": "Gadget",    "ProductID": "p2", "Quantity": 1, "Price": 25.0}
                    ]
                  },
                  {
                    "OrderID": "order2",
                    "Product": [
                      {"Product Name": "Doohickey", "ProductID": "p3", "Quantity": 5, "Price": 3.0}
                    ]
                  }
                ]
              }
            }
            """;

    private JsonNode eval(String expr, String json) throws Exception {
        return FACTORY.compile(expr).evaluate(MAPPER.readTree(json));
    }

    private JsonNode json(String raw) throws Exception {
        return MAPPER.readTree(raw);
    }

    private void assertJsonEqual(String expected, JsonNode actual) throws Exception {
        assertEquals(MAPPER.readTree(expected).toString(), actual.toString());
    }

    // =========================================================================
    // Positional variable binding  (#$var)
    // =========================================================================

    @Test
    void positional_allBooks_withIndex() throws Exception {
        JsonNode result = eval(
                "library.books#$i.{'title': title, 'index': $i}",
                LIBRARY);
        String expected = """
                [
                  {"title": "The C Programming Language",   "index": 0},
                  {"title": "The AWK Programming Language", "index": 1},
                  {"title": "Java Concurrency in Practice", "index": 2}
                ]""";
        assertJsonEqual(expected, result);
    }

    @Test
    void positional_filteredBooks_preservesOriginalIndex() throws Exception {
        // Books by Kernighan appear at original indices 0 and 1.
        JsonNode result = eval(
                "library.books#$i['Kernighan' in authors].{'title': title, 'index': $i}",
                LIBRARY);
        String expected = """
                [
                  {"title": "The C Programming Language",   "index": 0},
                  {"title": "The AWK Programming Language", "index": 1}
                ]""";
        assertJsonEqual(expected, result);
    }

    @Test
    void positional_singleBook_scalarResult() throws Exception {
        // Only the third book has isbn3; expect a single object (not an array).
        JsonNode result = eval(
                "library.books#$i[isbn = 'isbn3'].{'title': title, 'index': $i}",
                LIBRARY);
        String expected = """
                {"title": "Java Concurrency in Practice", "index": 2}""";
        assertJsonEqual(expected, result);
    }

    // =========================================================================
    // Context variable binding / cross-join  (@$var)
    // =========================================================================

    @Test
    void contextBinding_simpleBinding() throws Exception {
        // library.books@$b.title — equivalent to library.books.title but uses @$b
        JsonNode result = eval("library.books@$b.$b.title", LIBRARY);
        String expected = """
                ["The C Programming Language", "The AWK Programming Language", "Java Concurrency in Practice"]""";
        assertJsonEqual(expected, result);
    }

    @Test
    void contextBinding_crossJoin_loanMatchesBook() throws Exception {
        // Cross-product join: for each loan, find the matching book.
        JsonNode result = eval(
                "(library.loans)@$l.(library.books)@$b[$l.isbn=$b.isbn].{'title': $b.title, 'customer': $l.customer}",
                LIBRARY);
        String expected = """
                [
                  {"title": "The C Programming Language",   "customer": "Alice"},
                  {"title": "The AWK Programming Language", "customer": "Bob"}
                ]""";
        assertJsonEqual(expected, result);
    }

    // =========================================================================
    // Parent operator  (%)
    // =========================================================================

    @Test
    void parent_productWithOrderId() throws Exception {
        // Each product's object includes the OrderID of its containing order.
        JsonNode result = eval(
                "Account.Order.Product.{'Product': `Product Name`, 'Order': %.OrderID}",
                ACCOUNT);
        String expected = """
                [
                  {"Product": "Widget",    "Order": "order1"},
                  {"Product": "Gadget",    "Order": "order1"},
                  {"Product": "Doohickey", "Order": "order2"}
                ]""";
        assertJsonEqual(expected, result);
    }

    @Test
    void parent_productWithOrderAndAccount() throws Exception {
        // % gives Order, %.% gives Account
        JsonNode result = eval(
                "Account.Order.Product.{'Product': `Product Name`, 'Order': %.OrderID, 'Account': %.%.`Account Name`}",
                ACCOUNT);
        String expected = """
                [
                  {"Product": "Widget",    "Order": "order1", "Account": "Firefly"},
                  {"Product": "Gadget",    "Order": "order1", "Account": "Firefly"},
                  {"Product": "Doohickey", "Order": "order2", "Account": "Firefly"}
                ]""";
        assertJsonEqual(expected, result);
    }
}

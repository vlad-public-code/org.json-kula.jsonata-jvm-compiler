package org.json_kula.jsonata_jvm.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.jsonata_jvm.JsonNodeTestHelper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract tests for {@link ConstructedObjectMap}.
 *
 * <p>This map backs every object a constructor with literal keys produces, and Jackson treats it as
 * an ordinary {@link Map} — it iterates it to serialize, compares it to compare two objects, and
 * mutates it when a transform adds or removes a field. So it is tested against a {@link
 * LinkedHashMap} holding the same entries: anything the two disagree about is a bug here, not a
 * design choice.
 *
 * <p>The size cases matter because the map changes strategy at 8 fields, from a scan to a hash
 * index built on first read; the tests cross that boundary in both directions.
 */
class ConstructedObjectMapTest {

    private static final JsonNodeFactory NF = JsonNodeFactory.instance;

    private static JsonNode n(int i) {
        return NF.numberNode(i);
    }

    /** Builds both map kinds over the same entries, so every assertion can compare them. */
    private static ConstructedObjectMap build(int fields) {
        String[] keys = new String[fields];
        JsonNode[] values = new JsonNode[fields];
        for (int i = 0; i < fields; i++) {
            keys[i] = "k" + i;
            values[i] = n(i);
        }
        return new ConstructedObjectMap(keys, values);
    }

    private static LinkedHashMap<String, JsonNode> reference(int fields) {
        LinkedHashMap<String, JsonNode> m = new LinkedHashMap<>();
        for (int i = 0; i < fields; i++) m.put("k" + i, n(i));
        return m;
    }

    private static List<String> keysOf(Map<String, JsonNode> m) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, JsonNode> e : m.entrySet()) out.add(e.getKey());
        return out;
    }

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    @Test
    void agreesWithLinkedHashMapAcrossTheIndexThreshold() {
        // 8 is where the map switches from scanning to a hash index.
        for (int fields : new int[]{0, 1, 2, 7, 8, 9, 16, 40}) {
            ConstructedObjectMap map = build(fields);
            LinkedHashMap<String, JsonNode> ref = reference(fields);

            assertEquals(ref.size(), map.size(), "size at " + fields);
            assertEquals(ref.isEmpty(), map.isEmpty(), "isEmpty at " + fields);
            assertEquals(keysOf(ref), keysOf(map), "iteration order at " + fields);
            assertEquals(ref, map, "equals at " + fields);
            assertEquals(ref.hashCode(), map.hashCode(), "hashCode at " + fields);
            for (int i = 0; i < fields; i++) {
                assertEquals(n(i), map.get("k" + i), "get k" + i + " at " + fields);
                assertTrue(map.containsKey("k" + i));
            }
            assertNull(map.get("absent"), "absent key at " + fields);
            assertFalse(map.containsKey("absent"));
        }
    }

    @Test
    void aMissingValueOmitsItsKey() {
        ConstructedObjectMap map = new ConstructedObjectMap(
                new String[]{"a", "b", "c"},
                new JsonNode[]{n(1), JsonataRuntime.MISSING, n(3)});
        assertEquals(2, map.size());
        assertEquals(List.of("a", "c"), keysOf(map));
        assertNull(map.get("b"));
    }

    @Test
    void aNullValueSlotAlsoOmitsItsKey() {
        ConstructedObjectMap map = new ConstructedObjectMap(
                new String[]{"a", "b"}, new JsonNode[]{null, n(2)});
        assertEquals(1, map.size());
        assertEquals(n(2), map.get("b"));
    }

    @Test
    void lookupWorksForAnEqualButNotIdenticalKey() {
        // The scan tries == first; a key built at runtime must still be found.
        ConstructedObjectMap map = build(3);
        assertEquals(n(1), map.get(new String("k1")));
        ConstructedObjectMap wide = build(20);
        assertEquals(n(11), wide.get(new String("k11")));
    }

    // -------------------------------------------------------------------------
    // Mutation
    // -------------------------------------------------------------------------

    @Test
    void putReplacesAnExistingKeyInPlace() {
        ConstructedObjectMap map = build(3);
        assertEquals(n(1), map.put("k1", n(99)));
        assertEquals(n(99), map.get("k1"));
        assertEquals(3, map.size());
        assertEquals(List.of("k0", "k1", "k2"), keysOf(map));
    }

    @Test
    void putAppendsANewKeyAndGrowsPastTheInitialCapacity() {
        ConstructedObjectMap map = build(2);
        for (int i = 2; i < 30; i++) assertNull(map.put("k" + i, n(i)));
        assertEquals(30, map.size());
        assertEquals(reference(30), map);
        assertEquals(keysOf(reference(30)), keysOf(map));
    }

    @Test
    void aStructuralChangeIsVisibleThroughTheIndex() {
        // Read first so the index exists, then mutate: a stale index would answer the old question.
        ConstructedObjectMap map = build(12);
        assertEquals(n(5), map.get("k5"));           // builds the index
        map.remove("k5");
        assertNull(map.get("k5"), "removed key still found — stale index");
        assertEquals(n(6), map.get("k6"), "surviving key moved slot — stale index");
        map.put("fresh", n(77));
        assertEquals(n(77), map.get("fresh"));
        assertEquals(n(11), map.get("k11"));
    }

    @Test
    void removePreservesOrderAndReportsThePreviousValue() {
        ConstructedObjectMap map = build(4);
        assertEquals(n(1), map.remove("k1"));
        assertNull(map.remove("k1"));
        assertNull(map.remove("never"));
        assertEquals(List.of("k0", "k2", "k3"), keysOf(map));
        LinkedHashMap<String, JsonNode> ref = reference(4);
        ref.remove("k1");
        assertEquals(ref, map);
    }

    @Test
    void clearEmptiesTheMap() {
        ConstructedObjectMap map = build(12);
        assertEquals(n(3), map.get("k3"));
        map.clear();
        assertEquals(0, map.size());
        assertTrue(map.isEmpty());
        assertNull(map.get("k3"));
        assertEquals(List.of(), keysOf(map));
    }

    // -------------------------------------------------------------------------
    // Views
    // -------------------------------------------------------------------------

    @Test
    void entrySetValueWritesThrough() {
        ConstructedObjectMap map = build(3);
        for (Map.Entry<String, JsonNode> e : map.entrySet()) {
            if (e.getKey().equals("k1")) assertEquals(n(1), e.setValue(n(42)));
        }
        assertEquals(n(42), map.get("k1"));
    }

    @Test
    void entrySetIteratorRemoveWritesThrough() {
        ConstructedObjectMap map = build(4);
        Iterator<Map.Entry<String, JsonNode>> it = map.entrySet().iterator();
        it.next();
        it.remove();
        assertEquals(3, map.size());
        assertNull(map.get("k0"));
        assertEquals(List.of("k1", "k2", "k3"), keysOf(map));
    }

    @Test
    void entrySetContainsMatchesOnKeyAndValue() {
        ConstructedObjectMap map = build(3);
        assertTrue(map.entrySet().contains(Map.entry("k1", n(1))));
        assertFalse(map.entrySet().contains(Map.entry("k1", n(2))));
        assertFalse(map.entrySet().contains(Map.entry("nope", n(1))));
    }

    @Test
    void keySetAndValuesReflectTheMap() {
        ConstructedObjectMap map = build(3);
        assertEquals(reference(3).keySet(), map.keySet());
        assertEquals(new ArrayList<>(reference(3).values()), new ArrayList<>(map.values()));
    }

    @Test
    void forEachVisitsEveryEntryInOrder() {
        List<String> seen = new ArrayList<>();
        build(5).forEach((k, v) -> seen.add(k + "=" + v));
        assertEquals(List.of("k0=0", "k1=1", "k2=2", "k3=3", "k4=4"), seen);
    }

    @Test
    void iteratingPastTheEndThrows() {
        Iterator<Map.Entry<String, JsonNode>> it = build(1).entrySet().iterator();
        it.next();
        assertFalse(it.hasNext());
        assertThrows(java.util.NoSuchElementException.class, it::next);
    }

    // -------------------------------------------------------------------------
    // As an ObjectNode's backing store
    // -------------------------------------------------------------------------

    @Test
    void anObjectNodeBackedByItBehavesLikeAnOrdinaryOne() {
        JsonNode fast = JsonataRuntime.objectOfDistinct(
                new String[]{"b", "a"}, new JsonNode[]{n(2), n(1)});
        ObjectNode plain = new ObjectNode(NF, new LinkedHashMap<>());
        plain.set("b", n(2));
        plain.set("a", n(1));

        assertEquals(plain, fast, "ObjectNode equality");
        assertEquals(plain.hashCode(), fast.hashCode(), "ObjectNode hashCode");
        assertEquals(plain.toString(), fast.toString(), "serialization, including key order");
        assertEquals(plain.deepCopy(), fast.deepCopy());
        assertEquals(n(1), fast.get("a"));
        assertEquals(2, fast.size());
    }

    @Test
    void anObjectNodeBackedByItStaysMutable() {
        ObjectNode node = (ObjectNode) JsonataRuntime.objectOfDistinct(
                new String[]{"a"}, new JsonNode[]{n(1)});
        node.set("b", n(2));
        node.remove("a");
        assertEquals("{\"b\":2}", node.toString());
    }

    // -------------------------------------------------------------------------
    // Through the language
    // -------------------------------------------------------------------------

    @Test
    void constructedObjectsBehaveTheSameEndToEnd() throws Exception {
        // Wide enough to cross the index threshold, then navigated, counted and re-serialized.
        String build = "{ \"a\":1,\"b\":2,\"c\":3,\"d\":4,\"e\":5,"
                     + "  \"f\":6,\"g\":7,\"h\":8,\"i\":9,\"j\":10 }";
        assertEquals("{\"a\":1,\"b\":2,\"c\":3,\"d\":4,\"e\":5,\"f\":6,\"g\":7,\"h\":8,\"i\":9,\"j\":10}",
                JsonNodeTestHelper.evaluate(build, "{}").toString());
        assertEquals(7, JsonNodeTestHelper.evaluate("(" + build + ").g", "{}").asInt());
        assertEquals(10, JsonNodeTestHelper.evaluate("$count($keys(" + build + "))", "{}").asInt());
        assertEquals("[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\",\"g\",\"h\",\"i\",\"j\"]",
                JsonNodeTestHelper.evaluate("$keys(" + build + ")", "{}").toString());
        assertEquals(55, JsonNodeTestHelper.evaluate(
                "$sum($each(" + build + ", function($v) { $v }))", "{}").asInt());
    }

    @Test
    void aMissingValueStillDropsItsKeyEndToEnd() throws Exception {
        assertEquals("{\"a\":1,\"c\":3}",
                JsonNodeTestHelper.evaluate("{ \"a\": 1, \"b\": nope, \"c\": 3 }", "{}").toString());
    }

    @Test
    void constructedObjectsCompareEqualToParsedOnes() throws Exception {
        assertTrue(JsonNodeTestHelper.evaluate(
                "{ \"a\": 1, \"b\": 2 } = $", "{ \"a\": 1, \"b\": 2 }").asBoolean());
    }

    @Test
    void mergingAndSiftingAConstructedObjectWorks() throws Exception {
        assertEquals("{\"a\":1,\"b\":9,\"c\":3}", JsonNodeTestHelper.evaluate(
                "$merge([{ \"a\": 1, \"b\": 2 }, { \"b\": 9, \"c\": 3 }])", "{}").toString());
        assertEquals("{\"b\":2}", JsonNodeTestHelper.evaluate(
                "$sift({ \"a\": 1, \"b\": 2 }, function($v) { $v > 1 })", "{}").toString());
    }

    @Test
    void duplicateLiteralKeysStillRaiseD1009() {
        // The compile-time distinctness check must route these to the checking constructor.
        org.json_kula.jsonata_jvm.JsonataEvaluationException error =
                assertThrows(org.json_kula.jsonata_jvm.JsonataEvaluationException.class,
                        () -> JsonNodeTestHelper.evaluate("{ \"a\": 1, \"a\": 2 }", "{}"));
        assertEquals("D1009", error.getErrorCode());
    }
}

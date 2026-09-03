package org.json_kula.jsonata_jvm.runtime;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

/**
 * Backing store for an object built by an object constructor with literal keys.
 *
 * <p>Such an object is written once, from a key list already known to be distinct at compile time,
 * and then usually read a handful of times or serialized. Jackson's default {@link
 * java.util.LinkedHashMap} is built for none of that: it hashes every key on the way in, allocates
 * an {@code Entry} per field plus a bucket table, and performs a duplicate check that the compiler
 * has already done. Filling two parallel arrays instead makes construction a run of pointer stores.
 *
 * <p>Profiling the analytical benchmark put {@code HashMap.putVal} at 86% of the time spent in the
 * object constructor, and the constructor at 18% of evaluation; replacing it here measured about
 * +10% end to end.
 *
 * <p>Reads do not pay for that. Below {@link #INDEX_THRESHOLD} fields a scan that tries {@code ==}
 * before {@code equals} beats a hash lookup, and above it a hash index is built on the first read —
 * on the read, not on the write, so an object that is only serialized never builds one and one that
 * is navigated repeatedly still gets constant-time lookup.
 *
 * <p>The map stays a fully general {@link Map}: {@link com.fasterxml.jackson.databind.node.ObjectNode}
 * is mutable, and a transform or {@code $merge} may add or remove fields afterwards. Structural
 * changes simply drop the index, which the next read rebuilds.
 */
final class ConstructedObjectMap extends AbstractMap<String, JsonNode> {

    /** Field count above which a read builds a hash index rather than scanning. */
    private static final int INDEX_THRESHOLD = 8;

    private String[] keys;
    private JsonNode[] values;
    private int size;

    /** Key to slot. Built on demand by {@link #slotOf}, dropped by any structural change. */
    private HashMap<String, Integer> index;

    /**
     * Fills the map from a constructor's key and value arrays, skipping the keys whose value is
     * missing. No lookup is performed: the caller has established that {@code keys} holds no
     * duplicates.
     *
     * <p>The arrays are copied rather than retained — {@code keys} is a shared static field of the
     * generated class, and this map is free to be mutated afterwards.
     */
    ConstructedObjectMap(String[] keys, JsonNode[] values) {
        String[] k = new String[keys.length];
        JsonNode[] v = new JsonNode[keys.length];
        int n = 0;
        for (int i = 0; i < keys.length; i++) {
            JsonNode value = values[i];
            if (value == null || value == JsonataRuntime.MISSING) continue;
            k[n] = keys[i];
            v[n] = value;
            n++;
        }
        this.keys = k;
        this.values = v;
        this.size = n;
    }

    // -----------------------------------------------------------------------------------------
    // Lookup
    // -----------------------------------------------------------------------------------------

    private int slotOf(Object key) {
        if (size > INDEX_THRESHOLD) {
            HashMap<String, Integer> idx = index;
            if (idx == null) {
                idx = new HashMap<>((size * 4 / 3) + 1);
                for (int i = 0; i < size; i++) idx.put(keys[i], i);
                index = idx;
            }
            Integer slot = idx.get(key);
            return slot == null ? -1 : slot;
        }
        // Identity first: the keys are constant-pool strings from the generated class, so the
        // reference comparison settles almost every lookup without touching String.equals.
        final String[] k = keys;
        for (int i = 0, n = size; i < n; i++) {
            if (k[i] == key) return i;
        }
        if (key == null) return -1;
        for (int i = 0, n = size; i < n; i++) {
            if (key.equals(k[i])) return i;
        }
        return -1;
    }

    @Override
    public JsonNode get(Object key) {
        int slot = slotOf(key);
        return slot < 0 ? null : values[slot];
    }

    @Override
    public boolean containsKey(Object key) {
        return slotOf(key) >= 0;
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public boolean isEmpty() {
        return size == 0;
    }

    // -----------------------------------------------------------------------------------------
    // Mutation
    // -----------------------------------------------------------------------------------------

    @Override
    public JsonNode put(String key, JsonNode value) {
        int slot = slotOf(key);
        if (slot >= 0) {
            JsonNode previous = values[slot];
            values[slot] = value;
            return previous;
        }
        if (size == keys.length) {
            int capacity = Math.max(4, keys.length * 2);
            keys = Arrays.copyOf(keys, capacity);
            values = Arrays.copyOf(values, capacity);
        }
        keys[size] = key;
        values[size] = value;
        size++;
        index = null;
        return null;
    }

    @Override
    public JsonNode remove(Object key) {
        int slot = slotOf(key);
        if (slot < 0) return null;
        JsonNode previous = values[slot];
        int tail = size - 1 - slot;
        if (tail > 0) {
            System.arraycopy(keys, slot + 1, keys, slot, tail);
            System.arraycopy(values, slot + 1, values, slot, tail);
        }
        size--;
        keys[size] = null;
        values[size] = null;
        index = null;
        return previous;
    }

    @Override
    public void clear() {
        Arrays.fill(keys, 0, size, null);
        Arrays.fill(values, 0, size, null);
        size = 0;
        index = null;
    }

    // -----------------------------------------------------------------------------------------
    // Views
    // -----------------------------------------------------------------------------------------

    @Override
    public void forEach(java.util.function.BiConsumer<? super String, ? super JsonNode> action) {
        for (int i = 0, n = size; i < n; i++) action.accept(keys[i], values[i]);
    }

    @Override
    public Set<Entry<String, JsonNode>> entrySet() {
        return new AbstractSet<>() {
            @Override
            public int size() {
                return size;
            }

            @Override
            public boolean contains(Object o) {
                if (!(o instanceof Map.Entry<?, ?> e)) return false;
                int slot = slotOf(e.getKey());
                return slot >= 0 && Objects.equals(values[slot], e.getValue());
            }

            @Override
            public Iterator<Entry<String, JsonNode>> iterator() {
                return new Iterator<>() {
                    private int next;

                    @Override
                    public boolean hasNext() {
                        return next < size;
                    }

                    @Override
                    public Entry<String, JsonNode> next() {
                        if (next >= size) throw new NoSuchElementException();
                        return new Slot(next++);
                    }

                    @Override
                    public void remove() {
                        if (next == 0) throw new IllegalStateException();
                        ConstructedObjectMap.this.remove(keys[--next]);
                    }
                };
            }
        };
    }

    /** An entry that reads and writes through to the arrays, as a map entry must. */
    private final class Slot implements Map.Entry<String, JsonNode> {
        private final int slot;

        Slot(int slot) {
            this.slot = slot;
        }

        @Override
        public String getKey() {
            return keys[slot];
        }

        @Override
        public JsonNode getValue() {
            return values[slot];
        }

        @Override
        public JsonNode setValue(JsonNode value) {
            JsonNode previous = values[slot];
            values[slot] = value;
            return previous;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Map.Entry<?, ?> other
                    && Objects.equals(getKey(), other.getKey())
                    && Objects.equals(getValue(), other.getValue());
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(getKey()) ^ Objects.hashCode(getValue());
        }

        @Override
        public String toString() {
            return getKey() + "=" + getValue();
        }
    }
}

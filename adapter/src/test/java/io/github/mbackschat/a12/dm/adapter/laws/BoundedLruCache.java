package io.github.mbackschat.a12.dm.adapter.laws;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/** Small synchronized access-order cache. Test-oracle engines are expensive and retain generated classes, so
 * the shared convenience path needs a hard per-fork bound rather than an unbounded static map. */
final class BoundedLruCache<K, V> {
    private final int capacity;
    private final Map<K, V> entries;

    BoundedLruCache(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.entries = new LinkedHashMap<>(capacity, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > BoundedLruCache.this.capacity;
            }
        };
    }

    synchronized V getOrCompute(K key, Function<K, V> factory) {
        V existing = entries.get(key);
        if (existing != null) {
            return existing;
        }
        V created = factory.apply(key);
        entries.put(key, created);
        return created;
    }

    synchronized V get(K key) {
        return entries.get(key);
    }

    synchronized void put(K key, V value) {
        entries.put(key, value);
    }

    synchronized int size() {
        return entries.size();
    }

    synchronized void clear() {
        entries.clear();
    }
}

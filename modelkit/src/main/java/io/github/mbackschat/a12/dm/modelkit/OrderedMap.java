package io.github.mbackschat.a12.dm.modelkit;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable defensive copies for order-bearing maps serialized into A12 document arrays. */
final class OrderedMap {

    private OrderedMap() {
    }

    static <K, V> Map<K, V> copyOf(Map<? extends K, ? extends V> source) {
        if (source.isEmpty()) {
            return Map.of();
        }
        Map<K, V> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(Objects.requireNonNull(key), Objects.requireNonNull(value)));
        return Collections.unmodifiableMap(copy);
    }
}

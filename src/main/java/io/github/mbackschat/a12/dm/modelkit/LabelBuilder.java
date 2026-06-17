package io.github.mbackschat.a12.dm.modelkit;

import java.util.LinkedHashMap;
import java.util.Map;

/** Collects per-locale display labels for one enumeration value. */
public final class LabelBuilder {
    final Map<String, String> labels = new LinkedHashMap<>();

    public LabelBuilder label(String locale, String text) {
        labels.put(locale, text);
        return this;
    }
}

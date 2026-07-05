package io.github.mbackschat.a12.dm.modelkit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A field's non-type metadata — per-locale {@code labels}, element {@code annotations}, the kernel
 * {@code global}/{@code transient} flags, and per-locale internal/external {@code description} + {@code
 * helperText}. Extracted from {@link ElementDraft.Field} so a field is the cohesive {@code (name, type,
 * requiredness, meta)} rather than a long positional constructor: these aspects accreted incrementally
 * (FEATURE-GAPS) and grouping them keeps the clump a single named concept (and the constructor honest).
 * Built via {@link #builder()} — a positional constructor with this many optional aspects is error-prone
 * (the same reason {@link FieldModification} uses a builder).
 */
public record FieldMeta(
        Map<String, String> labels,
        List<ElementDraft.Annotation> annotations,
        boolean global,
        boolean notPersisted,
        Map<String, String> internalDescription,
        Map<String, String> externalDescription,
        Map<String, String> helperText) {

    public FieldMeta {
        labels = labels == null ? Map.of() : Map.copyOf(labels);
        annotations = annotations == null ? List.of() : List.copyOf(annotations);
        internalDescription = internalDescription == null ? Map.of() : Map.copyOf(internalDescription);
        externalDescription = externalDescription == null ? Map.of() : Map.copyOf(externalDescription);
        helperText = helperText == null ? Map.of() : Map.copyOf(helperText);
    }

    /** No metadata — the default for a bare field. */
    public static FieldMeta none() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Collects a field's metadata aspects; an unset aspect defaults to empty / {@code false}. */
    public static final class Builder {
        private final Map<String, String> labels = new LinkedHashMap<>();
        private final List<ElementDraft.Annotation> annotations = new ArrayList<>();
        private boolean global;
        private boolean notPersisted;
        private final Map<String, String> internalDescription = new LinkedHashMap<>();
        private final Map<String, String> externalDescription = new LinkedHashMap<>();
        private final Map<String, String> helperText = new LinkedHashMap<>();

        public Builder labels(Map<String, String> v) { this.labels.putAll(v); return this; }
        public Builder annotations(List<ElementDraft.Annotation> v) { this.annotations.addAll(v); return this; }
        public Builder global(boolean v) { this.global = v; return this; }
        public Builder notPersisted(boolean v) { this.notPersisted = v; return this; }
        public Builder internalDescription(Map<String, String> v) { this.internalDescription.putAll(v); return this; }
        public Builder externalDescription(Map<String, String> v) { this.externalDescription.putAll(v); return this; }
        public Builder helperText(Map<String, String> v) { this.helperText.putAll(v); return this; }

        public FieldMeta build() {
            return new FieldMeta(labels, annotations, global, notPersisted,
                    internalDescription, externalDescription, helperText);
        }
    }
}

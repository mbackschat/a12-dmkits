package io.github.mbackschat.a12.dm.modelkit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A group's non-structural metadata — its per-locale display {@code labels} (the kernel's {@code ILabeled},
 * which a group carries via {@code IEntity}, exactly like a field), element {@code annotations}, and per-locale
 * internal/external {@code description}. The sibling of {@link FieldMeta}: a group has the same
 * {@code ILabeled}+{@code IDescribed}+{@code IAnnotated} element metadata as a field, but none of the
 * field-body extras ({@code global}/{@code transient}/{@code helperText}). Extracted from
 * {@link ElementDraft.Group} so a group is the cohesive {@code (…structure…, meta)} rather than a long
 * positional constructor — the same Extract-Class that keeps {@link FieldMeta} honest. Built via
 * {@link #builder()}.
 */
public record GroupMeta(
        Map<String, String> labels,
        List<ElementDraft.Annotation> annotations,
        Map<String, String> internalDescription,
        Map<String, String> externalDescription) {

    public GroupMeta {
        labels = labels == null ? Map.of() : OrderedMap.copyOf(labels);
        annotations = annotations == null ? List.of() : List.copyOf(annotations);
        internalDescription = internalDescription == null ? Map.of() : OrderedMap.copyOf(internalDescription);
        externalDescription = externalDescription == null ? Map.of() : OrderedMap.copyOf(externalDescription);
    }

    /** No metadata — the default for a bare group. */
    public static GroupMeta none() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Collects a group's metadata aspects; an unset aspect defaults to empty. */
    public static final class Builder {
        private final Map<String, String> labels = new LinkedHashMap<>();
        private final List<ElementDraft.Annotation> annotations = new ArrayList<>();
        private final Map<String, String> internalDescription = new LinkedHashMap<>();
        private final Map<String, String> externalDescription = new LinkedHashMap<>();

        public Builder labels(Map<String, String> v) { this.labels.putAll(v); return this; }
        public Builder annotations(List<ElementDraft.Annotation> v) { this.annotations.addAll(v); return this; }
        public Builder internalDescription(Map<String, String> v) { this.internalDescription.putAll(v); return this; }
        public Builder externalDescription(Map<String, String> v) { this.externalDescription.putAll(v); return this; }

        public GroupMeta build() {
            return new GroupMeta(labels, annotations, internalDescription, externalDescription);
        }
    }
}

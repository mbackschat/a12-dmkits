package io.github.mbackschat.a12.dm.cli.group;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.ElementDraft;

/**
 * The shared {@code group modify} setter cascade (T4) — a value object holding a group's edit directives
 * (repeatability, index field, row sort, metadata), single-sourcing the mutation + {@code changed}-report
 * between the standalone {@code group modify} verb and the {@code apply} {@code GROUP_MODIFY} op. Both build
 * one of these (from picocli options / from a JSON op) and call {@link #applyTo}; the cascade runs exactly
 * once, so the two surfaces can't drift. Both surfaces already ride the mutable {@link EditSession}, so no
 * receiver reconciliation was needed (unlike {@code config modify}). Mirrors the
 * {@code FieldSpec}/{@code FieldModification} pattern.
 *
 * <p>Built via {@link #builder()} — a positional 13-arg constructor over this many same-typed directives is
 * too swap-prone (the {@code FieldModification} rule). An absent directive (a {@code null} scalar / empty
 * collection / {@code false} flag) means "leave it unchanged".
 */
public record GroupModification(
        Integer repeatable, boolean nonRepeatable, String indexField, boolean clearIndexField,
        List<ElementDraft.SortField> sortFields, boolean clearSortFields,
        Map<String, String> labels, Map<String, String> annotations, List<String> removeAnnotations,
        Map<String, String> internalDescriptions, Map<String, String> externalDescriptions,
        List<String> removeInternalDescriptions, List<String> removeExternalDescriptions) {

    public GroupModification {
        sortFields = copyList(sortFields);
        labels = copyMap(labels);
        annotations = copyMap(annotations);
        removeAnnotations = copyStrings(removeAnnotations);
        internalDescriptions = copyMap(internalDescriptions);
        externalDescriptions = copyMap(externalDescriptions);
        removeInternalDescriptions = copyStrings(removeInternalDescriptions);
        removeExternalDescriptions = copyStrings(removeExternalDescriptions);
    }

    /** The {@code GROUP_MODIFY} op-record keys, minus the locator (single-sourced for the apply op's key set). */
    public static final Set<String> DIRECTIVE_KEYS = Set.of("repeatable", "non-repeatable",
            "index-field", "clear-index-field", "sort-field", "clear-sort-fields",
            "label", "annotation", "remove-annotation", "internal-description", "external-description",
            "remove-internal-description", "remove-external-description");

    public static Builder builder() {
        return new Builder();
    }

    /** Parse an {@code apply} {@code GROUP_MODIFY} op-record (sort-field strings parsed via {@link SortFieldArg}). */
    public static GroupModification fromJson(JsonNode op) {
        return builder()
                .repeatable(op.hasNonNull("repeatable") ? op.get("repeatable").asInt() : null)
                .nonRepeatable(op.path("non-repeatable").asBoolean(false))
                .indexField(text(op, "index-field")).clearIndexField(op.path("clear-index-field").asBoolean(false))
                .sortFields(SortFieldArg.parseAll(stringValues(op, "sort-field")))
                .clearSortFields(op.path("clear-sort-fields").asBoolean(false))
                .labels(textMap(op, "label")).annotations(textMap(op, "annotation"))
                .removeAnnotations(stringValues(op, "remove-annotation"))
                .internalDescriptions(textMap(op, "internal-description"))
                .externalDescriptions(textMap(op, "external-description"))
                .removeInternalDescriptions(stringValues(op, "remove-internal-description"))
                .removeExternalDescriptions(stringValues(op, "remove-external-description"))
                .build();
    }

    /** True when no directive is set — the "nothing to change" guard, shared by both surfaces. */
    public boolean isEmpty() {
        return repeatable == null && !nonRepeatable && indexField == null && !clearIndexField
                && sortFields.isEmpty() && !clearSortFields && labels.isEmpty() && annotations.isEmpty()
                && removeAnnotations.isEmpty() && internalDescriptions.isEmpty() && externalDescriptions.isEmpty()
                && removeInternalDescriptions.isEmpty() && removeExternalDescriptions.isEmpty();
    }

    /** Run the group setter cascade on {@code session} at {@code groupPath}, recording each change in {@code changed}. */
    public void applyTo(String groupPath, EditSession session, ObjectNode changed) {
        changed.put("group", groupPath);
        if (repeatable != null) {
            session.setRepeatable(groupPath, repeatable);
            changed.put("repeatable", true);
            changed.put("maxRepetitions", repeatable);
        } else if (nonRepeatable) {
            session.setNonRepeatable(groupPath);
            changed.put("repeatable", false);
        }
        if (indexField != null) {
            session.setIndexField(groupPath, indexField);
            changed.put("indexField", indexField);
        } else if (clearIndexField) {
            session.clearIndexField(groupPath);
            changed.putNull("indexField");
        }
        if (!sortFields.isEmpty()) {
            session.setSortFields(groupPath, sortFields);
            ArrayNode arr = changed.putArray("sortFields");
            for (ElementDraft.SortField sf : sortFields) {
                ObjectNode n = arr.addObject();
                n.put("name", sf.fieldName());
                n.put("order", sf.order().name());
            }
        } else if (clearSortFields) {
            session.clearSortFields(groupPath);
            changed.putArray("sortFields");
        }
        if (!labels.isEmpty()) {
            session.setLabel(groupPath, labels);
            labels.forEach((locale, text) -> changed.put("label." + locale, text));
        }
        annotations.forEach((name, value) -> {
            session.setAnnotation(groupPath, name, value);
            changed.put("annotation." + name, value);
        });
        removeAnnotations.forEach(name -> {
            session.removeAnnotation(groupPath, name);
            changed.putNull("annotation." + name);
        });
        internalDescriptions.forEach((locale, text) -> {
            session.setInternalDescription(groupPath, locale, text);
            changed.put("internalDescription." + locale, text);
        });
        externalDescriptions.forEach((locale, text) -> {
            session.setExternalDescription(groupPath, locale, text);
            changed.put("externalDescription." + locale, text);
        });
        removeInternalDescriptions.forEach(locale -> {
            session.removeInternalDescription(groupPath, locale);
            changed.putNull("internalDescription." + locale);
        });
        removeExternalDescriptions.forEach(locale -> {
            session.removeExternalDescription(groupPath, locale);
            changed.putNull("externalDescription." + locale);
        });
    }

    /** The human-readable summary fragments for the standalone verb's {@code applied} message (apply uses {@code changed}). */
    public List<String> summaryParts() {
        List<String> parts = new ArrayList<>();
        if (repeatable != null) {
            parts.add("repeatable (max " + repeatable + ")");
        } else if (nonRepeatable) {
            parts.add("non-repeating");
        }
        if (indexField != null) {
            parts.add("index field " + indexField);
        } else if (clearIndexField) {
            parts.add("index field cleared");
        }
        if (!sortFields.isEmpty()) {
            List<String> keys = new ArrayList<>();
            for (ElementDraft.SortField sf : sortFields) {
                keys.add(sf.fieldName() + " " + sf.order().name().toLowerCase(Locale.ROOT));
            }
            parts.add("sort by " + String.join(", ", keys));
        } else if (clearSortFields) {
            parts.add("sort fields cleared");
        }
        labels.forEach((locale, text) -> parts.add("label[" + locale + "]"));
        annotations.forEach((name, value) -> parts.add("annotation " + name));
        removeAnnotations.forEach(name -> parts.add("annotation " + name + " removed"));
        internalDescriptions.forEach((locale, text) -> parts.add("internal-description[" + locale + "]"));
        externalDescriptions.forEach((locale, text) -> parts.add("external-description[" + locale + "]"));
        return parts;
    }

    private static List<ElementDraft.SortField> copyList(List<ElementDraft.SortField> l) {
        return l == null ? List.of() : List.copyOf(l);
    }

    private static List<String> copyStrings(List<String> l) {
        return l == null ? List.of() : List.copyOf(l);
    }

    private static Map<String, String> copyMap(Map<String, String> m) {
        return m == null ? Map.of() : Map.copyOf(m);
    }

    private static String text(JsonNode op, String key) {
        return op.hasNonNull(key) ? op.get(key).asText() : null;
    }

    private static Map<String, String> textMap(JsonNode op, String key) {
        Map<String, String> out = new LinkedHashMap<>();
        if (op.hasNonNull(key)) {
            op.get(key).fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asText()));
        }
        return out;
    }

    /** A repeatable op-record key: an array yields its elements, a bare scalar binds as a 1-element list. */
    private static List<String> stringValues(JsonNode op, String key) {
        List<String> out = new ArrayList<>();
        JsonNode node = op.get(key);
        if (node != null && node.isArray()) {
            node.forEach(n -> out.add(n.asText()));
        } else if (node != null && !node.isNull()) {
            out.add(node.asText());
        }
        return out;
    }

    /** Collects the directives; a {@code null}/empty/{@code false} argument means "leave this setting unchanged". */
    public static final class Builder {
        private Integer repeatable;
        private boolean nonRepeatable;
        private String indexField;
        private boolean clearIndexField;
        private List<ElementDraft.SortField> sortFields;
        private boolean clearSortFields;
        private Map<String, String> labels;
        private Map<String, String> annotations;
        private List<String> removeAnnotations;
        private Map<String, String> internalDescriptions;
        private Map<String, String> externalDescriptions;
        private List<String> removeInternalDescriptions;
        private List<String> removeExternalDescriptions;

        public Builder repeatable(Integer v) { this.repeatable = v; return this; }
        public Builder nonRepeatable(boolean v) { this.nonRepeatable = v; return this; }
        public Builder indexField(String v) { this.indexField = v; return this; }
        public Builder clearIndexField(boolean v) { this.clearIndexField = v; return this; }
        public Builder sortFields(List<ElementDraft.SortField> v) { this.sortFields = v; return this; }
        public Builder clearSortFields(boolean v) { this.clearSortFields = v; return this; }
        public Builder labels(Map<String, String> v) { this.labels = v; return this; }
        public Builder annotations(Map<String, String> v) { this.annotations = v; return this; }
        public Builder removeAnnotations(List<String> v) { this.removeAnnotations = v; return this; }
        public Builder internalDescriptions(Map<String, String> v) { this.internalDescriptions = v; return this; }
        public Builder externalDescriptions(Map<String, String> v) { this.externalDescriptions = v; return this; }
        public Builder removeInternalDescriptions(List<String> v) { this.removeInternalDescriptions = v; return this; }
        public Builder removeExternalDescriptions(List<String> v) { this.removeExternalDescriptions = v; return this; }

        public GroupModification build() {
            return new GroupModification(repeatable, nonRepeatable, indexField, clearIndexField, sortFields,
                    clearSortFields, labels, annotations, removeAnnotations, internalDescriptions,
                    externalDescriptions, removeInternalDescriptions, removeExternalDescriptions);
        }
    }
}

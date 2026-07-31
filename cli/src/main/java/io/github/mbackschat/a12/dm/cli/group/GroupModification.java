package io.github.mbackschat.a12.dm.cli.group;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.ElementDraft;

/**
 * The shared {@code group modify} setter cascade (T4) — a value object holding a group's semantic edit
 * directives (repeatability, index field, and row sort), single-sourcing the mutation + {@code changed}-report
 * between the standalone {@code group modify} verb and the {@code apply} {@code GROUP_MODIFY} op. Both build
 * one of these (from picocli options / from a JSON op) and call {@link #applyTo}; the cascade runs exactly
 * once, so the two surfaces can't drift. Both surfaces already ride the mutable {@link EditSession}, so no
 * receiver reconciliation was needed (unlike {@code config modify}). Mirrors the
 * {@code FieldSpec}/{@code FieldModification} pattern.
 *
 * <p>Built via {@link #builder()} so the two same-typed string directives stay named at their call sites.
 * An absent directive (a {@code null} scalar / empty collection / {@code false} flag) means "leave it unchanged".
 */
public record GroupModification(
        Integer repeatable, boolean nonRepeatable, String indexField, boolean clearIndexField,
        List<ElementDraft.SortField> sortFields, boolean clearSortFields) {

    public GroupModification {
        sortFields = copyList(sortFields);
    }

    /** The {@code GROUP_MODIFY} op-record keys, minus the locator (single-sourced for the apply op's key set). */
    public static final Set<String> DIRECTIVE_KEYS = Set.of("repeatable", "non-repeatable",
            "index-field", "clear-index-field", "sort-field", "clear-sort-fields");

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
                .build();
    }

    /** True when no directive is set — the "nothing to change" guard, shared by both surfaces. */
    public boolean isEmpty() {
        return repeatable == null && !nonRepeatable && indexField == null && !clearIndexField
                && sortFields.isEmpty() && !clearSortFields;
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
        return parts;
    }

    private static List<ElementDraft.SortField> copyList(List<ElementDraft.SortField> l) {
        return l == null ? List.of() : List.copyOf(l);
    }

    private static String text(JsonNode op, String key) {
        return op.hasNonNull(key) ? op.get(key).asText() : null;
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

        public Builder repeatable(Integer v) { this.repeatable = v; return this; }
        public Builder nonRepeatable(boolean v) { this.nonRepeatable = v; return this; }
        public Builder indexField(String v) { this.indexField = v; return this; }
        public Builder clearIndexField(boolean v) { this.clearIndexField = v; return this; }
        public Builder sortFields(List<ElementDraft.SortField> v) { this.sortFields = v; return this; }
        public Builder clearSortFields(boolean v) { this.clearSortFields = v; return this; }
        public GroupModification build() {
            return new GroupModification(repeatable, nonRepeatable, indexField, clearIndexField,
                    sortFields, clearSortFields);
        }
    }
}

package io.github.mbackschat.a12.dm.modelkit;

import java.util.List;
import java.util.Map;

/**
 * An immutable node in a structure draft — a {@link Field}, a (possibly repeatable) {@link Group},
 * or a {@link Mount} (an include point referencing another model). Sealed so the emitter dispatches
 * with an exhaustive pattern switch (no {@code default}); the typed builders in
 * {@link DocumentModelDraft} are the only way to construct these, so illegal shapes (a field with no
 * kind, a negative repeatability) are unrepresentable.
 */
public sealed interface ElementDraft
        permits ElementDraft.Field, ElementDraft.Group, ElementDraft.Mount, ElementDraft.Rule {

    /** The element's short name — the last segment of its name-path. */
    String name();

    /** Element-level name/value metadata (the kernel's {@code annotations}). */
    List<Annotation> annotations();

    /** A name/value annotation pair. */
    record Annotation(String name, String value) {}

    /** A repeatable group's declared row sort key — a direct-child field name + its {@link SortOrder}. */
    record SortField(String fieldName, SortOrder order) {}

    /**
     * A leaf field — its short name, typed config ({@link FieldTypeSpec}), how it must be filled
     * ({@link Requiredness}), and its non-type {@link FieldMeta metadata} (labels, annotations, the
     * {@code global}/{@code transient} flags, internal/external descriptions, helper text). The metadata is
     * grouped into one cohesive value so a field stays a four-part record instead of a long positional
     * constructor (the aspects had accreted incrementally).
     */
    record Field(String name, FieldTypeSpec type, Requiredness requiredness, FieldMeta meta)
            implements ElementDraft {

        public Field {
            meta = meta == null ? FieldMeta.none() : meta;
        }

        /** A bare field — no metadata (labels/annotations/flags/descriptions all default). */
        public Field(String name, FieldTypeSpec type, Requiredness requiredness) {
            this(name, type, requiredness, FieldMeta.none());
        }

        /** The field's element annotations (the {@link ElementDraft#annotations()} contract) — from its metadata. */
        @Override
        public List<Annotation> annotations() {
            return meta.annotations();
        }
    }

    /**
     * A group; {@code repeatability == 1} is single, {@code > 1} a repeatable list. A repeatable group
     * may carry an {@code indexFieldName} — the direct child field whose value identifies a row (so two
     * lists can be iterated in lockstep); {@code null} when unset. {@code usageType} is the kernel's
     * group-template marker ({@code "attachment"} / {@code "multi-select"}, FEATURE-GAPS); {@code null}
     * for a plain group. Its non-structural {@link GroupMeta metadata} (labels, annotations, descriptions)
     * is grouped into one cohesive value so a group stays a compact record instead of a long positional
     * constructor — the same Extract-Class as {@link Field}'s {@link FieldMeta}.
     */
    record Group(String name, int repeatability, String indexFieldName, List<ElementDraft> elements,
            String usageType, List<SortField> sortFields, GroupMeta meta)
            implements ElementDraft {

        public Group {
            elements = elements == null ? List.of() : List.copyOf(elements);
            sortFields = sortFields == null ? List.of() : List.copyOf(sortFields);
            meta = meta == null ? GroupMeta.none() : meta;
        }

        /** A plain group — no {@code usageType} template marker, no declared row sort. */
        public Group(String name, int repeatability, String indexFieldName, List<ElementDraft> elements,
                GroupMeta meta) {
            this(name, repeatability, indexFieldName, elements, null, List.of(), meta);
        }

        /** A group with a {@code usageType} template marker, no declared row sort. */
        public Group(String name, int repeatability, String indexFieldName, List<ElementDraft> elements,
                String usageType, GroupMeta meta) {
            this(name, repeatability, indexFieldName, elements, usageType, List.of(), meta);
        }

        /** The group's element annotations (the {@link ElementDraft#annotations()} contract) — from its metadata. */
        @Override
        public List<Annotation> annotations() {
            return meta.annotations();
        }
    }

    /**
     * An include mount: a group with no inline elements that carries {@code modelAlias}; on expand the
     * kernel mounts the referenced model's content here (the {@code alias} resolves via the header's
     * {@code modelReferences}). KERNEL-FINDINGS §10. The {@link MountOptions} carry {@code repeatability}
     * (&gt; 1 = a repeatable sub-form, FEATURE-GAPS) and the rule/computation exclusions.
     */
    record Mount(String name, String alias, MountOptions options, List<Annotation> annotations)
            implements ElementDraft {

        /** A single (non-repeatable) mount, including the model's rules and computations. */
        public Mount(String name, String alias, List<Annotation> annotations) {
            this(name, alias, MountOptions.single(), annotations);
        }

        /** A mount of {@code repeatability} rows, including the model's rules and computations. */
        public Mount(String name, String alias, int repeatability, List<Annotation> annotations) {
            this(name, alias, MountOptions.repeatable(repeatability), annotations);
        }
    }

    /**
     * A <b>raw rule node</b> — a fixed {@code errorCondition} (in the model's condition language) plus its
     * {@code errorCode} / {@code severity} / per-locale {@code messages} / {@code errorEntityRelPath}. This
     * is the structural emission primitive used by <b>template generators</b> (the attachment template's
     * kernel-mandated rules, FEATURE-GAPS) — <em>not</em> a logic-authoring API: author real rules with
     * rulekit's typed DSL ({@code EditSession.addRule}), which renders + kernel-validates the condition. Rules
     * carry no element-level annotations here.
     */
    record Rule(String name, String errorEntityRelPath, String errorCode, String errorCondition,
            String severity, Map<String, String> messages) implements ElementDraft {

        public Rule {
            messages = OrderedMap.copyOf(messages);
        }

        @Override
        public List<Annotation> annotations() {
            return List.of();
        }
    }
}

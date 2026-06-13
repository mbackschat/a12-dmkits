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
public sealed interface ElementDraft permits ElementDraft.Field, ElementDraft.Group, ElementDraft.Mount {

    /** The element's short name — the last segment of its name-path. */
    String name();

    /** Element-level name/value metadata (the kernel's {@code annotations}). */
    List<Annotation> annotations();

    /** A name/value annotation pair. */
    record Annotation(String name, String value) {}

    /**
     * A leaf field — its typed config ({@link FieldTypeSpec}), how it must be filled, per-locale display
     * {@code labels} (locale → text), and element-level {@code annotations}. Plus the field-level metadata
     * (FEATURE-GAPS §3.5): the kernel flags {@code global} (relevant to partial validation) and
     * {@code notPersisted} (the kernel {@code transient} flag — not stored), and three per-locale text slots
     * distinct from the label — {@code internalDescription} / {@code externalDescription} (element-level) and
     * {@code helperText} (field-level UI help).
     */
    record Field(String name, FieldTypeSpec type, Requiredness requiredness, Map<String, String> labels,
            List<Annotation> annotations, boolean global, boolean notPersisted,
            Map<String, String> internalDescription, Map<String, String> externalDescription,
            Map<String, String> helperText) implements ElementDraft {

        public Field {
            internalDescription = internalDescription == null ? Map.of() : Map.copyOf(internalDescription);
            externalDescription = externalDescription == null ? Map.of() : Map.copyOf(externalDescription);
            helperText = helperText == null ? Map.of() : Map.copyOf(helperText);
        }

        /** The pre-§3.5 form: no field-level flags or description/helper text. */
        public Field(String name, FieldTypeSpec type, Requiredness requiredness, Map<String, String> labels,
                List<Annotation> annotations) {
            this(name, type, requiredness, labels, annotations, false, false, Map.of(), Map.of(), Map.of());
        }
    }

    /**
     * A group; {@code repeatability == 1} is single, {@code > 1} a repeatable list. A repeatable group
     * may carry an {@code indexFieldName} — the direct child field whose value identifies a row (so two
     * lists can be iterated in lockstep); {@code null} when unset. {@code usageType} is the kernel's
     * group-template marker ({@code "attachment"} / {@code "multi-select"}, FEATURE-GAPS §3.8); {@code null}
     * for a plain group.
     */
    record Group(String name, int repeatability, String indexFieldName, List<ElementDraft> elements,
            List<Annotation> annotations, String usageType) implements ElementDraft {

        /** A plain group — no {@code usageType} template marker. */
        public Group(String name, int repeatability, String indexFieldName, List<ElementDraft> elements,
                List<Annotation> annotations) {
            this(name, repeatability, indexFieldName, elements, annotations, null);
        }
    }

    /**
     * An include mount: a group with no inline elements that carries {@code modelAlias}; on expand the
     * kernel mounts the referenced model's content here (the {@code alias} resolves via the header's
     * {@code modelReferences}). KERNEL-FINDINGS §10. The {@link MountOptions} carry {@code repeatability}
     * (&gt; 1 = a repeatable sub-form, FEATURE-GAPS §2) and the rule/computation exclusions (§3.9).
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
}

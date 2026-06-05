package io.github.mbackschat.a12.dm.modelkit;

import java.util.List;
import java.util.Map;

/**
 * An immutable node in a structure draft — a {@link Field} or a (possibly repeatable) {@link Group}.
 * Sealed so the emitter dispatches with an exhaustive pattern switch (no {@code default}); the typed
 * builders in {@link DocumentModelDraft} are the only way to construct these, so illegal shapes
 * (a field with no kind, a negative repeatability) are unrepresentable.
 */
public sealed interface ElementDraft permits ElementDraft.Field, ElementDraft.Group {

    /** The element's short name — the last segment of its name-path. */
    String name();

    /** Element-level name/value metadata (the kernel's {@code annotations}). */
    List<Annotation> annotations();

    /** A name/value annotation pair. */
    record Annotation(String name, String value) {}

    /**
     * A leaf field — its typed config ({@link FieldTypeSpec}), how it must be filled, per-locale
     * display {@code labels} (locale → text), and element-level {@code annotations}.
     */
    record Field(String name, FieldTypeSpec type, Requiredness requiredness,
            Map<String, String> labels, List<Annotation> annotations) implements ElementDraft {}

    /** A group; {@code repeatability == 1} is single, {@code > 1} a repeatable list. */
    record Group(String name, int repeatability, List<ElementDraft> elements,
            List<Annotation> annotations) implements ElementDraft {}
}

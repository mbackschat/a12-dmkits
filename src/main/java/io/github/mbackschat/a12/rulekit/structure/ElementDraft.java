package io.github.mbackschat.a12.rulekit.structure;

import java.util.List;

/**
 * An immutable node in a structure draft — a {@link Field} or a (possibly repeatable) {@link Group}.
 * Sealed so the emitter dispatches with an exhaustive pattern switch (no {@code default}); the typed
 * builders in {@link DocumentModelDraft} are the only way to construct these, so illegal shapes
 * (a field with no kind, a negative repeatability) are unrepresentable.
 */
public sealed interface ElementDraft permits ElementDraft.Field, ElementDraft.Group {

    /** The element's short name — the last segment of its name-path. */
    String name();

    /** A leaf field of a given {@link FieldKind}. */
    record Field(String name, FieldKind kind) implements ElementDraft {}

    /** A group; {@code repeatability == 1} is single, {@code > 1} a repeatable list. */
    record Group(String name, int repeatability, List<ElementDraft> elements) implements ElementDraft {}
}

package io.github.mbackschat.a12.dm.rulekit.dsl;

/**
 * The compound identity of a semantic-index-selected entity — {@code (entity path, index key, every
 * $ correlation in the shape)} — shared between {@link IndexedFieldValue} and {@link
 * IndexedGroupValue} rather than each computing it independently, since the shape is identical
 * regardless of what the index targets: only the base entity (a field's path, a group's path) and
 * whether that base entity itself can carry a {@code $} differ.
 *
 * <p>Doubles as the plain diagnostic {@code renderedPath()} both interfaces expose — a reference that
 * prints the same diagnostic path IS the same identity, so there is no second, looser string to keep
 * in sync with this one.
 */
final class SemanticIndexIdentity {

    private SemanticIndexIdentity() {
    }

    /** {@code base For "literal"}, correlation-folded on {@code base} only. */
    static String of(boolean baseCorrelated, String basePath, String literalKey) {
        return (baseCorrelated ? "$" : "") + basePath + " For \"" + literalKey + "\"";
    }

    /** {@code base For keyField}, correlation-folded on {@code base} AND on the key field independently —
     *  a correlated key reads a different repetition than an uncorrelated one, so the two must stay
     *  distinguishable (the same fold commit {@code 2a5c9a409} applied to a plain {@link FieldRef}). */
    static String of(boolean baseCorrelated, String basePath, FieldRef keyField) {
        return (baseCorrelated ? "$" : "") + basePath + " For "
                + (keyField.correlated() ? "$" : "") + keyField.renderedPath();
    }
}

package io.github.mbackschat.a12.dm.rulekit.dsl;

/**
 * Provenance hint on a {@link FieldRef}: how the reference's path form was (or should be) chosen
 * (D14/F10, proposal §8/§12). Builder-made refs are {@link #CANONICAL} — the renderer picks the
 * correct/safest form per the path policy (§18 fork 9). The text→AST converter (F10) constructs
 * refs carrying the <em>authored</em> style it finds in the kernel's parse tree
 * ({@code PathNotationConfig}), so a re-render can preserve the author's notation where legal —
 * softening the canonicalization blast radius of modify (§12).
 */
public enum PathStyle {
    /** No authored style — the renderer decides (builder-made nodes). */
    CANONICAL,
    /** Authored as an absolute path ({@code /Group/Field}). */
    ABSOLUTE,
    /** Authored relative to the rule group ({@code ../Field}, {@code Field}-in-group). */
    RELATIVE,
    /** Authored as a short name (model must allow {@code fieldRefByShortNameAllowed}). */
    SHORT
}

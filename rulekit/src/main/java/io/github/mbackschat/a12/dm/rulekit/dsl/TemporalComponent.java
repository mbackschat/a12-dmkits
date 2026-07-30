package io.github.mbackschat.a12.dm.rulekit.dsl;

/**
 * One supplied {@code Date(...)} or {@code Time(...)} component — the kernel's {@code datumParam} union.
 *
 * <p>A component is either a numeric expression (including a quoted numeric constant or a matching temporal
 * extractor) or a bare field. The field's declaration decides whether that position is legal: the kernel admits
 * bounded Number fields, exact digit-pattern String fields, and an exact {@code yyyy} Date field only at the
 * complete-Year position. Keeping that model-dependent gate at validation time lets mixed legal sources remain
 * authorable without pretending every field kind is valid everywhere.
 */
public sealed interface TemporalComponent permits FieldRef, NumberExpr, TemporalComponentLiteral {

    /** The expression rendered in the constructor's bare component position. */
    Calculation expression();
}

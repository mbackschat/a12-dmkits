package io.github.mbackschat.a12.dm.rulekit.dsl;

/**
 * One supplied {@code Date(...)} or {@code Time(...)} component — the kernel's {@code datumParam} union.
 *
 * <p>A component is a bare field, the cell a semantic index selects in one, written {@code Field For key}
 * ({@code KF531}), a quoted numeric constant, or a temporal extractor. The numeric-expression arm exists for the
 * extractor and the number literal; any other expression there is refused when the construction is built, with
 * the class A12 draws ({@code KF532}, {@link TemporalComponentGrammar}). The field's declaration decides whether
 * that position is legal: the kernel admits bounded Number fields, exact digit-pattern String fields, and — only at the complete-Year position — a
 * temporal field whose DECLARED FORMAT is exactly {@code yyyy}, on any date-bearing kind. It is the declaration
 * and not the kind: a TIME field and a DATE_TIME field declared {@code yyyy} are admitted at Year exactly as a
 * DATE one is, while any of the three declared {@code yyyy-MM-dd} is refused there ({@code KF211}'s rule at
 * this gate, locked by {@code validate.laws.TemporalConstructorDeclaredFormatGateLawsTest}). Keeping that
 * model-dependent gate at validation time lets mixed legal sources remain authorable without pretending every
 * declaration is valid everywhere.
 */
public sealed interface TemporalComponent permits FieldRef, NumberExpr, TemporalComponentLiteral, SemanticIndex {

    /** The expression rendered in the constructor's bare component position. */
    Calculation expression();
}

package io.github.mbackschat.a12.dm.rulekit.dsl;

/**
 * A typed value expression (PROJECT-DESIGN §5) — the operand kind that supplies values inside conditions
 * and renders into a computation's {@code operation}. The closed set keeps rendering exhaustive.
 *
 * <p><strong>The function-node pattern</strong> (PROJECT-DESIGN §8): every keyword construct gets its own
 * typed node (like {@link LengthOf}) carrying its catalog operator-id — type safety is the product,
 * so there is no generic {@code FunctionCall} node. Only <em>rendering</em> is generalized: the
 * renderer looks the keyword up from the catalog by id. Value nodes ({@link FieldValue}, the
 * number/string/enum literals) apply no operator — their {@code [...]}/quote syntax is intrinsic,
 * not a catalog keyword — so they carry no operator-id. The exception proving the rule is
 * {@link BoolLiteral}: {@code True}/{@code False} are <em>localized keywords</em>
 * ({@code Wahr}/{@code Falsch}), so it does carry a catalog id. Pattern-matching over the sealed
 * set is the dispatch mechanism either way.
 */
public sealed interface Calculation permits FieldValue, NumberLiteral, StringLiteral, EnumValueLiteral, BoolLiteral, DateLiteral, TimeLiteral, LengthOf, EnumCategoryAccess, CountAggregate, ListAggregate, CurrentRepetition, Today, Now, BaseYear, DateDifference, DateShift, SemanticIndexValue, SemanticIndexFieldValue, Arithmetic, Rounding, Concatenation, DateListAggregate, FirstFilledValue, FieldValueAsString, RangeAsString, FieldValueAsNumber, RangeAsNumber, OpListMath, NumberOfValueInFields, ValueAsDate, DateExtraction, DateTimePart, SumOfProducts, DateConstruction, TimeConstruction, DateTimeConstruction, FieldRounding, DateRangeBound, RootGroupCount, DateRangeConstruction {

    /** The value kind this expression yields (§5b) — the typed AST mirrors the lattice. */
    io.github.mbackschat.a12.dm.core.ValueKind kind();
}

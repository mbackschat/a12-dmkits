package io.github.mbackschat.a12.dm.bridge

import io.github.mbackschat.a12.dm.core.ModelIndex
import io.github.mbackschat.a12.dm.interpreter.ast.Cond
import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import io.github.mbackschat.a12.dm.rulekit.dsl.AggregateOperand
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Calculation
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldOperand
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupRef
import io.github.mbackschat.a12.dm.rulekit.dsl.IndexedFieldValue
import io.github.mbackschat.a12.dm.rulekit.dsl.IndexedGroupValue
import io.github.mbackschat.a12.dm.rulekit.dsl.SemanticIndex

/**
 * What every mapper needs from the rest of the bridge: the bound model, and the two recursions.
 *
 * It exists so a construct family can be mapped without reaching into the others. Each family owns one class
 * behind this interface — [Operands], [Predicates], [Functions], [Conditions] — and they compose only through
 * the calls declared here.
 */
internal interface Mapping {

    /** The model the condition is written against. */
    fun model(): BoundModel

    /** Structural questions the typed refs cannot answer, chiefly whether a path names a group. */
    fun index(): ModelIndex

    /** Map a nested condition. */
    fun condition(node: Cond): Condition

    /** Map a nested value expression. */
    fun calculation(node: Expr): Calculation

    /**
     * The path the condition is written against. A relative reference — a bare name, `..`, the `@From` scope —
     * resolves against it, so a mapper handling one needs it.
     */
    fun basePath(): String

    /** Resolve an operand that must name a field. */
    fun fieldRef(node: Expr): FieldRef

    /**
     * Resolve an operand in a field-list slot. Wider than [fieldRef]: a starred field is a `FieldListRef`,
     * which that return type cannot express.
     */
    fun fieldOperand(node: Expr): FieldOperand

    /** Resolve a semantic index as a presence handle, for the filled / not-filled predicates. */
    fun semanticIndex(node: Expr): SemanticIndex

    /** Resolve a semantic index as a value, for a comparison or arithmetic slot. */
    fun indexedValue(node: Expr): IndexedFieldValue

    /**
     * The group analog of [indexedValue] — a semantic index whose target is a GROUP, as an entity-list /
     * group-list-quantifier member (`FirstFilledValue` reaches this too, to build its own owner entity from
     * the same resolved group and key).
     */
    fun indexedGroup(node: Expr): IndexedGroupValue

    /**
     * Narrow an operand to the typed ref its kind admits — `StringRef`, `NumberRef`, `EnumRef`, and so on.
     *
     * Needed because rulekit puts much of its kernel knowledge on the typed refs rather than on `FieldRef`:
     * the value-list membership operators, the comparison families, an enum literal's domain check. Returns
     * [Any] because the refs share no supertype; a caller pattern-matches for the one its operator takes and
     * leaves the rest [UnmappedConstructException].
     */
    fun typedRef(node: Expr): Any

    /** Resolve an operand that must name a group. */
    fun groupRef(node: Expr): GroupRef

    /**
     * As [groupRef], but with the `$` correlation left to a caller whose own node can hold one — `GroupRef`
     * carries no marker, so every other slot must keep refusing rather than drop it (DEV-API-GAPS `DG49`).
     */
    fun groupRef(node: Expr, allowOuter: Boolean): GroupRef

    /**
     * Resolve a raw group-reference TOKEN — the form a scope keyword captures, rather than an operand the
     * parser has already resolved. Absolute, relative to [basePath], or the rule-group keyword.
     *
     * Here rather than in the mapper that needs it, so path resolution stays in one place: the interpreter's
     * own `resolveGroupRef` compares the raw token against the English `RuleGroup` alone, which a
     * German-authored `RegelKontext` misses, and copying that into a second implementation would put the same
     * defect in two places.
     */
    fun groupReference(rawReference: String): GroupRef

    /** Resolve an operand in an aggregate slot, which accepts a field, a repetition list or a group scope. */
    fun aggregateOperand(node: Expr): AggregateOperand

    /** True when [path] names a group rather than a field. */
    fun isGroup(path: String): Boolean = index().isGroup(path)
}

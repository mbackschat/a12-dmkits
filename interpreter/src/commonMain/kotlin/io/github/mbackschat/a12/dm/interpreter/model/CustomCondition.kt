package io.github.mbackschat.a12.dm.interpreter.model

/**
 * The interpreter's **cross-platform** twin of the kernel's `IData`-based `ICustomCondition` — the SPI a
 * consumer implements to supply a `CustomCondition <name>` the rule language can't express. Its shape mirrors
 * the kernel contract that exists IDENTICALLY on the JVM (core `…rt.a12internal.validation.ICustomCondition`)
 * AND in TypeScript (`kernel-core-runtime-ts`), so it compiles to JVM and JS and existing impls plug in:
 * a Kotlin/JS or TS consumer implements it directly; a JVM consumer adapts an existing kernel `ICustomCondition`
 * (core directly, or a modern `DocumentV2` one via a JVM-only view adapter). KERNEL-FINDINGS "CustomCondition".
 *
 * `check` returns true when the condition holds (the rule FIRES). It must not mutate the data and must be
 * thread-safe (the kernel contract). It is invoked only on an evaluated row whose relevant fields are formally
 * correct (the interpreter gates this), and must return false when the fields to validate are not relevant.
 */
fun interface CustomCondition {
    /**
     * @param data the row's data view (the kernel's `IData`)
     * @param relevant the relevant entities (fields/groups) for this validation, or null for a full validation
     *   (mirrors the kernel's `relevantEntities`); a partial validation passes the relevant subset
     * @param formallyIncorrect the fields whose value is formally invalid (the kernel's `formallyIncorrectFields`)
     * @param errorField the field this rule attaches to (the kernel's `errorField` identifier)
     */
    fun check(
        data: CustomConditionData,
        relevant: Set<FieldPointer>?,
        formallyIncorrect: Set<FieldPointer>,
        errorField: FieldPointer,
    ): Boolean
}

/**
 * The read-only data view a [CustomCondition] sees — the kernel's `IData` surface (cross-platform). A
 * consumer reads field values by [FieldPointer]; the interpreter resolves them against the current row.
 */
interface CustomConditionData {
    /** The value at [field] (path + 1-based repetition indices), or null if empty/absent — the kernel's
     *  `IData.getValue(IIdentifier)`. */
    fun getValue(field: FieldPointer): String?

    /** Every present (valued) field instance in the row's scope — the kernel's `IData.iterator()`. */
    fun fields(): List<FieldPointer>
}

/**
 * A field-instance pointer — the kernel's `IIdentifier`: a [path] (e.g. `/Order/Items/Count`) and its 1-based
 * repetition [indices], one per path part (a group and field both carry indices). Equality is structural so it
 * works as a set element (the kernel's relevant / formally-incorrect sets).
 */
data class FieldPointer(val path: String, val indices: List<Int>)

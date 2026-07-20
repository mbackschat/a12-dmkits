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
 * thread-safe (the kernel contract). Once ordinary condition evaluation reaches the leaf, the callback owns
 * its hidden dependencies: it must use [relevant] and [formallyIncorrect] to return false when a necessary
 * field was not checked or is formally invalid. The evaluator does not infer or pre-suppress that footprint.
 */
fun interface CustomCondition {
    /**
     * @param data the document-wide data view (the kernel's `IData`)
     * @param relevant the relevant entities (fields/groups) for this validation, or null for a full validation
     *   (mirrors the kernel's `relevantEntities`); a partial validation passes the exact externally addressed
     *   relevant subset
     * @param formallyIncorrect the exact externally addressed fields found formally invalid in this validation's
     *   formal pass; partial validation includes only formally checked relevant fields, independently of the
     *   [relevant] channel
     * @param errorField the exact field instance this rule attaches to (the kernel's `errorField` identifier)
     */
    fun check(
        data: CustomConditionData,
        relevant: Set<FieldPointer>?,
        formallyIncorrect: Set<FieldPointer>,
        errorField: FieldPointer,
    ): Boolean
}

/**
 * The read-only document-wide data view a [CustomCondition] sees — the kernel's `IData` surface
 * (cross-platform). A consumer reads field values by exact [FieldPointer]; the current rule row is carried
 * separately by the callback's `errorField`.
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

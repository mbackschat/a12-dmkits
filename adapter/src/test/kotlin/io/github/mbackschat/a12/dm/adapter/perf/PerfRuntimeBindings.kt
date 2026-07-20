package io.github.mbackschat.a12.dm.adapter.perf

import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldTypeFactory
import com.mgmtp.a12.kernel.md.document.apiV2.DocumentMultiPointer
import com.mgmtp.a12.kernel.md.document.apiV2.DocumentPointer
import com.mgmtp.a12.kernel.md.document.apiV2.PartiallyKnownDocumentMultiPointer
import com.mgmtp.a12.kernel.md.document.apiV2.immutable.DocumentV2
import com.mgmtp.a12.kernel.md.rt.api.ICustomCondition
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.model.CustomCondition
import io.github.mbackschat.a12.dm.interpreter.model.CustomFieldType
import io.github.mbackschat.a12.dm.interpreter.model.EvalClock
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import java.time.Instant
import java.util.Date

/**
 * One immutable source for every runtime value that can change an interpreter/kernel performance result.
 * Each registration carries both implementations, so a comparative scenario cannot accidentally configure
 * only one engine. Stable contract ids—not callback object identities—become part of the scenario fingerprint.
 */
class PerfRuntimeBindings private constructor(builder: Builder) {
    private val clock = builder.clock
    private val conditions = builder.conditions.toMap()
    private val fieldTypes = builder.fieldTypes

    internal val identity: Map<String, String> = buildMap {
        clock?.let {
            put("clock.today", it.today)
            put("clock.now", it.now ?: "<absent>")
            put("clock.instant", it.instant.toString())
        }
        conditions.toSortedMap().forEach { (name, binding) ->
            put("custom-condition:$name", binding.contractId)
        }
        fieldTypes?.let {
            put("custom-field-types", it.contractId)
            put("custom-field-type-names", it.interpreter.keys.sorted().joinToString(","))
        }
    }.toMap()

    internal fun interpreter(model: ModelDef): DmInterpreter = DmInterpreter(
        model,
        clock = clock?.let { EvalClock(it.today, it.now) },
        customConditions = conditions.mapValues { it.value.interpreter },
        customFieldTypes = fieldTypes?.interpreter ?: emptyMap(),
    )

    internal fun kernelConfig(): KernelPerfConfig = KernelPerfConfig(
        clock = clock?.instant,
        customConditions = conditions.mapValues { it.value.kernel },
        customFieldTypeFactory = fieldTypes?.kernel,
    )

    class Builder internal constructor() {
        internal var clock: ClockBinding? = null
        internal val conditions = linkedMapOf<String, ConditionBinding>()
        internal var fieldTypes: FieldTypeBinding? = null

        fun clock(today: String, now: String?, instant: Instant) = apply {
            require(today.isNotBlank()) { "clock today must not be blank" }
            clock = ClockBinding(today, now, instant)
        }

        fun customCondition(
            name: String,
            contractId: String,
            interpreter: CustomCondition,
            kernel: ICustomCondition,
        ) = apply {
            require(name.isNotBlank()) { "custom-condition name must not be blank" }
            require(contractId.isNotBlank()) { "$name: custom-condition contract id must not be blank" }
            require(conditions.put(name, ConditionBinding(contractId, interpreter, kernel)) == null) {
                "$name: custom condition is already bound"
            }
        }

        fun customFieldTypes(
            contractId: String,
            interpreter: Map<String, CustomFieldType>,
            kernel: ICustomFieldTypeFactory,
        ) = apply {
            require(contractId.isNotBlank()) { "custom-field-type contract id must not be blank" }
            require(interpreter.isNotEmpty()) { "at least one interpreter custom field type is required" }
            require(interpreter.keys.none(String::isBlank)) { "custom-field-type names must not be blank" }
            require(fieldTypes == null) { "custom field types are already bound" }
            fieldTypes = FieldTypeBinding(contractId, interpreter.toMap(), kernel)
        }

        fun build(): PerfRuntimeBindings = PerfRuntimeBindings(this)
    }

    internal data class ClockBinding(val today: String, val now: String?, val instant: Instant)

    internal data class ConditionBinding(
        val contractId: String,
        val interpreter: CustomCondition,
        val kernel: ICustomCondition,
    )

    internal data class FieldTypeBinding(
        val contractId: String,
        val interpreter: Map<String, CustomFieldType>,
        val kernel: ICustomFieldTypeFactory,
    )

    companion object {
        internal val DEFAULT: PerfRuntimeBindings = Builder().build()

        fun builder(): Builder = Builder()
    }
}

/** Kernel projection of [PerfRuntimeBindings]; owns no mutable Date or caller-owned map. */
internal class KernelPerfConfig(
    clock: Instant? = null,
    customConditions: Map<String, ICustomCondition> = emptyMap(),
    val customFieldTypeFactory: ICustomFieldTypeFactory? = null,
) {
    private val clockInstant = clock
    val customConditions: Map<String, ICustomCondition> = customConditions.toMap()

    fun freshClock(): Date? = clockInstant?.let(Date::from)
}

/** A deterministic callback for synthetic workloads; uses only the kernel's current DocumentV2 SPI. */
internal fun constantKernelCondition(result: Boolean): ICustomCondition = object : ICustomCondition {
    override fun check(
        document: DocumentV2,
        relevantEntities: Set<DocumentMultiPointer>?,
        formallyIncorrectEntities: Set<DocumentPointer>,
        errorEntityInstance: PartiallyKnownDocumentMultiPointer,
    ): Boolean = result
}

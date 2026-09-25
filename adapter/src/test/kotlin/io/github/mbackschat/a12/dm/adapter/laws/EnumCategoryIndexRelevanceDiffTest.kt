package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.conformance.kernelComputeGranularity
import io.github.mbackschat.a12.dm.interpreter.eval.formatPointer
import io.github.mbackschat.a12.dm.interpreter.eval.readDocumentJson
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — what an enum CATEGORY read `[Plan -> Tier]` does over an index cell that is well-formed but not
 * check-relevant, beside the value read `[Plan]` of the same cell.
 *
 * [EnumCategoryComputePoisonDiffTest] settled the malformed cell, where the value's own format already fails. The
 * cells here pass every per-value check and are unavailable for a reason only the whole document shows: a
 * duplicated index value, a missing index key, or a row above the group's capacity. So the category read's
 * gate is the variable, and the value read of the same cell holds everything else fixed.
 *
 * Each case is one canonical A12 Document JSON string that both kernel strategies and the interpreter read. Own
 * domain (insurance cover plans).
 */
class EnumCategoryIndexRelevanceDiffTest {

    private companion object {
        const val COVERS = "/Policy/Covers"
        const val RULE_CODE = "TIER"
        const val SEED = "7"

        fun policy(vararg covers: String) =
            """{"Policy":{"Holder":"H","Covers":[${covers.joinToString(",")}]}}"""

        fun cover(plan: String?, note: String) = buildString {
            append("""{"Note":"$note","Surcharge":$SEED""")
            if (plan != null) append(""","Plan":"$plan"""")
            append('}')
        }

        /** Two rows sharing one index value — each cell well-formed, both not check-relevant. */
        val DUPLICATED = policy(cover("PREMIUM", "a"), cover("PREMIUM", "b"))

        /** The same two rows keyed apart — the control that each carrier reads its cell at all. */
        val DISTINCT = policy(cover("PREMIUM", "a"), cover("BASIC", "b"))

        /** One row with no index value, which the preliminary index requiredness invalidates. */
        val KEYLESS = policy(cover(null, "a"))

        /** Three rows under a capacity of two: the third is above it and every cell in it is unavailable. */
        val OVER_CAPACITY = policy(cover("BASIC", "a"), cover("PLUS", "b"), cover("PREMIUM", "c"))
    }

    private val model = DocumentModelDraft.named("cover-plans").locale("en_US")
        .rootGroup("Policy") { policy ->
            policy.stringField("Holder") { }
                .group("Covers") { covers ->
                    covers.repeatable(2).indexField("Plan")
                        .enumField("Plan") {
                            it.value("BASIC").value("PLUS").value("PREMIUM").value("ELITE")
                                .category("Tier", "LOW", "LOW", "HIGH", "HIGH")
                        }
                        .stringField("Note") { }
                        .numberField("Surcharge") { it.fractionDigits(0, 0) }
                }
        }.build().toJson()
    private val bound = BoundModel.ofJson(model)
    private val plan = bound.enumOf("$COVERS/Plan")

    /** The category read under test. */
    private val categoryRead: Condition = plan.category("Tier").`is`("HIGH")

    /** The value read of the same cell — the carrier whose answer is already settled. */
    private val valueRead: Condition = plan.`is`("PREMIUM")

    private fun admitted(model: String): String {
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(model).filter { it.startsWith("ERROR") },
            "the kernel must admit the model, or an absent verdict is the gate's silence and not an evaluation")
        return model
    }

    /** Every row fills it, so it holds the rule's error field in the condition and decides nothing. */
    private val noted: Condition = bound.str("$COVERS/Note").filled()

    /** How many times the row rule `FieldFilled(Note) And [condition]` fires, agreed by three engines. */
    private fun firings(condition: Condition, documentJson: String): Int {
        val kModel = admitted(RuntimeLaws.withRule(model, COVERS, "TierRule", "../Note", RULE_CODE,
            RuntimeLaws.render(noted.and(condition))))
        val ofRule = { signatures: List<String> -> signatures.count { it.startsWith("$RULE_CODE|") } }
        val dynamic = RuntimeLaws.kernelEngine(kModel)
        val static = RuntimeLaws.kernelJavaEngine(kModel)
        val fromDynamic = ofRule(dynamic.validationSignaturesSorted(dynamic.instance(documentJson)))
        assertEquals(fromDynamic, ofRule(static.validationSignaturesSorted(static.instance(documentJson))),
            "static-Java vs dynamic-Groovy — $condition — $documentJson")
        val def = ModelLoader.load(kModel)
        val outcome = DmInterpreter(def).validate(def.readDocumentJson(documentJson))
        assertEquals(emptyList<Any>(), outcome.unsupported, "declined, not evaluated — $documentJson")
        assertEquals(fromDynamic, outcome.messages.count { it.code == RULE_CODE && it.type == MsgType.VALUE },
            "INTERPRETER vs dynamic-Groovy — $condition — $documentJson")
        return fromDynamic
    }

    /**
     * Each row's `Surcharge` from `5` when [guard] holds and `1` after it, agreed by three engines. The second
     * alternative is the discriminator: a guard that is merely not true falls through to `1`, while a poisoned
     * read aborts the instance and clears the seeded value. It holds on every row, because A12 lets only one
     * alternative of several go unguarded (`MVK_ERROR_EMPTY_PRECONDITION`).
     */
    private fun surcharges(guard: Condition, documentJson: String): List<String> {
        val kModel = admitted(RuntimeLaws.withTieredComputation(model, COVERS, "SurchargeComp", "../Surcharge", null,
            listOf(RuntimeLaws.CompAlt(RuntimeLaws.render(guard), "5"),
                RuntimeLaws.CompAlt(RuntimeLaws.render(noted), "1"))))
        val dynamic = RuntimeLaws.kernelEngine(kModel)
        val static = RuntimeLaws.kernelJavaEngine(kModel)
        val fromDynamic = dynamic.computationSignaturesSorted(dynamic.instance(documentJson))
        assertEquals(fromDynamic, static.computationSignaturesSorted(static.instance(documentJson)),
            "static-Java vs dynamic-Groovy — $guard — $documentJson")
        val def = ModelLoader.load(kModel)
        val doc = def.readDocumentJson(documentJson)
        val signatures = DmInterpreter(def).compute(doc).map {
            val pointer = formatPointer(it.path, it.indices)
            when (it.outcome) {
                ComputeOutcome.VALUE -> "$pointer|VALUE|${it.value}"
                ComputeOutcome.CLEARED -> "$pointer|CLEARED"
                ComputeOutcome.ERRORED -> "$pointer|ERRORED|${it.value ?: ""}"
            }
        }
        assertEquals(fromDynamic, kernelComputeGranularity(signatures, doc, def).sorted(),
            "INTERPRETER vs dynamic-Groovy — $guard — $documentJson")
        return fromDynamic.map { it.substringAfter('|') }
    }

    @Test
    fun aCategoryReadOfAnIndexCellTheDocumentMakesUnavailableIsSilentInValidation() {
        val readings = linkedMapOf(
            "duplicated" to DUPLICATED, "distinct" to DISTINCT, "over capacity" to OVER_CAPACITY,
        ).mapValues { (_, document) -> "${firings(categoryRead, document)}/${firings(valueRead, document)}" }
        assertEquals(linkedMapOf("duplicated" to "0/0", "distinct" to "1/1", "over capacity" to "0/0"), readings,
            "category/value firings")
    }

    @Test
    fun aCategoryReadOfAnIndexCellTheDocumentMakesUnavailablePoisonsTheComputation() {
        val readings = linkedMapOf(
            "duplicated" to DUPLICATED, "distinct" to DISTINCT, "keyless" to KEYLESS,
        ).mapValues { (_, document) -> "${surcharges(categoryRead, document)} ${surcharges(valueRead, document)}" }
        assertEquals(linkedMapOf(
            "duplicated" to "[CLEARED, CLEARED] [CLEARED, CLEARED]",
            "distinct" to "[VALUE|5, VALUE|1] [VALUE|5, VALUE|1]",
            "keyless" to "[CLEARED] [CLEARED]",
        ), readings, "category / value outcomes per row")
    }
}

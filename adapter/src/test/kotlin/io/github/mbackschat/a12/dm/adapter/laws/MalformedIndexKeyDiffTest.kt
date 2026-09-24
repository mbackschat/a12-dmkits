package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.conformance.kernelComputeGranularity
import io.github.mbackschat.a12.dm.interpreter.eval.formatPointer
import io.github.mbackschat.a12.dm.interpreter.eval.readDocumentJson
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Tri-engine differential — **a field-valued index key that is itself malformed.** `Packages For /Order/WantedNo`
 * reads its key from a NUMBER field, and a stored `"abc"` there is a formally invalid operand: validation treats
 * every read through it as not check-relevant, computation poisons it. The rows cover each consumer of a keyed
 * lookup — a keyed group in a group-list quantifier and in a presence leaf, a keyed field's presence and value, and
 * `FirstFilledValue` over a keyed group — each beside a control whose key is well-formed.
 *
 * Each case is one canonical A12 Document JSON string that both kernel strategies and the interpreter read. Own
 * domain (a parcel order).
 */
class MalformedIndexKeyDiffTest {

    private companion object {
        const val RULE_CODE = "KEY"

        /** Package 1 is filled throughout, package 2 holds its key alone; `WantedNo` is the key under test. */
        fun order(wantedNo: String) = """{"Order":{"Reference":"R","WantedNo":$wantedNo,"Flag":9,""" +
            """"Packages":[{"No":1,"Carrier":"DHL","Dims":{"Height":3}},{"No":2}]}}"""

        val MALFORMED = order("\"abc\"")
        val SELECTS = order("1")
        val MISSES = order("3")
    }

    private val model = DocumentModelDraft.named("parcel-keys").locale("en_US")
        .rootGroup("Order") { order ->
            order.stringField("Reference") { }
                .numberField("WantedNo") { it.fractionDigits(0, 0) }
                .numberField("Flag") { it.fractionDigits(0, 0) }
                .group("Packages") { packages ->
                    packages.repeatable(5).indexField("No")
                        .numberField("No") { it.fractionDigits(0, 0) }
                        .stringField("Carrier") { }
                        .group("Dims") { dims -> dims.numberField("Height") { it.fractionDigits(0, 0) } }
                }
        }.build().toJson()

    private fun admitted(model: String): String {
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(model).filter { it.startsWith("ERROR") },
            "the kernel must admit the model, or an absent verdict is the gate's silence and not an evaluation")
        return model
    }

    /** The polarity of every message the rule `FieldFilled(Reference) And [condition]` raises, agreed by three engines. */
    private fun fired(condition: String, documentJson: String): List<String> {
        val kModel = admitted(RuntimeLaws.withRule(model, "/Order", "KeyRule", "../Reference", RULE_CODE,
            "FieldFilled(/Order/Reference) And $condition"))
        val ofRule = { signatures: List<String> ->
            signatures.filter { it.startsWith("$RULE_CODE|") }.map { it.split('|')[1] }.sorted()
        }
        val dynamic = RuntimeLaws.kernelEngine(kModel)
        val static = RuntimeLaws.kernelJavaEngine(kModel)
        val fromDynamic = ofRule(dynamic.validationSignaturesSorted(dynamic.instance(documentJson)))
        assertEquals(fromDynamic, ofRule(static.validationSignaturesSorted(static.instance(documentJson))),
            "static-Java vs dynamic-Groovy — $condition — $documentJson")
        val def = ModelLoader.load(kModel)
        val outcome = DmInterpreter(def).validate(def.readDocumentJson(documentJson))
        assertEquals(emptyList<Any>(), outcome.unsupported, "declined, not evaluated — $condition")
        assertEquals(fromDynamic, outcome.messages.filter { it.code == RULE_CODE }
            .map { if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR" }.sorted(),
            "INTERPRETER vs dynamic-Groovy — $condition — $documentJson")
        return fromDynamic
    }

    /**
     * `Flag`'s outcome from two alternatives, `1` when [precondition] holds and `2` after it, agreed by three
     * engines. The second alternative is the discriminator: a precondition that is merely not true falls through to
     * `2`, while a poisoned read aborts the computation and leaves the seeded `9` cleared. It carries a guard every
     * fixture satisfies, because A12 lets only one alternative of several go unguarded (`MVK_ERROR_EMPTY_PRECONDITION`).
     */
    private fun computed(precondition: String, documentJson: String): List<String> = computedBy(listOf(
        RuntimeLaws.CompAlt(precondition, "1"), RuntimeLaws.CompAlt("FieldFilled(/Order/Reference)", "2")), documentJson)

    /** `Flag`'s outcome when it is assigned [operation] unguarded, agreed by three engines. */
    private fun assigned(operation: String, documentJson: String): List<String> =
        computedBy(listOf(RuntimeLaws.CompAlt(null, operation)), documentJson)

    private fun computedBy(alternatives: List<RuntimeLaws.CompAlt>, documentJson: String): List<String> {
        val kModel = admitted(RuntimeLaws.withTieredComputation(model, "/Order", "FlagComp", "../Flag", null,
            alternatives))
        val dynamic = RuntimeLaws.kernelEngine(kModel)
        val static = RuntimeLaws.kernelJavaEngine(kModel)
        val fromDynamic = dynamic.computationSignaturesSorted(dynamic.instance(documentJson))
        assertEquals(fromDynamic, static.computationSignaturesSorted(static.instance(documentJson)),
            "static-Java vs dynamic-Groovy — $alternatives — $documentJson")
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
            "INTERPRETER vs dynamic-Groovy — $alternatives — $documentJson")
        return fromDynamic
    }

    /** Validation: a malformed key makes every consumer not check-relevant, so the rule is silent on each. */
    @Test
    fun aMalformedKeyLeavesEveryValidationConsumerSilent() {
        val grid = mapOf(
            "NoGroupFilled(/Order/Packages For /Order/WantedNo)" to listOf(MISSES),
            "GroupNotFilled(/Order/Packages For /Order/WantedNo)" to listOf(MISSES),
            "NotAllGroupsFilled(/Order/Packages For /Order/WantedNo, /Order/Reference)" to listOf(MISSES),
            "AtLeastOneGroupFilled(/Order/Packages For /Order/WantedNo)" to listOf(SELECTS),
            "GroupFilled(/Order/Packages For /Order/WantedNo)" to listOf(SELECTS),
            "FieldNotFilled(/Order/Packages/Carrier For /Order/WantedNo)" to listOf(MISSES),
            "FieldFilled(/Order/Packages/Carrier For /Order/WantedNo)" to listOf(SELECTS),
            "[/Order/Packages/Dims/Height For /Order/WantedNo] == 3" to listOf(SELECTS),
            "FirstFilledValue(/Order/Packages/Dims For /Order/WantedNo) == 0" to listOf(MISSES),
        )
        for ((condition, firing) in grid) {
            assertEquals(emptyList<String>(), fired(condition, MALFORMED), condition)
            // the control: a well-formed key that makes the same rule fire, so the silence above is the key's
            for (document in firing) assertEquals(1, fired(condition, document).size, "$condition — $document")
        }
    }

    /** Computation: a malformed key POISONS every consumer, so neither alternative stores and the seed clears. */
    @Test
    fun aMalformedKeyPoisonsEveryComputationConsumer() {
        val consumers = listOf(
            "NoGroupFilled(/Order/Packages For /Order/WantedNo)",
            "GroupNotFilled(/Order/Packages For /Order/WantedNo)",
            "NotAllGroupsFilled(/Order/Packages For /Order/WantedNo, /Order/Reference)",
            "AtLeastOneGroupFilled(/Order/Packages For /Order/WantedNo)",
            "GroupFilled(/Order/Packages For /Order/WantedNo)",
            "FieldNotFilled(/Order/Packages/Carrier For /Order/WantedNo)",
            "FieldFilled(/Order/Packages/Carrier For /Order/WantedNo)",
            "[/Order/Packages/Dims/Height For /Order/WantedNo] == 3",
            "FirstFilledValue(/Order/Packages/Dims For /Order/WantedNo) == 3",
        )
        for (condition in consumers) {
            assertEquals(listOf("/Order[1]/Flag|CLEARED"), computed(condition, MALFORMED), condition)
            // the control: a well-formed key decides the precondition, one way or the other, and something stores
            for (document in listOf(SELECTS, MISSES)) {
                assertEquals(1, computed(condition, document).count { "|VALUE|" in it }, "$condition — $document")
            }
        }
        for (operation in listOf("[/Order/Packages/Dims/Height For /Order/WantedNo]",
                "FirstFilledValue(/Order/Packages/Dims For /Order/WantedNo)")) {
            assertEquals(listOf("/Order[1]/Flag|CLEARED"), assigned(operation, MALFORMED), operation)
            assertEquals(listOf("/Order[1]/Flag|VALUE|3"), assigned(operation, SELECTS), operation)
        }
    }
}

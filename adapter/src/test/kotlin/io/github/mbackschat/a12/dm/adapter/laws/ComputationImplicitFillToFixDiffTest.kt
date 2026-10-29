package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A computation's implicit message lists `fillToFix` by the ordinary rule arm's omission projection of its generated
 * condition (KERNEL-FINDINGS KF219, corrected 2026-10-29; a12-kernel-lean SPEC-2026-10-29-04), not all of `referenced`:
 * a field the condition reads only through positive presence is referenced and withheld. Tri-checked dynamic Groovy
 * == generated Java == interpreter on complete sorted sets.
 *
 * Each target computes `[Base] + [Loading]` beside an empty `Loading`, so every message types OMISSION and only its
 * precondition differs. The rival account, all of `referenced`, predicts the presence-read `Discount` and riders
 * listed; the ordinary `[Discount] > 0` precondition is the control that a precondition's field is listed when it is
 * read ordinarily, and there the two sets coincide. `NumberOfFilledFields(…) > 0` is the upward count pruning of
 * [KF522], which a12-kernel-lean left unmeasured on the generated condition. Own domain: an insurance policy whose
 * premium a discount gates and riders qualify.
 */
class ComputationImplicitFillToFixDiffTest {

    private val base: String = DocumentModelDraft.named("policy-premium").locale("en_US")
        .rootGroup("Policy") { p ->
            for (name in listOf("Discount", "Base", "Loading", "Rider1", "Rider2", GATED, QUANTIFIED, COUNTED, ORDINARY)) {
                p.numberField(name) { it.fractionDigits(0, 0) }
            }
        }.build().toJson()

    private val model: String = BoundModel.ofJson(base).let { m ->
        val discount = m.num("/Policy/Discount")
        val riders = Dsl.atLeastOneFieldFilled(m.field("/Policy/Rider1"), m.field("/Policy/Rider2"))
        val ridersCounted = Dsl.numberOfFilledFields(m.field("/Policy/Rider1"), m.field("/Policy/Rider2")).gt(0)
        val premium = m.num("/Policy/Base").plus(m.num("/Policy/Loading"))
        fun target(name: String) = m.computation(m.num("/Policy/$name")).name("${name}Comp").message(Dsl.en("Premium differs"))
        listOf(
            target(GATED).commonPrecondition(discount.filled()).calc(premium),
            target(QUANTIFIED).`when`(riders).calc(premium),
            target(COUNTED).`when`(ridersCounted).calc(premium),
            target(ORDINARY).`when`(discount.gt(0)).calc(premium),
        ).fold(base) { json, builder -> RuntimeLaws.withComputation(json, builder.build()) }
    }

    private val definition = ModelLoader.load(model)

    /** `code → (type, referenced, fillToFix)` for each of dynamic Groovy, generated Java and the interpreter. */
    private fun observed(): Map<String, List<Triple<String, List<String>, List<String>>>> {
        val cells = mapOf("Discount" to "10", "Base" to "500", "Rider1" to "1") +
            listOf(GATED, QUANTIFIED, COUNTED, ORDINARY).associateWith { "99" }
        val kernelDoc = RuntimeLaws.doc().group("/Policy", 1)
        val interpreterDoc = definition.newDocument().group("/Policy", 1)
        for ((name, value) in cells) {
            kernelDoc.field("/Policy/$name", value, 1, 1)
            interpreterDoc.field("/Policy/$name", value, 1, 1)
        }
        val engines = listOf(
            RuntimeLaws.messages(model, kernelDoc).map { Triple(it.code(), it.type().toString(), it.referenced() to it.omissionResponsible()) },
            RuntimeLaws.kernelJavaEngine(model).messagesModern(kernelDoc, null, java.util.Locale.US)
                .map { Triple(it.code(), it.type().toString(), it.referenced() to it.omissionResponsible()) },
            DmInterpreter(definition).validateFull(interpreterDoc)
                .map { Triple(it.code, "${it.type}_ERROR", it.referenced to it.fillToFix) },
        )
        return listOf(GATED, QUANTIFIED, COUNTED, ORDINARY).associate { name ->
            "${name}Comp" to engines.map { messages ->
                messages.single { it.first == "${name}Comp" }.let { (_, type, sets) -> Triple(type, sets.first.sorted(), sets.second.sorted()) }
            }
        }
    }

    @Test
    fun aPresenceOnlyPreconditionIsReferencedAndWithheldFromFillToFix() {
        fun row(target: String, vararg withheld: String): Triple<String, List<String>, List<String>> {
            val listed = listOf("Base", "Loading", target) + if (withheld.isEmpty()) listOf("Discount") else emptyList()
            return Triple("OMISSION_ERROR", (listed + withheld).map { "/Policy[1]/$it" }.sorted(), listed.map { "/Policy[1]/$it" }.sorted())
        }
        val predicted = mapOf(
            "${GATED}Comp" to row(GATED, "Discount"),
            "${QUANTIFIED}Comp" to row(QUANTIFIED, "Rider1", "Rider2"),
            "${COUNTED}Comp" to row(COUNTED, "Rider1", "Rider2"),
            "${ORDINARY}Comp" to row(ORDINARY),
        )
        assertEquals(predicted.mapValues { (_, set) -> List(3) { set } }, observed(),
            "(type, referenced, fillToFix) per message, as [dynamic Groovy, generated Java, interpreter]")
    }

    private companion object {
        const val GATED = "GatedPremium"
        const val QUANTIFIED = "QualifiedPremium"
        const val COUNTED = "CountedPremium"
        const val ORDINARY = "DiscountedPremium"
    }
}

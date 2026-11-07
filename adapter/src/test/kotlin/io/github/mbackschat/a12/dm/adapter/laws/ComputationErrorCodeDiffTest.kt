package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Which code the validation A12 generates from a computation carries: its `errorCode` when the member is set, the
 * computation's name otherwise (`ValidationModelConverter.createRuleFromComputation`, kernel 31.3.1, source-read).
 * Every row stores a premium that disagrees with its rate, so the generated validation fires; the absent row is the
 * control that keeps the name fallback observable. Both kernel strategies and the interpreter are compared.
 * Accept/reject of the member's values is `rulekit.validate.laws.ComputationErrorCodeLawsTest`.
 *
 * Own domain (an insurance premium quote).
 */
class ComputationErrorCodeDiffTest {

    private val quote = DocumentModelDraft.named("premium-quote").locale("en_US").locale("de_DE")
        .rootGroup("Quote") { quote -> quote
            .numberField("Rate") { it.fractionDigits(2) }
            .numberField("Premium") { it.fractionDigits(2) }
        }.build().toJson()

    @Test
    fun theGeneratedValidationCarriesTheErrorCodeAndFallsBackToTheName() {
        val rows = linkedMapOf(
            "absent — the control" to null,
            "a code" to "PREMIUM_MISMATCH",
            "a code with a blank inside" to "PREMIUM MISMATCH",
        )
        val observed = rows.mapValues { (_, errorCode) -> observe(errorCode) }
        val expected = linkedMapOf(
            "absent — the control" to tri("PremiumCalc"),
            "a code" to tri("PREMIUM_MISMATCH"),
            "a code with a blank inside" to tri("PREMIUM MISMATCH"),
        )
        assertEquals(expected, observed, "as [dynamic Groovy, generated Java, interpreter]")
    }

    private fun tri(code: String) = List(3) { listOf("$code|$PREMIUM_POINTER|VALUE_ERROR") }

    private fun observe(errorCode: String?): List<List<String>> {
        val model = ModelWriter.addComputation(quote, DraftComputation.builder().groupPath("/Quote").name("PremiumCalc")
            .computedFieldRelPath("../Premium").errorCode(errorCode)
            .alternatives(listOf(DraftComputation.Alternative(null, "[Rate]"))).build(),
            listOf(Dsl.en("The premium follows the rate."), Dsl.de("Die Prämie folgt dem Satz.")))
        val kDoc = RuntimeLaws.doc().group("/Quote", 1).field(RATE, "5.00", 1, 1).field(PREMIUM, "9.00", 1, 1)
        val dynamic = RuntimeLaws.messages(model, kDoc).map { "${it.code()}|${it.field()}|${it.type().name}" }
        val java = RuntimeLaws.kernelJavaEngine(model)
        val generated = java.messages(java.instance(kDoc)).map { "${it.code()}|${it.field()}|${it.type().name}" }
        val definition = ModelLoader.load(model)
        val iDoc = definition.newDocument().group("/Quote", 1).field(RATE, "5.00", 1, 1).field(PREMIUM, "9.00", 1, 1)
        val interpreted = DmInterpreter(definition).validateFull(iDoc).map {
            "${it.code}|${it.errorPath}|${if (it.type.name == "OMISSION") "OMISSION_ERROR" else "VALUE_ERROR"}"
        }
        return listOf(dynamic, generated, interpreted)
    }

    private companion object {
        const val RATE = "/Quote/Rate"
        const val PREMIUM = "/Quote/Premium"
        const val PREMIUM_POINTER = "/Quote[1]/Premium"
    }
}

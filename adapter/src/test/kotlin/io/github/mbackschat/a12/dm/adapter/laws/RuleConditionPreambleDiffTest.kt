package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionDirectives
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential (groovy-dynamic == java-static == interpreter) for a rule that opens with the scale-gate waiver
 * (KERNEL-FINDINGS KF75). The waiver lifts a static gate only, so evaluation stays exact: `5 == 5.00` fires and
 * `5 == 5.01` does not, and a waived `Having` filter keeps exactly the rows it reads as different. The carriers
 * are the spellings A12 admits — plain, padded, followed by a comment, in a `Having` filter, and the German keyword
 * in a German-condition model (a12-kernel-lean's SPEC-2026-10-14-11 report, re-measured here on an own-domain
 * policy model). The interpreter had no reading of the preamble and refused the model at its first rule.
 *
 * The waived comparisons are condition text: the typed surface refuses a cross-scale `==`/`!=` when it is built
 * and has no waiver to lift that refusal (DEV-API-GAPS DG140).
 */
class RuleConditionPreambleDiffTest {

    @Test
    fun aWaivedRuleEvaluatesExactlyOnEveryAdmittedSpelling() {
        val waiver = ConditionDirectives.allowDifferingDecimals(ConditionLanguage.EN, "")
        val rules = mapOf(
            "PLAIN" to "${waiver}FieldFilled(Rate) And [Units] == [Rate]",
            "PADDED" to "@ SuppressWarning ( ${ConditionDirectives.SCALE_GATE} ) FieldFilled(Rate) And [Units] == [Rate]",
            "NOTED" to "$waiver;;the rate is stored to two places\nFieldFilled(Rate) And [Units] == [Rate]",
            "FILTER" to "${waiver}FieldFilled(Units) And Sum(Lines*/Amount Having [Lines/Amount] != 1.5) > 2",
        )
        assertFires(model("en_US", rules), mapOf("5.00" to rules.keys, "5.01" to setOf("FILTER")))
    }

    @Test
    fun aGermanModelsWaiverEvaluatesExactly() {
        val rules = mapOf(
            "PLAIN" to ConditionDirectives.allowDifferingDecimals(
                ConditionLanguage.DE, "FeldAngegeben(Rate) Und [Units] == [Rate]"),
        )
        assertFires(model("de_DE", rules), mapOf("5.00" to setOf("PLAIN"), "5.01" to emptySet()))
    }

    private fun model(conditionLanguage: String, rules: Map<String, String>): String {
        val base = DocumentModelDraft.named("policy").locale("en_US")
            .rootGroup("Policy") { policy ->
                policy.numberField("Units") { n -> n.fractionDigits(0) }
                    .numberField("Rate") { n -> n.fractionDigits(2) }
                    .group("Lines") { lines -> lines.repeatable(5).numberField("Amount") { n -> n.fractionDigits(0) } }
            }
            .build().toJson()
        var model = EditSession.read(base).setConditionLanguage(conditionLanguage).toJson()
        rules.forEach { (code, condition) ->
            model = RuntimeLaws.withRule(model, "/Policy", "R_$code", "../Units", code, condition)
        }
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(model), "A12 admits every waived rule")
        return model
    }

    /**
     * Each document fills Units 5, the given Rate and Lines 1, 2, 2. An exact filter keeps all three (sum 5); one that
     * rounded 1.5 to the field's scale would drop both 2s and fall to 1, so FILTER fires only if evaluation is exact.
     */
    private fun assertFires(model: String, expected: Map<String, Set<String>>) {
        val def = ModelLoader.load(model)
        expected.forEach { (rate, codes) ->
            val kDoc = RuntimeLaws.doc().group("/Policy", 1).field("/Policy/Units", "5", 1, 1)
                .field("/Policy/Rate", rate, 1, 1)
            val iDoc = def.newDocument().group("/Policy", 1).field("/Policy/Units", "5", 1, 1)
                .field("/Policy/Rate", rate, 1, 1)
            listOf("1", "2", "2").forEachIndexed { i, amount ->
                kDoc.group("/Policy/Lines", 1, i + 1).field("/Policy/Lines/Amount", amount, 1, i + 1, 1)
                iDoc.group("/Policy/Lines", 1, i + 1).field("/Policy/Lines/Amount", amount, 1, i + 1, 1)
            }
            val groovy = RuntimeLaws.messages(model, kDoc).map { it.code() }.toSet()
            val engine = RuntimeLaws.kernelJavaEngine(model)
            val java = engine.messages(engine.instance(kDoc)).map { it.code() }.toSet()
            val interpreter = DmInterpreter(def).validateFull(iDoc).map { it.code }.toSet()
            assertEquals(codes, groovy, "dynamic Groovy at Rate $rate")
            assertEquals(groovy, java, "generated Java at Rate $rate")
            assertEquals(groovy, interpreter, "interpreter at Rate $rate")
        }
    }
}

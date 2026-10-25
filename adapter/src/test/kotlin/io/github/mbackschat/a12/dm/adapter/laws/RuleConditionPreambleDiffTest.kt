package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionDirectives
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
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
 * The plain, filter and German rows are typed rules carrying the waiver (`Rule.Builder.allowDifferingDecimals`,
 * API-FINDINGS AF192, DG140), checked to render the spellings they stand for; the padded and commented rows stay text,
 * because they are spellings A12 admits and the renderer never emits.
 */
class RuleConditionPreambleDiffTest {

    @Test
    fun aWaivedRuleEvaluatesExactlyOnEveryAdmittedSpelling() {
        val waiver = ConditionDirectives.allowDifferingDecimals(ConditionLanguage.EN, "")
        val plain = waived(rateEqualsUnits, ConditionLanguage.EN)
        val filter = waived(filteredSum, ConditionLanguage.EN)
        assertEquals("${waiver}FieldFilled(Rate) And [Units] == [Rate]", plain)
        assertEquals("${waiver}FieldFilled(Units) And Sum(Lines*/Amount Having [Lines/Amount] != 1.5) > 2", filter)
        val rules = mapOf(
            "PLAIN" to plain,
            "PADDED" to "@ SuppressWarning ( ${ConditionDirectives.SCALE_GATE} ) FieldFilled(Rate) And [Units] == [Rate]",
            "NOTED" to "$waiver;;the rate is stored to two places\nFieldFilled(Rate) And [Units] == [Rate]",
            "FILTER" to filter,
        )
        assertFires(model("en_US", rules), mapOf("5.00" to rules.keys, "5.01" to setOf("FILTER")))
    }

    @Test
    fun aGermanModelsWaiverEvaluatesExactly() {
        val plain = waived(rateEqualsUnits, ConditionLanguage.DE)
        assertEquals(ConditionDirectives.allowDifferingDecimals(ConditionLanguage.DE,
            "FeldAngegeben(Rate) Und [Units] == [Rate]"), plain)
        assertFires(model("de_DE", mapOf("PLAIN" to plain)), mapOf("5.00" to setOf("PLAIN"), "5.01" to emptySet()))
    }

    private val base = DocumentModelDraft.named("policy").locale("en_US")
        .rootGroup("Policy") { policy ->
            policy.numberField("Units") { n -> n.fractionDigits(0) }
                .numberField("Rate") { n -> n.fractionDigits(2) }
                .group("Lines") { lines -> lines.repeatable(5).numberField("Amount") { n -> n.fractionDigits(0) } }
        }
        .build().toJson()
    private val bound = BoundModel.ofJson(base)
    private val units = bound.num("/Policy/Units")
    private val rate = bound.num("/Policy/Rate")
    private val rateEqualsUnits = rate.filled().and(units.eq(rate.asNumber().allowingDifferingDecimals()))
    private val filteredSum = units.filled().and(Dsl.sum(bound.flatten("/Policy/Lines*/Amount")
        .having(bound.num("/Policy/Lines/Amount").asNumber().allowingDifferingDecimals().ne("1.5"))).gt(2))

    /** The typed rule's condition text, waiver included, as it would be written into a [language] model. */
    private fun waived(condition: Condition, language: ConditionLanguage): String =
        bound.rule(bound.group("/Policy")).name("Typed").errorField(units.field()).`when`(condition)
            .allowDifferingDecimals().code("TYPED").message(Dsl.en("Units match the rate.")).build().render(language)

    private fun model(conditionLanguage: String, rules: Map<String, String>): String {
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

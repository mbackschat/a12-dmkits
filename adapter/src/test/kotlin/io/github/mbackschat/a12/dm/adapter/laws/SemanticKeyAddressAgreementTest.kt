package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberLiteral
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SemanticKeyAddressAgreementTest {
    private enum class KeyIndex { None, Matching, OtherName, OtherType, Nested }

    private fun model(index: KeyIndex) = DocumentModelDraft.named("depot-key-address").locale("en_US")
        .rootGroup("Store") { store -> store.stringField("Reference") { }
            .group("Audit") { it.numberField("Reserve") { } }
            .group("Depots") { depot ->
                depot.repeatable(2).stringField("Wanted") { }.stringField("Alias") { }
                when (index) {
                    KeyIndex.None -> depot.stringField("Code") { }
                    KeyIndex.OtherType -> depot.numberField("Code") { }.indexField("Code")
                    KeyIndex.OtherName -> depot.stringField("Code") { }.indexField("Alias")
                    KeyIndex.Matching, KeyIndex.Nested -> depot.stringField("Code") { }.indexField("Code")
                }
                depot.group("Routes") { it.repeatable(2).indexField("Code")
                    .stringField("Code") { }.stringField("Wanted") { } }
            }
            .group("Catalog") { it.repeatable(3).indexField("Code")
                .stringField("Code") { }.numberField("Stock") { }.numberField("Reserve") { } }
        }.build().toJson()

    @Test
    fun fieldKeyAddressingKeepsParallelClassesAndRenamesOnlyUnaddressedIteration() {
        val wrong = mutableListOf<String>()
        for (index in KeyIndex.entries) {
            val json = model(index)
            val session = EditSession.read(json)
            val store = session.group("/Store")
            val depot = session.group("/Store/Depots")
            val catalog = session.group("/Store/Catalog")
            val wanted = if (index == KeyIndex.Nested) depot.str("Routes/Wanted") else depot.str("Wanted")
            val reserve = catalog.num("Reserve")
            val selected = catalog.num("Stock").forIndex(wanted.field()).gt(0)
            val expected = when (index) {
                KeyIndex.None -> "MVK_SEMANTIC_INDEX_INVALID_ITER"
                KeyIndex.Matching -> null
                KeyIndex.OtherName -> "MVK_ERROR_FIELD_INVALID_INDEX"
                KeyIndex.OtherType -> "MVK_ERROR_FIELD_INVALID_INDEX_TYPE"
                KeyIndex.Nested -> "MVK_MULTIPLE_INDEX_FIELDS"
            }
            val cases = mutableListOf(Triple("parallel", reserve.filled().and(selected), expected))
            if (index == KeyIndex.None) {
                cases += Triple("root-guard", Dsl.groupFilled(store.ref()).and(selected), expected)
                cases += Triple("fixed-key", reserve.filled().and(catalog.num("Stock").forIndex(store.field("Reference")).gt(0)), null)
                cases += Triple("ordinary-read", reserve.filled().and(wanted.filled()), "MVK_NO_WILDCARD")
            }
            for ((label, condition, code) in cases) {
                val rule = store.rule { it.name("Selected").code("Selected").errorField(reserve).`when`(condition)
                    .message(Dsl.en("Check selected stock.")).build() }
                val result = RuleValidator().check(json, rule.draft())
                val kernel = result.diagnostics().filter { !result.ok() }.map { it.code().orElse("uncoded") }.toSet()
                assertEquals(setOfNotNull(code), kernel, "$index $label kernel")
                val portable = check(ModelSource(TypedRules.inject(json, rule))).findings
                    .filter { it.justifiesRejection }.map { it.code }.toSet()
                if (kernel != portable) wrong += "$index $label: kernel=$kernel portable=$portable"
            }
            if (index == KeyIndex.None || index == KeyIndex.Matching) {
                val error = if (index == KeyIndex.None) wanted.field() else store.field("Audit/Reserve")
                val rule = store.rule { it.name("KeyTarget").code("KeyTarget").errorField(error)
                    .`when`(Dsl.fieldFilled(error).and(selected)).message(Dsl.en("Check selected stock.")).build() }
                val result = RuleValidator().check(json, rule.draft())
                val kernel = result.diagnostics().filter { !result.ok() }.map { it.code().orElse("uncoded") }.toSet()
                assertEquals(if (index == KeyIndex.None) emptySet() else setOf("MVK_ERROR_FIELD_NO_INDEX"), kernel, "$index target kernel")
                val portable = check(ModelSource(TypedRules.inject(json, rule))).findings
                    .filter { it.justifiesRejection }.map { it.code }.toSet()
                if (kernel != portable) wrong += "$index target: kernel=$kernel portable=$portable"
            }
            if (index == KeyIndex.None) {
                val calc = store.computation(reserve) { it.name("SelectedCalc").`when`(Dsl.groupFilled(store.ref()).and(selected))
                    .calc(NumberLiteral.of("1")).message(Dsl.en("Check stock calculation.")).build() }
                val computed = TypedRules.inject(json, calc)
                val result = RuleValidator().check(json, calc.draft())
                assertEquals(setOf("MVK_SEMANTIC_INDEX_INVALID_ITER"), result.diagnostics().filter { !result.ok() }.map { it.code().orElse("uncoded") }.toSet())
                val portable = check(ModelSource(computed)).findings.filter { it.justifiesRejection }.map { it.code }.toSet()
                if (portable != setOf("MVK_SEMANTIC_INDEX_INVALID_ITER")) wrong += "computation: portable=$portable"
            }
        }
        assertEquals(emptyList<String>(), wrong)
    }
}

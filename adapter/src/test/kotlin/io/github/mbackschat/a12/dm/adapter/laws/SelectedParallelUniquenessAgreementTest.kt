package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SelectedParallelUniquenessAgreementTest {
    @Test
    fun selectedLevelsDoNotCreateParallelIterationButOrdinaryTwinsDo() {
        val base = DocumentModelDraft.named("depot-unique-selection").locale("en_US")
            .rootGroup("Store") { store -> store
                .group("Depots") { it.repeatable(2).indexField("Code").stringField("Code") { }.stringField("Wanted") { } }
                .group("Catalog") { it.repeatable(3).indexField("Code").stringField("Code") { }.numberField("Stock") { } }
            }.build().toJson()
        val session = EditSession.read(base)
        val store = session.group("/Store")
        val wanted = store.field("Depots/Wanted")
        val stock = store.num("Catalog/Stock")
        val unique = Dsl.repetitionNotUnique(wanted)
        val wrong = mutableListOf<String>()
        for (fieldKey in listOf(false, true)) {
            val selected = (if (fieldKey) stock.forIndex(wanted) else stock.forIndex("b")).gt(0)
            val cases = listOf(
                Triple("selected-only", unique.and(selected), emptySet()),
                Triple("ordinary-first", unique.and(stock.gt(0)).and(selected), setOf("MVK_PARALLEL_ITERATION_NOT_ALLOWED")),
                Triple("ordinary-last", unique.and(selected).and(stock.gt(0)), setOf("MVK_PARALLEL_ITERATION_NOT_ALLOWED")),
            )
            for ((label, condition, expected) in cases) {
                val rule = store.rule { it.name("UniqueSelected").code("UniqueSelected").errorField(wanted)
                    .`when`(condition).message(Dsl.en("Check depot uniqueness.")).build() }
                val result = RuleValidator().check(base, rule.draft())
                val kernel = result.diagnostics().filter { !result.ok() }.map { it.code().orElse("uncoded") }.toSet()
                assertEquals(expected, kernel, "$fieldKey $label kernel")
                val portable = check(ModelSource(TypedRules.inject(base, rule))).findings
                    .filter { it.justifiesRejection }.map { it.code }.toSet()
                if (kernel != portable) wrong += "$fieldKey $label kernel=$kernel portable=$portable"
            }
        }
        assertEquals(emptyList<String>(), wrong)
    }
}

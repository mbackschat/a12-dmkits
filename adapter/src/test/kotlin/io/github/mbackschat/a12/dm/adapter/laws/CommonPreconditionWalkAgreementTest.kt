package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.core.EntityPath
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** SPEC-2026-10-02-07: common reads occur once, where the first judged alternative is reached. */
class CommonPreconditionWalkAgreementTest {
    private data class Row(val id: String, val model: String, val classes: List<String>)

    private fun fields(group: DocumentModelDraft.GroupBuilder) {
        group.numberField("Total") { it.fractionDigits(0) }
            .numberField("Base") { it.fractionDigits(0) }
            .numberField("Fee") { it.fractionDigits(0) }.stringField("Gate") { }
    }

    @Test
    fun commonReadsFollowTheFirstJudgedAlternativeAndProjectionsAnswerAlone() {
        val base = DocumentModelDraft.named("invoice-common-walk").locale("en_US")
            .rootGroup("Invoice") { root ->
                fields(root)
                root.group("Details") { fields(it) }
                    .group("Lines") { it.repeatable(3).indexField("Code").stringField("Code") { }
                        fields(it) }
            }.build().toJson()
        val rows = buildList {
            for (groupPath in listOf("/Invoice", "/Invoice/Details", "/Invoice/Lines")) {
                val group = EditSession.read(base).group(groupPath)
                val target = group.num("Total")
                val source = group.num("Base").asNumber()
                val fee = group.num("Fee").asNumber()
                val own = target.asNumber()
                val guard = group.str("Gate").filled()
                val common = target.filled()
                fun row(id: String, condition: Condition?, alternatives: List<Pair<Condition?, NumberExpr>>, vararg classes: String) {
                    // The typed builder deliberately rejects self-reads and multiple unguarded alternatives;
                    // the draft boundary carries those inputs, with all references rendered from typed refs.
                    val computation = DraftComputation.builder().groupPath(groupPath).name("CommonWalk")
                        .computedFieldRelPath(EntityPath.of("$groupPath/CommonWalk").relativize(target.field().path()))
                        .commonPrecondition(condition?.let { RuntimeLaws.render(groupPath, it) })
                        .alternatives(alternatives.map { (precondition, operation) -> DraftComputation.Alternative(
                            precondition?.let { RuntimeLaws.render(groupPath, it) }, RuntimeLaws.render(groupPath, operation.expression())) })
                        .errorCodesToSuppress(listOf("MVK_INVALID_COMPARE_DEC_PLACES")).build()
                    add(Row("$groupPath:$id", ModelWriter.addComputation(base, computation, listOf(Dsl.en("Calculate invoice."))), classes.toList()))
                }
                row("lone-unguarded", common, listOf(null to source), REF)
                row("lone-guarded", common, listOf(guard to source), REF)
                row("two-guarded", common, listOf(guard to source, guard to fee), REF)
                row("three-guarded", common, listOf(guard to source, guard to fee, guard to source), REF)
                row("unguarded-first", common, listOf(null to source, guard to fee), EMPTY, REF)
                row("guarded-first", common, listOf(guard to source, null to fee), REF, EMPTY)
                row("none-judged", common, listOf(null to source, null to fee), EMPTY)
                row("none-judged-self-operation", common, listOf(null to own, null to source), EMPTY)
                row("two-common-reads", own.plus(own).gt(0L), listOf(guard to source, guard to fee), REF, REF)
                row("common-does-not-guard", guard, listOf(null to source, null to fee), EMPTY)
                if (groupPath == "/Invoice/Lines") {
                    val projected = target.forIndex("a")
                    val other = group.num("Base").forIndex("a")
                    row("projection-lone", null, listOf(null to projected), PROJECTED)
                    row("projection-first", null, listOf(null to projected, null to source), PROJECTED)
                    row("projection-last", null, listOf(null to source, null to projected), PROJECTED)
                    row("projection-twice", null, listOf(null to projected.plus(target.forIndex("b"))), PROJECTED)
                    row("projection-common-unguarded", projected.gt(0L), listOf(null to source, null to fee), PROJECTED)
                    row("projection-common-guarded", projected.gt(0L), listOf(guard to source, guard to fee), PROJECTED)
                    row("other-projection", null, listOf(null to other))
                    row("other-projection-beside-plain-self", null, listOf(null to own, null to other), EMPTY)
                }
            }
        }
        assertEquals(38, rows.size)
        assertEquals(rows.size, rows.map { it.id }.toSet().size)
        val stored = HarvestedModels.expand(com.fasterxml.jackson.databind.ObjectMapper().readTree(
            checkNotNull(javaClass.getResource("/checker-near-miss/common-precondition-walk.json")).readText()))
        assertEquals(rows, stored.map { Row(it["id"].asText(), it["model"].asText(),
            it["classes"].map { code -> code.asText() }) }, "exact portable replay")
        assertEquals(rows.map { it.id to it.classes }, rows.map { it.id to MessageParameterProbes.kernel(it.model) }, "kernel classes")
        assertEquals(rows.map { it.id to it.classes }, rows.map { it.id to MessageParameterProbes.portable(it.model) }, "portable classes")
    }

    private companion object {
        const val REF = "MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD"
        const val EMPTY = "MVK_ERROR_EMPTY_PRECONDITION"
        const val PROJECTED = "MVK_ERROR_SEMANTIC_INDEX_OR_CATEGORY_FOR_ERRORFIELD"
    }
}

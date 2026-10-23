package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The iteration-guard and binding rows of a12-kernel-lean's K04 checkpoints, re-measured here: A12 answers each as
 * the peer's capture observed it, and the checker names the same classes (a12-kernel-lean SPEC-2026-10-22-05,
 * SPEC-2026-10-23-01; KERNEL-FINDINGS KF601). The rows live in `spec-sync/iteration-k04.tsv`: the peer's exactly as
 * it wrote them, then our own discriminators (`x`, `y`, `z`) for the two shapes the checker used to leave to A12, a
 * field-keyed selection and a group error target. The models are rebuilt here to the shapes the peer's checkpoint
 * describes.
 *
 * Own domain, shared with the peer's fixture: [OrderK04Model], and a staffing rota of unkeyed weeks holding keyed
 * demand and supply.
 */
class CheckerIterationK04AgreementTest {

    private class Row(val id: String, val model: String, val group: String, val field: String, val condition: String,
        val a12: List<String>)

    private val rows: List<Row> = javaClass.getResource("/spec-sync/iteration-k04.tsv")!!.readText().lines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { line ->
            val cells = line.split('\t')
            Row(cells[0], cells[1], cells[2], cells[3], cells[4].replace("\\n", "\n"),
                cells.getOrNull(5).orEmpty().split(',').filter { it.isNotEmpty() }.sorted())
        }

    private val rota = DocumentModelDraft.named("rota-k04").locale("en_US")
        .rootGroup("Plan") { p -> p
            .group("Weeks") { w -> w.repeatable(4)
                .group("Demand") { d -> d.repeatable(6).indexField("Shift")
                    .stringField("Shift") { }
                    .numberField("Headcount") { n -> n.fractionDigits(0) }
                    .stringField("Note") { } }
                .group("Supply") { s -> s.repeatable(6).indexField("Shift")
                    .stringField("Shift") { }
                    .numberField("Headcount") { n -> n.fractionDigits(0) }
                    .stringField("Note") { } } } }
        .build().toJson()

    private val models = mapOf("order" to OrderK04Model.json(withBows = false), "order-bows" to OrderK04Model.json(withBows = true),
        "rota" to rota)

    private val validator = RuleValidator()

    private fun judge(row: Row): Pair<List<String>, List<String>> {
        val model = models.getValue(row.model)
        val draft = DraftRule(row.group, "K04Probe", "../" + row.field.removePrefix(row.group + "/"), row.condition,
            "PROBE", "ERROR")
        val a12 = validator.check(model, draft).let { r ->
            if (r.ok()) emptyList() else r.diagnostics().map { it.code().orElse("(no code)") }
        }
        val ours = check(ModelSource(validator.inject(model, draft))).findings
            .filter { it.justifiesRejection }.map { it.code }
        return a12.sorted() to ours.sorted()
    }

    @Test
    fun a12AnswersEveryRowAsPinned() {
        assertEquals(rows.associate { it.id to it.a12 }, rows.associate { it.id to judge(it).first })
    }

    /**
     * Row `y1` is refused at the group target's own level, not at the line `GroupFilled(Lines)` guards: A12's
     * message names `/Order/Lines/Parts`, and so does the checker's.
     */
    @Test
    fun aGroupTargetIsRefusedAtItsOwnLevel() {
        val row = rows.single { it.id == "y1" }
        val draft = DraftRule(row.group, "K04Probe", "../" + row.field.removePrefix(row.group + "/"), row.condition,
            "PROBE", "ERROR")
        val model = models.getValue(row.model)
        val a12 = validator.check(model, draft).diagnostics().single().summary()
        val ours = check(ModelSource(validator.inject(model, draft))).findings.single { it.justifiesRejection }.explain
        assertTrue("Iteration for '/Order/Lines/Parts'" in a12, a12)
        assertTrue("`/Order/Lines/Parts`" in ours, ours)
    }

    @Test
    fun theCheckerNamesA12sClassOnEveryRow() {
        val verdicts = rows.associate { it.id to judge(it) }
        assertEquals(verdicts.mapValues { it.value.first }, verdicts.mapValues { it.value.second })
    }
}

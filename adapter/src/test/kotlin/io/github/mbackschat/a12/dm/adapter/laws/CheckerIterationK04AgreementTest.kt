package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The iteration-guard and binding rows of a12-kernel-lean's K04 checkpoints, re-measured here: A12 answers each as
 * the peer's capture observed it, and the checker names the same classes (a12-kernel-lean SPEC-2026-10-22-05,
 * SPEC-2026-10-23-01, and the static halves of SPEC-2026-10-23-02 and SPEC-2026-10-23-03; KERNEL-FINDINGS KF601). The
 * rows live in `spec-sync/iteration-k04.tsv`: the peer's exactly as it wrote them, then our own discriminators (`x`,
 * `y`, `z`, `f`, `w`) for the two shapes the checker used to leave to A12, a field-keyed selection and a group error
 * target, and for a group reference beside a parallel iteration. The models are rebuilt here to the shapes the peer's checkpoint
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

    /** A12's rejection classes, ours, and whether the checker left the guard to A12 with a gap at the probe. */
    private class Verdict(val a12: List<String>, val ours: List<String>, val declined: Boolean)

    private fun judge(row: Row): Verdict {
        val model = models.getValue(row.model)
        val draft = DraftRule(row.group, PROBE, "../" + row.field.removePrefix(row.group + "/"), row.condition,
            "PROBE", "ERROR")
        return judge(validator.check(model, draft), validator.inject(model, draft))
    }

    private fun judge(result: ValidationResult, injected: String): Verdict {
        val a12 = if (result.ok()) emptyList() else result.diagnostics().map { it.code().orElse("(no code)") }
        val report = check(ModelSource(injected))
        val ours = report.findings.filter { it.justifiesRejection }.map { it.code }
        val declined = report.gaps.any { it.at?.endsWith("/$PROBE") == true && NEGATIVE in it.reason }
        return Verdict(a12.sorted(), ours.sorted(), declined)
    }

    @Test
    fun a12AnswersEveryRowAsPinned() {
        assertEquals(rows.associate { it.id to it.a12 }, rows.associate { it.id to judge(it).a12 })
    }

    /**
     * Row `y1` is refused at the group target's own level, not at the line `GroupFilled(Lines)` guards: A12's
     * message names `/Order/Lines/Parts`, and so does the checker's.
     */
    @Test
    fun aGroupTargetIsRefusedAtItsOwnLevel() {
        val row = rows.single { it.id == "y1" }
        val draft = DraftRule(row.group, PROBE, "../" + row.field.removePrefix(row.group + "/"), row.condition,
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
        assertEquals(verdicts.mapValues { it.value.a12 }, verdicts.mapValues { it.value.ours })
    }

    /**
     * The positive control of the agreement above: an admitted row passes there whether the checker judged it or
     * declined, so the rows it leaves to A12 are pinned. A guard that re-declines a judged shape fails here, not
     * only on the refusal rows.
     */
    @Test
    fun theCheckerDeclinesOnlyThePinnedRows() {
        assertEquals(DECLINED, rows.filter { judge(it).declined }.map { it.id }.toSet())
    }

    /**
     * The computation rows: `k08` into the level below the join and its direct control `k00` (a12-kernel-lean
     * SPEC-2026-10-23-03), and a field-keyed selection in a precondition, judged through the condition A12 joins
     * a computation into (`kf1`, `kf2`).
     */
    @Test
    fun theCheckerNamesA12sClassOnEveryComputation() {
        val verdicts = COMPUTATIONS.mapValues { (_, computation) ->
            val (target, precondition) = computation
            val draft = DraftComputation("/Order", PROBE, "../$target", precondition,
                listOf(DraftComputation.Alternative(null, "1")))
            val model = models.getValue("order")
            judge(validator.check(model, draft), validator.inject(model, draft))
        }
        assertEquals(COMPUTATIONS.mapValues { emptyList<String>() }, verdicts.mapValues { it.value.a12 }, "A12")
        assertEquals(verdicts.mapValues { it.value.a12 }, verdicts.mapValues { it.value.ours }, "checker")
        assertEquals(emptySet<String>(), verdicts.filterValues { it.declined }.keys, "declined")
    }

    private companion object {
        const val PROBE = "K04Probe"
        const val NEGATIVE = "MVK_NEG_CONDITION_IN_ITERATION"

        /**
         * The rows whose guard the checker leaves to A12 (CONSISTENCY-GAPS DG146). All but one are refused by a
         * class A12 decides before the guard, the error field's reference or binding or a wildcard's, so the
         * guard has no verdict of its own to give there; `t8` is admitted, a group target iterated in parallel,
         * which no refusal row measures.
         */
        val DECLINED = setOf("o01", "o02", "o03", "o04", "a09", "d1", "d2", "d3", "y12", "t3", "t5", "t8", "f2b",
            "f2d", "f2d-f", "f3", "f3-f", "f5f")

        /** Name → (computed field below `/Order`, precondition). */
        val COMPUTATIONS = mapOf(
            "k08" to ("Lines/Parts/Count" to "NoFieldFilled(Lines/Parts/PartNo, Picks/Ref)"),
            "k00" to ("Lines/Price" to "NoFieldFilled(Lines/Code, Picks/Ref)"),
            "kf1" to ("Lines/Qty" to "FieldNotFilled(Picks/Ref For Lines/LineNo)"),
            "kf2" to ("Lines/Qty" to "FieldFilled(Picks/Ref For Lines/LineNo)"),
        )
    }
}

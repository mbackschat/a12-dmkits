package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The value-independent support report is the same preparation boundary execution consumes. These cases are
 * deliberately structural: no document value can make the declined shape safe, so sampling a convenient row is
 * not an eligibility proof.
 */
class PreparedPlanSupportTest {

    @Test
    fun reportsEveryRepetitionNotUniqueStructuralDeclineBeforeEvaluation() {
        val model = ModelDef(
            fields = listOf(
                FieldDef("/Order/Items/Key", Kind.STRING),
                FieldDef("/Order/Items/Other", Kind.STRING),
                FieldDef("/Order/Items/Details/Value", Kind.NUMBER),
                FieldDef("/Order/OtherRows/Key", Kind.STRING),
            ),
            rules = listOf(
                rule(
                    "RepetitionNotUnique(Items/Key) And RepetitionNotUnique(Items/Other)",
                    "../Items/Key",
                ),
                rule(
                    "RepetitionNotUnique(Items*)",
                    "../Items/Key",
                    code = "GROUP_KEY",
                ),
                rule(
                    "RepetitionNotUnique(Items/Key) And [Items/Details/Value] > 0",
                    "../Items/Key",
                    code = "DEEP_SCOPE",
                ),
                rule(
                    "RepetitionNotUnique(Items/Key, OtherRows/Key)",
                    "../Items/Key",
                    code = "PARALLEL_KEYS",
                ),
            ),
            repeatableGroups = setOf("/Order/Items", "/Order/Items/Details", "/Order/OtherRows"),
            declaredReps = mapOf(
                "/Order/Items" to 10,
                "/Order/Items/Details" to 10,
                "/Order/OtherRows" to 10,
            ),
        )

        val reasons = DmInterpreter(model).supportAudit().map { it.reason }

        assertTrue(reasons.any { "more than one RepetitionNotUnique" in it }, "$reasons")
        assertTrue(reasons.any { "plain field" in it }, "$reasons")
        assertTrue(reasons.any { "deeper than its key group" in it }, "$reasons")
        assertTrue(reasons.any { "key groups must lie on one hierarchy path" in it }, "$reasons")
    }

    @Test
    fun reportsParallelJoinStructureBeforeEvaluation() {
        val noIndex = parallelModel(
            condition = "[A/Value] > [B/Value]",
            indexFields = emptySet(),
        )
        val mismatchedIndex = parallelModel(
            condition = "[A/Value] > [B/Value]",
            indexFields = setOf("/Plan/A/AKey", "/Plan/B/BKey"),
            extraFields = listOf(
                FieldDef("/Plan/A/AKey", Kind.STRING),
                FieldDef("/Plan/B/BKey", Kind.STRING),
            ),
        )

        val noIndexReasons = DmInterpreter(noIndex).supportAudit().map { it.reason }
        val mismatchedReasons = DmInterpreter(mismatchedIndex).supportAudit().map { it.reason }

        assertTrue(noIndexReasons.any { "has no index field" in it }, "$noIndexReasons")
        assertTrue(mismatchedReasons.any { "share one index field name" in it }, "$mismatchedReasons")
    }

    @Test
    fun reportsParallelDocumentViewsThatExecutionCannotProvide() {
        val indexed = setOf("/Plan/A/Key", "/Plan/B/Key")
        val aggregate = parallelModel(
            condition = "[A/Value] > [B/Value] And AtLeastOneFieldFilled(A*/Value)",
            indexFields = indexed,
        )
        val nonJoinedGroupFill = parallelModel(
            condition = "[A/Value] > [B/Value] And GroupFilled(Other)",
            indexFields = indexed,
            extraFields = listOf(FieldDef("/Plan/Other/Flag", Kind.STRING)),
        )

        val aggregateReasons = DmInterpreter(aggregate).supportAudit().map { it.reason }
        val groupFillReasons = DmInterpreter(nonJoinedGroupFill).supportAudit().map { it.reason }

        assertTrue(aggregateReasons.any { "aggregate/filter" in it && "parallel iteration" in it }, "$aggregateReasons")
        assertTrue(groupFillReasons.any { "non-parallel group" in it }, "$groupFillReasons")
    }

    /**
     * **A STARRED group operand in a parallel iteration is caught by the star check, not by the group-read scan
     * — so the scan's blindness to it is unreachable.**
     *
     * `parallelGroupReads` collects group-operand paths with `(it as? Expr.Field)?.path`, which drops an
     * `Expr.Star`. The kernel-legal `NoGroupFilled(G*)` parses to exactly that, so the scan cannot see it, and a
     * refusal gate that cannot see a shape would evaluate it instead of declining — the wrong direction.
     *
     * Measured rather than reasoned, because the claim that the gap is unreachable is a claim about the ORDER of
     * two checks in one function: `requireParallelDocumentSupport` runs `starRefs(ast).isNotEmpty()` first, and
     * `starRefs` collects every `Expr.Star` including a starred group operand. Reading says the star check wins;
     * this row is what makes that a measurement. **The gap is therefore live in the code and dead in practice,
     * and the right treatment is to leave it alone** — an unreachable branch with a test costs more than the gap.
     */
    @Test
    fun aStarredGroupOperandInAParallelIterationDeclinesAtTheStarCheck() {
        val starredGroupFill = parallelModel(
            condition = "[A/Value] > [B/Value] And NoGroupFilled(Other*)",
            indexFields = setOf("/Plan/A/Key", "/Plan/B/Key"),
            extraFields = listOf(FieldDef("/Plan/Other/Flag", Kind.STRING)),
        )

        val reasons = DmInterpreter(starredGroupFill).supportAudit().map { it.reason }

        assertTrue(reasons.isNotEmpty(), "the shape must decline somewhere rather than evaluate")
        assertTrue(reasons.any { "aggregate/filter" in it && "parallel iteration" in it },
            "the STAR check is what declines it — if this ever reports the non-parallel-group reason instead, " +
                "the star check stopped seeing group operands and the scan's `as? Expr.Field` blindness became " +
                "reachable: $reasons")
    }

    private fun parallelModel(
        condition: String,
        indexFields: Set<String>,
        extraFields: List<FieldDef> = emptyList(),
    ) = ModelDef(
        fields = listOf(
            FieldDef("/Plan/A/Key", Kind.STRING),
            FieldDef("/Plan/A/Value", Kind.NUMBER),
            FieldDef("/Plan/B/Key", Kind.STRING),
            FieldDef("/Plan/B/Value", Kind.NUMBER),
        ) + extraFields,
        rules = listOf(RuleDef("/Plan/PAR", "/Plan", condition, "PAR", "../A/Value")),
        repeatableGroups = setOf("/Plan/A", "/Plan/B"),
        declaredReps = mapOf("/Plan/A" to 10, "/Plan/B" to 10),
        indexFields = indexFields,
    )

    private fun rule(condition: String, errorPath: String, code: String = "RNU") =
        RuleDef("/Order/$code", "/Order", condition, code, errorPath)
}

package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals
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
        // A star below a joined group reads that group's joined row (KERNEL-FINDINGS KF573); one that also binds an
        // unstarred repeatable level below it has no joined row to read, and A12 refuses it as MVK_NO_WILDCARD.
        val aggregate = parallelModel(
            condition = "[A/Value] > [B/Value] And AtLeastOneFieldFilled(A/Lines/Picks*/Units)",
            indexFields = indexed,
            extraFields = listOf(FieldDef("/Plan/A/Lines/Picks/Units", Kind.NUMBER)),
            extraRepeatable = setOf("/Plan/A/Lines", "/Plan/A/Lines/Picks"),
        )
        val joinedChild = parallelModel(
            condition = "[A/Value] > [B/Value] And AtLeastOneFieldFilled(A/Lines*/Units)",
            indexFields = indexed,
            extraFields = listOf(FieldDef("/Plan/A/Lines/Units", Kind.NUMBER)),
            extraRepeatable = setOf("/Plan/A/Lines"),
        )
        val nonJoinedGroupFill = parallelModel(
            condition = "[A/Value] > [B/Value] And GroupFilled(Other)",
            indexFields = indexed,
            extraFields = listOf(FieldDef("/Plan/Other/Flag", Kind.STRING)),
        )

        val aggregateReasons = DmInterpreter(aggregate).supportAudit().map { it.reason }
        val groupFillReasons = DmInterpreter(nonJoinedGroupFill).supportAudit().map { it.reason }

        assertTrue(aggregateReasons.any { "bound below the parallel iteration's common parent" in it }, "$aggregateReasons")
        assertEquals(emptyList(), DmInterpreter(joinedChild).supportAudit().map { it.reason })
        assertTrue(groupFillReasons.any { "non-parallel group" in it }, "$groupFillReasons")
    }

    /**
     * **A starred group operand of a group outside the join evaluates from the common parent, so the group-read
     * scan's blindness to it is correct rather than a gap.**
     *
     * `parallelGroupReads` collects group-operand paths with `(it as? Expr.Field)?.path`, which drops an
     * `Expr.Star`, so `NoGroupFilled(Other*)` passes it. That is the measured reading: a star iterating at the
     * common parent ranges over that parent's rows whichever rows the join paired (KERNEL-FINDINGS KF567, where
     * `NoGroupFilled(Prices*)` beside a join is pinned on the Kernel by `adapter.laws.JoinedRowAggregateDiffTest`).
     * Before KF567 a blanket star decline answered first and this row asserted it.
     */
    @Test
    fun aStarredGroupOperandBesideAJoinEvaluatesFromTheCommonParent() {
        val starredGroupFill = parallelModel(
            condition = "[A/Value] > [B/Value] And NoGroupFilled(Other*)",
            indexFields = setOf("/Plan/A/Key", "/Plan/B/Key"),
            extraFields = listOf(FieldDef("/Plan/Other/Flag", Kind.STRING)),
            extraRepeatable = setOf("/Plan/Other"),
        )

        assertEquals(emptyList(), DmInterpreter(starredGroupFill).supportAudit().map { it.reason })
    }

    private fun parallelModel(
        condition: String,
        indexFields: Set<String>,
        extraFields: List<FieldDef> = emptyList(),
        extraRepeatable: Set<String> = emptySet(),
    ) = ModelDef(
        fields = listOf(
            FieldDef("/Plan/A/Key", Kind.STRING),
            FieldDef("/Plan/A/Value", Kind.NUMBER),
            FieldDef("/Plan/B/Key", Kind.STRING),
            FieldDef("/Plan/B/Value", Kind.NUMBER),
        ) + extraFields,
        rules = listOf(RuleDef("/Plan/PAR", "/Plan", condition, "PAR", "../A/Value")),
        repeatableGroups = setOf("/Plan/A", "/Plan/B") + extraRepeatable,
        declaredReps = mapOf("/Plan/A" to 10, "/Plan/B" to 10) + extraRepeatable.associateWith { 10 },
        indexFields = indexFields,
    )

    private fun rule(condition: String, errorPath: String, code: String = "RNU") =
        RuleDef("/Order/$code", "/Order", condition, code, errorPath)
}

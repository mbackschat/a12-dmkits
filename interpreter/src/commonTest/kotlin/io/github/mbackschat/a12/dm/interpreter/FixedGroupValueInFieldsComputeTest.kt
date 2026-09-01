package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform (JVM + JS/Node) lock for a12-kernel-lean **SPEC-2026-08-24-05** — `NumberOfValueInFields` over an
 * UNSTARRED, non-repeatable group operand expands that group's direct declarations, for both Boolean constants.
 *
 * The kernel strategies are the oracle in `adapter.laws.FixedGroupValueInFieldsComputeDiffTest`; this file exists
 * because the fix lives in `commonMain` and the acceptance condition asks for the same outcomes on both
 * platforms. `True` counts a Boolean+Confirm group, `False` a two-Boolean group (the kernel's constant-specific
 * kind gate admits `Confirm` only under `True`); a malformed token in either group clears the seeded target.
 */
class FixedGroupValueInFieldsComputeTest {

    private val model = ModelDef(
        fields = listOf(
            FieldDef(APPROVAL_COUNT, Kind.NUMBER, scale = 0),
            FieldDef(DECLINED_COUNT, Kind.NUMBER, scale = 0),
            FieldDef(EXPEDITED, Kind.BOOLEAN),
            FieldDef(TERMS, Kind.CONFIRM),
            FieldDef(GIFT_WRAP, Kind.BOOLEAN),
            FieldDef(INSURANCE, Kind.BOOLEAN),
        ),
        rules = emptyList(),
        computations = listOf(
            ComputationDef(APPROVAL_COUNT, ROOT, "NumberOfValueInFields(True In Approvals)"),
            ComputationDef(DECLINED_COUNT, ROOT, "NumberOfValueInFields(False In Declines)"),
        ),
    )

    private fun outcomes(
        expedited: String?, terms: String?, giftWrap: String?, insurance: String?,
    ): List<String> {
        val doc = model.newDocument().group(ROOT, 1)
            .field(APPROVAL_COUNT, "9", 1, 1).field(DECLINED_COUNT, "9", 1, 1)
            .group(APPROVALS, 1, 1).group(DECLINES, 1, 1)
        expedited?.let { doc.field(EXPEDITED, it, 1, 1, 1) }
        terms?.let { doc.field(TERMS, it, 1, 1, 1) }
        giftWrap?.let { doc.field(GIFT_WRAP, it, 1, 1, 1) }
        insurance?.let { doc.field(INSURANCE, it, 1, 1, 1) }
        return DmInterpreter(model).compute(doc).map {
            when (it.outcome) {
                ComputeOutcome.VALUE -> "${it.path}|VALUE|${it.value}"
                ComputeOutcome.CLEARED -> "${it.path}|CLEARED"
                ComputeOutcome.ERRORED -> "${it.path}|ERRORED"
            }
        }.sorted()
    }

    @Test fun bothDirectDeclarationsAreCounted() = assertEquals(
        listOf("$APPROVAL_COUNT|VALUE|2", "$DECLINED_COUNT|VALUE|2"),
        outcomes("true", "true", "false", "false"),
    )

    @Test fun oneMatchPerGroupCountsOne() = assertEquals(
        listOf("$APPROVAL_COUNT|VALUE|1", "$DECLINED_COUNT|VALUE|1"),
        outcomes("true", null, "false", null),
    )

    @Test fun emptyCellsCountZeroRatherThanClearing() = assertEquals(
        listOf("$APPROVAL_COUNT|VALUE|0", "$DECLINED_COUNT|VALUE|0"),
        outcomes(null, null, null, null),
    )

    @Test fun aMalformedTokenAfterAMatchClearsTheSeededTarget() = assertEquals(
        listOf("$APPROVAL_COUNT|CLEARED", "$DECLINED_COUNT|CLEARED"),
        outcomes("true", "maybe", "false", "maybe"),
    )

    private companion object {
        const val ROOT = "/Order"
        const val APPROVAL_COUNT = "/Order/ApprovalCount"
        const val DECLINED_COUNT = "/Order/DeclinedCount"
        const val APPROVALS = "/Order/Approvals"
        const val DECLINES = "/Order/Declines"
        const val EXPEDITED = "/Order/Approvals/Expedited"
        const val TERMS = "/Order/Approvals/TermsAccepted"
        const val GIFT_WRAP = "/Order/Declines/GiftWrap"
        const val INSURANCE = "/Order/Declines/Insurance"
    }
}

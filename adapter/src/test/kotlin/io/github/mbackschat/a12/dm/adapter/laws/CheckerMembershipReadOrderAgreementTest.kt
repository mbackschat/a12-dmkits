package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.modelkit.CustomFieldBuilder
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import java.util.function.Consumer
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A computation precondition's membership operands are read in authored order, and each operand's computation
 * binding is decided before the next operand is read (KERNEL-FINDINGS KF548): an unbound repeatable operand
 * written first answers `MVK_NO_WILDCARD`, a missing name written first answers `MVK_INVALID_ENTITY`, and the
 * whole-list duplicate and kind gates answer only after every operand has been read. A12 and the portable checker
 * must name the same class on every row.
 *
 * Every class-carrying row has a twin with the operands reversed, so an account that ranks the two classes
 * instead of reading in order gets one row of each pair wrong. Re-measures a12-kernel-lean's report
 * (their `SPEC-2026-10-13-03`) on our own carrier: an order with repeatable lines whose `Ref` is a Custom token.
 */
class CheckerMembershipReadOrderAgreementTest {

    private val order = DocumentModelDraft.named("order-membership").locale("en_US")
        .rootGroup("Order", Consumer { o -> o
            .numberField("Qty") { n -> n.fractionDigits(0) }
            .numberField("Limit") { n -> n.fractionDigits(0) }
            .stringField("Code") { }
            .stringField("Alias") { }
            .numberField("Probe") { n -> n.fractionDigits(0) }
            .group("Lines") { l -> l.repeatable(5)
                .numberField("Qty") { n -> n.fractionDigits(0) }
                .customField("Ref", Consumer<CustomFieldBuilder> { it.type("LineRef") }) } })
        .build().toJson()

    private val validator = RuleValidator()

    @Test
    fun eachOperandIsBoundBeforeTheNextIsRead() {
        val rows = linkedMapOf(
            "Number control" to ("AtLeastOneFieldValueIncludedInValueList(Qty In Limit)" to ADMITTED),
            "token control" to ("AtLeastOneFieldValueIncludedInValueList(Code In Alias)" to ADMITTED),
            "a missing member" to ("AtLeastOneFieldValueIncludedInValueList(Qty In NoSuch)" to ENTITY),
            "an unbound member" to ("AtLeastOneFieldValueIncludedInValueList(Qty In Lines/Qty)" to UNBOUND),
            "mixed kinds" to ("AtLeastOneFieldValueIncludedInValueList(Qty In Code)" to VARYING),
            "unbound, then missing" to ("AtLeastOneFieldValueIncludedInValueList(Qty In Lines/Qty,NoSuch)" to UNBOUND),
            "missing, then unbound" to ("AtLeastOneFieldValueIncludedInValueList(Qty In NoSuch,Lines/Qty)" to ENTITY),
            "an unbound group, then missing" to ("AtLeastOneFieldValueIncludedInValueList(Qty In Lines,NoSuch)" to UNBOUND),
            "missing, then an unbound group" to ("AtLeastOneFieldValueIncludedInValueList(Qty In NoSuch,Lines)" to ENTITY),
            "a repeated subject, then missing" to ("AtLeastOneFieldValueIncludedInValueList(Qty,Qty In NoSuch)" to ENTITY),
            "an unbound subject, then missing" to ("AtLeastOneFieldValueIncludedInValueList(Lines/Qty In NoSuch)" to UNBOUND),
            "an unbound Custom token, then missing" to
                ("AtLeastOneFieldValueIncludedInValueList(Code In Lines/Ref,NoSuch)" to UNBOUND),
            "missing, then an unbound Custom token" to
                ("AtLeastOneFieldValueIncludedInValueList(Code In NoSuch,Lines/Ref)" to ENTITY),
        )
        assertAgrees(rows)
    }

    /** The whole-list gates wait for every read: a duplicate or a kind mismatch never pre-empts a later read error. */
    @Test
    fun wholeListGatesFollowEveryOperandRead() {
        val rows = linkedMapOf(
            "a repeated subject, then an unbound member" to
                ("AtLeastOneFieldValueIncludedInValueList(Qty,Qty In Lines/Qty)" to UNBOUND),
            "a repeated member, then an unbound one" to
                ("AtLeastOneFieldValueIncludedInValueList(Qty In Limit,Limit,Lines/Qty)" to UNBOUND),
            "a kind mismatch, then missing" to ("AtLeastOneFieldValueIncludedInValueList(Qty In Code,NoSuch)" to ENTITY),
            "a missing subject, then an unbound member" to
                ("AtLeastOneFieldValueIncludedInValueList(Qty,NoSuch In Lines/Qty)" to ENTITY),
        )
        assertAgrees(rows)
    }

    /** The plural siblings and the scalar form read their operands the same way. */
    @Test
    fun theSiblingMembershipOperatorsReadInTheSameOrder() {
        val rows = linkedMapOf(
            "No, unbound then missing" to ("NoFieldValueIncludedInValueList(Qty In Lines/Qty,NoSuch)" to UNBOUND),
            "No, missing then unbound" to ("NoFieldValueIncludedInValueList(Qty In NoSuch,Lines/Qty)" to ENTITY),
            "NotAll, unbound then missing" to ("NotAllFieldValuesIncludedInValueList(Qty In Lines/Qty,NoSuch)" to UNBOUND),
            "NotAll, missing then unbound" to ("NotAllFieldValuesIncludedInValueList(Qty In NoSuch,Lines/Qty)" to ENTITY),
            "scalar, unbound then missing" to ("FieldValueIncludedInValueList(Qty, [Lines/Qty], [NoSuch])" to UNBOUND),
            "scalar, missing then unbound" to ("FieldValueIncludedInValueList(Qty, [NoSuch], [Lines/Qty])" to ENTITY),
        )
        assertAgrees(rows)
    }

    private fun assertAgrees(rows: Map<String, Pair<String, Set<String>>>) {
        val wrong = rows.mapNotNull { (row, written) ->
            val (precondition, a12Class) = written
            val (a12, ours) = compare(precondition)
            if (a12 == a12Class && ours == a12Class) null
            else "$row: expected $a12Class, A12 $a12, ours $ours"
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n", "rows that disagree:\n"))
    }

    /** The membership is the computation's common precondition, so the computed field is the read's context. */
    private fun compare(precondition: String): Pair<Set<String>, Set<String>> {
        val draft = DraftComputation("/Order", "Derived", "../Probe", precondition,
            listOf(DraftComputation.Alternative(null, "1")))
        val a12 = validator.check(order, draft).let { result ->
            if (result.ok()) emptySet() else result.diagnostics().map { it.codeOrSummary() }.toSet()
        }
        val ours = rejectingCodes(validator.inject(order, draft)).toSet()
        return a12 to ours
    }

    private companion object {
        val ADMITTED = emptySet<String>()
        val ENTITY = setOf("MVK_INVALID_ENTITY")
        val UNBOUND = setOf("MVK_NO_WILDCARD")
        val VARYING = setOf("MVK_VARYING_TYPES_NOT_ALLOWED")
    }
}

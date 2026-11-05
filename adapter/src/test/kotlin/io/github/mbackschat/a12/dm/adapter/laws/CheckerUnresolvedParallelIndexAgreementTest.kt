package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A parallel join whose one side nominates an index field its group does not have. Kernel 31.1.1 builds such a
 * model, reporting the nomination as `MVK_INVALID_INDEX_FIELD` ([KF653](../../../../../../../../../../../docs/KERNEL-FINDINGS.md#kf653)),
 * and a rule that joins the two groups draws `MVK_INDEX_FIELD_INVALID` beside it, whichever side holds the target
 * and whether the far side is read for presence or for its value. A12 resolves each side's index field before it
 * compares the two names, so the name-mismatch class never answers here. The control joins the same groups with
 * the index resolved, and a rule that reads only its own group draws nothing beyond the nomination. When both
 * groups nominate the same undeclared name the names agree, and existence still refuses, a join and a
 * `RepetitionNotUnique` over keys on the two branches alike, where the resolved control draws the same-path class.
 *
 * Each row is pinned for A12 and compared against the portable checker. Requested by a12-kernel-lean as
 * SPEC-2026-11-05-02 (rows `u1`–`u5`); own domain (an order's shipments joined with its returns).
 */
class CheckerUnresolvedParallelIndexAgreementTest {

    private val keyed = DocumentModelDraft.named("order-returns").locale("en_US")
        .rootGroup("Order") { o -> o.repeatable(5)
            .group("Shipments") { s -> s.repeatable(5).indexField("Ref")
                .numberField("Ref") { it.fractionDigits(0) }
                .stringField("Note") { } }
            .group("Returns") { r -> r.repeatable(5).indexField("Ref")
                .numberField("Ref") { it.fractionDigits(0) }
                .stringField("Reason") { } } }
        .build().toJson()

    /**
     * [keyed] with `Returns` nominating `Ticket`, which it does not declare. The typed builder refuses to author
     * that on purpose (A12 reports the model invalid), so the stored member is edited, as a hand-written model
     * would carry it.
     */
    private val dangling = nominating("Ticket", "/Order/Returns")

    /** Both groups nominate the same name, which neither declares: the names agree, so only existence can refuse. */
    private val bothDangling = nominating("Ticket", "/Order/Returns", "/Order/Shipments")

    private fun nominating(index: String, vararg groups: String): String {
        val mapper = ObjectMapper()
        val root = mapper.readTree(keyed)
        root.findParents("id").filter { it.path("id").asText() in groups }
            .forEach { (it.path("Group") as ObjectNode).put("indexFieldName", index) }
        return mapper.writeValueAsString(root)
    }

    private val validator = RuleValidator()

    @Test
    fun aJoinOverAnUnresolvedIndexDrawsTheIndexNameClassFromEitherSide() {
        val nomination = "MVK_INVALID_INDEX_FIELD"
        val joined = setOf(nomination, "MVK_INDEX_FIELD_INVALID")
        val rows = linkedMapOf(
            "u1: presence read of the unresolved side" to
                (verdicts(dangling, "/Order/Shipments", "../Note",
                    "FieldFilled(/Order/Shipments/Note) And FieldFilled(/Order/Returns/Reason)") to joined),
            "u2: the same rule with the index resolved — the control" to
                (verdicts(keyed, "/Order/Shipments", "../Note",
                    "FieldFilled(/Order/Shipments/Note) And FieldFilled(/Order/Returns/Reason)") to emptySet()),
            "u3: the rule reads its own group only" to
                (verdicts(dangling, "/Order/Shipments", "../Note", "FieldFilled(/Order/Shipments/Note)") to setOf(nomination)),
            "u4: the target on the unresolved side" to
                (verdicts(dangling, "/Order/Returns", "../Reason",
                    "FieldFilled(/Order/Returns/Reason) And FieldFilled(/Order/Shipments/Note)") to joined),
            "u5: a value read of the unresolved side" to
                (verdicts(dangling, "/Order/Shipments", "../Note",
                    "FieldFilled(/Order/Shipments/Note) And [/Order/Returns/Ref] > 0") to joined),
            "both sides name the same undeclared index — a join" to
                (verdicts(bothDangling, "/Order/Shipments", "../Note",
                    "FieldFilled(/Order/Shipments/Note) And FieldFilled(/Order/Returns/Reason)") to joined),
            "both sides name the same undeclared index — RepetitionNotUnique, whose keys must share a path" to
                (verdicts(bothDangling, "/Order", "../Shipments/Note",
                    "RepetitionNotUnique(Shipments/Ref, Returns/Ref)") to joined),
            "the same RepetitionNotUnique with the index resolved — the control" to
                (verdicts(keyed, "/Order", "../Shipments/Note", "RepetitionNotUnique(Shipments/Ref, Returns/Ref)")
                    to setOf("MVK_NOT_IN_SAME_PATH")),
        )
        assertEquals(rows.mapValues { (_, row) -> row.second to row.second }, rows.mapValues { it.value.first },
            "(A12, ours)")
    }

    private fun verdicts(model: String, group: String, errorField: String, condition: String): Pair<Set<String>, Set<String>> {
        val draft = DraftRule(group, "JoinRule", errorField, condition, "JOIN", "ERROR")
        val result = validator.check(model, draft)
        val a12 = if (result.ok()) emptySet() else result.diagnostics().map { it.code().orElse("?") }.toSet()
        val ours = rejectingCodes(validator.inject(model, draft)).toSet()
        return a12 to ours
    }
}

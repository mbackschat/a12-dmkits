package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.core.ModelConsistencyException
import io.github.mbackschat.a12.dm.core.ModelInputException
import io.github.mbackschat.a12.dm.core.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A reference that names a RULE or a COMPUTATION, on kernel 31.1.1. A12 resolves a reference among the model's
 * fields and groups only, so an executable's path answers as a path that names nothing, in every role measured: a
 * computed field draws the converter's "was not found" sentence, an error target `MVK_ERROR_FIELD_MISSING` with
 * `MVK_MISSING_ERROR_FIELD`, a condition operand `MVK_INVALID_ENTITY`, whether it is written relative or absolute,
 * and a message parameter `INVALID_ENTITY` rather than the membership class. The control rows resolve the same slots
 * to fields (KERNEL-FINDINGS KF674).
 *
 * Each row is pinned for A12 and compared against the portable checker. Reached from a12-kernel-lean's
 * SPEC-2026-11-05-01 row `mc-ct-rule` (a computed field naming a rule); the other rows are its siblings. Stored
 * DM-JSON only, because the typed surface hands each slot a field handle; own domain (a policy's holder and premium).
 */
class CheckerExecutableAsReferenceAgreementTest {

    private val base = DocumentModelDraft.named("policy-references").locale("en_US")
        .rootGroup("Policy") { p ->
            p.stringField("Holder") { }.numberField("Premium") { it.fractionDigits(2) }.stringField("Note") { }
        }
        .build().toJson()
        .let { RuntimeLaws.withRule(it, "/Policy", "HolderRequired", "../Holder", "HOLDER", "FieldFilled(Holder)") }
        .let { RuntimeLaws.withComputation(it, "/Policy", "Fee", "../Premium", "10.00") }

    private fun computing(target: String) = RuntimeLaws.withComputation(base, "/Policy", "Copy", target, "\"x\"")

    private fun reporting(target: String) =
        RuntimeLaws.withRule(base, "/Policy", "NoteRule", target, "NOTE", "FieldFilled(Note)")

    private fun reading(condition: String) =
        RuntimeLaws.withRule(base, "/Policy", "NoteRule", "../Note", "NOTE", condition)

    private fun naming(parameter: String) = RuntimeLaws.withRuleMessages(
        base, "/Policy", "NoteRule", "../Note", "NOTE", "FieldFilled(Note)", "$parameter is missing", "$parameter fehlt")

    @Test
    fun anExecutablesPathResolvesAsAPathThatNamesNothing() {
        val notFound = setOf("RK_COMPUTED_FIELD_NOT_FOUND")
        val noErrorField = setOf("MVK_ERROR_FIELD_MISSING", "MVK_MISSING_ERROR_FIELD")
        val invalidEntity = setOf("MVK_INVALID_ENTITY")
        val rows = linkedMapOf(
            "a computed field naming a field — the control" to (computing("../Note") to emptySet()),
            "a computed field naming a rule" to (computing("../HolderRequired") to notFound),
            "a computed field naming a computation" to (computing("../Fee") to notFound),
            "an error target naming a field — the control" to (reporting("../Note") to emptySet()),
            "an error target naming a rule" to (reporting("../HolderRequired") to noErrorField),
            "an error target naming a computation" to (reporting("../Fee") to noErrorField),
            "a condition reading a field — the control" to (reading("FieldFilled(Note)") to emptySet()),
            "a condition reading a rule" to (reading("FieldFilled(Note) And FieldFilled(HolderRequired)") to invalidEntity),
            "a condition reading a computation" to (reading("FieldFilled(Note) And FieldFilled(Fee)") to invalidEntity),
            "a condition reading a rule by its absolute path" to
                (reading("FieldFilled(Note) And FieldFilled(/Policy/HolderRequired)") to invalidEntity),
            "a message parameter naming a field the rule reads — the control" to (naming("\$Note\$") to emptySet()),
            "a message parameter naming a rule" to (naming("\$HolderRequired\$") to setOf("INVALID_ENTITY")),
        )
        val observed = rows.mapValues { (_, row) -> a12Classes(row.first) to rejectingCodes(row.first).toSortedSet() }
        assertEquals(rows.mapValues { (_, row) -> row.second.toSortedSet().let { it to it } }, observed, "(A12, ours)")
    }

    private fun a12Classes(json: String): Set<String> = try {
        ModelLoader.loadSelfContained(json)
        sortedSetOf()
    } catch (unreadable: ModelInputException) {
        sortedSetOf("unreadable")
    } catch (rejected: ModelConsistencyException) {
        rejected.notifications().mapTo(sortedSetOf()) { legibleNotificationClass(it.message().trim()) }
    }
}

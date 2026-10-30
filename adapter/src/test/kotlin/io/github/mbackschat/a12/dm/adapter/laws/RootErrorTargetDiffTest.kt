package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.core.ConsistencyCheckFailedException
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * A rule reporting on the ROOT group (KERNEL-FINDINGS KF603, KF522; a12-kernel-lean SPEC-2026-10-30-08): declared
 * in a group below the root with a condition naming the root group, it fires once at the bare root pointer, as an
 * omission for the root's absence and as a value for its presence, and the root names none of its fields in either
 * list under either polarity, on a root target and beside a field target.
 *
 * The root targets are spliced with [RuntimeLaws.withRule] because the kit's rule builder refuses an error target
 * outside the declaring group by decision (FEATURE-GAPS §4); their conditions are still typed and rendered. Both
 * strategies and the interpreter are compared on every document, references included; the messages are pinned from
 * dynamic Groovy. Own domain (an insurance policy's remark, contact and ledger number).
 */
class RootErrorTargetDiffTest {

    private val base: String = DocumentModelDraft.named("policy-root-target").locale("en_US")
        .rootGroup("Policy") { p ->
            p.stringField("Remark") { }.stringField("Contact") { }.group("Ledger") { l -> l.stringField("Number") { } }
        }.build().toJson()

    private val session = EditSession.read(base)
    private val policy = session.group("/Policy")
    private val remark = policy.str("Remark")
    private val absent = Dsl.groupNotFilled(policy.ref())
    private val present = Dsl.groupFilled(policy.ref())

    /** A rule in `/Policy/Ledger` whose error target `../..` is the root. */
    private fun onRoot(model: String, code: String, condition: Condition): String =
        RuntimeLaws.withRule(model, "/Policy/Ledger", code.lowercase(), "../..", code, condition)

    private val model: String = run {
        listOf("REMARK_WITH_ROOT_ABSENT" to absent.and(remark.notFilled()),
            "REMARK_OR_ROOT_ABSENT" to absent.or(remark.notFilled())).forEach { (code, condition) ->
            policy.addRule { it.name(code.lowercase()).code(code).errorField(remark).`when`(condition)
                .message(Dsl.en("Add a remark.")).build() }
        }
        mapOf("ROOT_ABSENT" to absent, "ROOT_PRESENT" to present,
            "ROOT_WITHOUT_REMARK" to present.and(remark.notFilled()))
            .entries.fold(session.toJson()) { json, (code, condition) -> onRoot(json, code, condition) }
    }

    private val documents = linkedMapOf(
        "empty" to """{"Policy":{}}""",
        "contact" to """{"Policy":{"Contact":"claims@example.com"}}""",
        "remark" to """{"Policy":{"Remark":"renewal"}}""",
        "ledger" to """{"Policy":{"Ledger":{"Number":"L-7"}}}""",
    )

    @Test
    fun a12AndThePortableCheckerAdmitTheModel() {
        assertEquals(emptyList<String>(), MessageParameterProbes.kernel(model))
        assertEquals(emptyList<String>(), rejectingCodes(model))
    }

    /** A root target whose condition names no group draws the error-field class, from A12 and the checker alike. */
    @Test
    fun aConditionNotNamingTheRootIsRefused() {
        val unnamed = onRoot(base, "REMARK_ONLY", remark.notFilled())
        assertEquals(listOf("MVK_ERROR_FIELD_NOT_REFERENCED"), MessageParameterProbes.kernel(unnamed))
        assertEquals(listOf("MVK_ERROR_FIELD_NOT_REFERENCED"), rejectingCodes(unnamed))
    }

    /** Declared in the root itself, the same target and condition fail A12's build without a class; the checker names that. */
    @Test
    fun theRootCannotReportOnItselfFromItsOwnDeclaration() {
        val own = RuntimeLaws.withRule(base, "/Policy", "root_absent", "..", "ROOT_ABSENT", absent)
        val failure = assertThrows<ConsistencyCheckFailedException> { MessageParameterProbes.kernel(own) }
        assertTrue(failure.message!!.startsWith("the kernel could not build this model"), failure.message)
        assertEquals(listOf("CK_MODEL_NOT_BUILDABLE"), rejectingCodes(own))
    }

    @Test
    fun allThreeEnginesAgreeOnEveryDocument() = DocumentDifferential.assertAgree(model, documents)

    /**
     * Each document's messages as `code type pointer refs fill`, below `/Policy`. The root's own rules list nothing
     * unless a field beside the root is read; the field targets list only `Remark`.
     */
    @Test
    fun theRootFiresOnceAtItsBarePointerAndNamesNoneOfItsFields() {
        assertEquals(PINNED, documents.keys.associateWith { document ->
            DocumentDifferential.kernel(model, documents.getValue(document), dynamic = true).messages.map {
                "${it.code} ${it.type} ${it.pointer} ${it.referenced.map(::short)} ${it.fillToFix.map(::short)}"
            }.sorted()
        })
    }

    private fun short(pointer: String) = pointer.removePrefix("/Policy[1]/")

    private companion object {
        const val REMARK_OMITTED = "[Remark] [Remark]"
        val PINNED = mapOf(
            "empty" to listOf(
                "REMARK_OR_ROOT_ABSENT OMISSION_ERROR /Policy[1]/Remark $REMARK_OMITTED",
                "REMARK_WITH_ROOT_ABSENT OMISSION_ERROR /Policy[1]/Remark $REMARK_OMITTED",
                "ROOT_ABSENT OMISSION_ERROR /Policy [] []",
            ),
            "contact" to listOf(
                "REMARK_OR_ROOT_ABSENT OMISSION_ERROR /Policy[1]/Remark $REMARK_OMITTED",
                "ROOT_PRESENT VALUE_ERROR /Policy [] []",
                "ROOT_WITHOUT_REMARK OMISSION_ERROR /Policy $REMARK_OMITTED",
            ),
            "remark" to listOf("ROOT_PRESENT VALUE_ERROR /Policy [] []"),
            "ledger" to listOf(
                "REMARK_OR_ROOT_ABSENT OMISSION_ERROR /Policy[1]/Remark $REMARK_OMITTED",
                "ROOT_PRESENT VALUE_ERROR /Policy [] []",
                "ROOT_WITHOUT_REMARK OMISSION_ERROR /Policy $REMARK_OMITTED",
            ),
        )
    }
}

package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.FirstFilledOperand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * `FirstFilledValue` over a bare group or a keyed group row, whose walk reaches a required-empty field before a filled
 * sibling (KERNEL-FINDINGS KF552 had measured the field, category and starred arms only). `Holder` and the `Contacts`
 * rows declare `Phone` required; `Office` and the `Branches` rows are their optional twins, so each pair of rules
 * differs only in whether the first cell the walk passes is available. A computation reads the same walk, where a
 * required-empty field stays an ordinary empty.
 *
 * Own domain (a policy holder's contact details).
 */
class RequiredEmptyFirstFilledGroupDiffTest {

    private val model: String = run {
        val session = EditSession.read(DocumentModelDraft.named("policy-first-filled-group").locale("en_US")
            .rootGroup("Policy") { p -> p.stringField("Ref") { }.stringField("Pick") { }
                .group("Holder") { h -> h.stringField("Phone") { it.required() }.stringField("Street") { } }
                .group("Office") { o -> o.stringField("Phone") { }.stringField("Street") { } }
                .group("Contacts") { c -> c.repeatable(3).stringField("Phone") { it.required() }.stringField("Street") { }
                    .stringField("Kind") { }.indexField("Kind") }
                .group("Branches") { b -> b.repeatable(3).stringField("Phone") { }.stringField("Street") { }
                    .stringField("Kind") { }.indexField("Kind") } }
            .build().toJson())
        val policy = session.group("/Policy")
        val ref = FirstFilledOperand.of(policy.field("Ref"))
        val operands = listOf("Holder" to policy.firstFilledGroup("Holder"), "Office" to policy.firstFilledGroup("Office"),
            "Contacts" to policy.firstFilledGroupFor("Contacts", "HOME"),
            "Branches" to policy.firstFilledGroupFor("Branches", "HOME"))
        for ((name, operand) in operands) policy.addRule {
            it.name("${name}First").code("${name}First").errorField(policy.field("Ref"))
                .`when`(Dsl.firstFilledString(operand, ref).eq("Main"))
                .message(Dsl.en("Check the contact details.")).build()
        }
        policy.addComputation(policy.str("Pick")) {
            it.name("PickComp").calc(Dsl.firstFilledString(policy.firstFilledGroup("Holder"), ref))
                .message(Dsl.en("Check the picked contact.")).build()
        }
        session.toJson()
    }

    private val documents = linkedMapOf(
        EMPTY_FIRST to """{"Policy":{"Ref":"P-1","Holder":{"Street":"Main"},"Office":{"Street":"Main"},""" +
            """"Contacts":[{"Street":"Main","Kind":"HOME"}],"Branches":[{"Street":"Main","Kind":"HOME"}]}}""",
        FILLED_FIRST to """{"Policy":{"Ref":"P-1","Holder":{"Phone":"Main"},"Office":{"Phone":"Main"},""" +
            """"Contacts":[{"Phone":"Main","Kind":"HOME"}],"Branches":[{"Phone":"Main","Kind":"HOME"}]}}""",
    )

    @Test
    fun theModelIsKernelConsistent() {
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(model))
    }

    @Test
    fun allThreeEnginesAgreeOnEveryDocument() {
        DocumentDifferential.assertAgree(model, documents)
    }

    /** The required-empty cell leaves the walk undecided where the optional twin reads past it; a walk the required
     *  field already decides fires, and the computation reads the empty field as an ordinary empty. */
    @Test
    fun theKernelLeavesTheWalkUndecidedAtTheRequiredEmptyCell() {
        for (dynamic in listOf(true, false)) {
            assertEquals(listOf("BranchesFirst", "OfficeFirst"), authored(documents.getValue(EMPTY_FIRST), dynamic), "$dynamic")
            assertEquals(listOf("BranchesFirst", "ContactsFirst", "HolderFirst", "OfficeFirst"),
                authored(documents.getValue(FILLED_FIRST), dynamic), "$dynamic")
        }
        assertEquals("VALUE|Main", DocumentDifferential.kernel(model, documents.getValue(EMPTY_FIRST), dynamic = true)
            .outcomes["Pick"])
    }

    /** Under partial validation the requirement holds where its field is selected, so the walk stops there too. */
    @Test
    fun aPartialSelectionOfTheGroupLeavesTheWalkUndecidedOnAllThreeEngines() {
        val selection = setOf(RelevantEntity("/Policy/Holder", listOf(0, 0)), RelevantEntity("/Policy/Office", listOf(0, 0)),
            RelevantEntity("/Policy/Ref", listOf(0, 0)))
        val document = documents.getValue(EMPTY_FIRST)
        val dynamic = DocumentDifferential.kernelMessages(model, document, true, selection)
        assertEquals(listOf("OfficeFirst"), dynamic.map { it.code }.filter { it.endsWith("First") })
        assertEquals(dynamic, DocumentDifferential.kernelMessages(model, document, false, selection))
        assertEquals(dynamic, DocumentDifferential.interpreterMessages(model, document, selection))
    }

    private fun authored(document: String, dynamic: Boolean): List<String> =
        DocumentDifferential.kernelMessages(model, document, dynamic).map { it.code }.filter { it.endsWith("First") }.sorted()

    private companion object {
        const val EMPTY_FIRST = "an empty required field before a filled street"
        const val FILLED_FIRST = "the required field filled first"
    }
}

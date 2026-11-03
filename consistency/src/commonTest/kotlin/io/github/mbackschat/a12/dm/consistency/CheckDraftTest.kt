package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [checkDraft] — DSL text an editor has typed but not saved, analysed in the model it belongs to (slice I).
 *
 * **Why it is not "check the edited model".** The text being typed is not in the model yet, and an editor
 * that wrote it there to find out would have to undo the write on every keystroke. So the draft is applied to
 * the fact base rather than to the source, and the answer is attributed like any other edit: the DRAFT's own
 * findings, apart from what the declaration's neighbours already carried.
 *
 * **The load-bearing rows are the two an editor would get wrong.** A draft under construction is INCOMPLETE
 * rather than invalid, and saying `Invalid` about half-typed text is an over-rejection an author sees on every
 * keystroke ([anIncompleteDraftIsNotBlamedOnTheAuthorAsAnInvalidOne]). And a draft's verdict may not absorb
 * its neighbours' defects ([anotherDeclarationsFindingsAreNotAttributedToTheDraft]), which is `checkEdit`'s
 * property inherited rather than re-derived.
 */
class CheckDraftTest {

    @Test
    fun theBaselineIsClean() {
        assertEquals(emptyList(), checkRead(ModelSource(base)).findings.map { "${it.code}@${it.at}" })
    }

    @Test
    fun aDraftThatBreaksTheTextIsAttributedToTheDraft() {
        val report = checkDraft(ModelSource(base), "/Order/CodeRequired", "errorCondition",
            "FieldFilled(NoSuchFieldHere)")

        assertEquals(ConsistencyStatus.Invalid, report.status)
        assertEquals(1, report.introduced.size, "exactly what the draft did: ${report.introduced}")
        assertEquals("/Order/CodeRequired", report.introduced.single().at)
        assertEquals(emptyList(), report.resolved)
    }

    /**
     * Half-typed text is the state an editor is in almost all the time, and A12 has a code for it. Calling it
     * invalid would tell an author their rule is wrong while they are still writing it.
     */
    @Test
    fun anIncompleteDraftIsNotBlamedOnTheAuthorAsAnInvalidOne() {
        val report = checkDraft(ModelSource(base), "/Order/CodeRequired", "errorCondition",
            "FieldFilled(Code) And")

        assertEquals(listOf("MVK_INCOMPLETE_INPUT"), report.introduced.map { it.code },
            "the draft ran out of input, which is its own class: ${report.introduced}")
    }

    @Test
    fun aCompletedDraftAgreesWithCheckingTheSavedModel() {
        val completed = "FieldFilled(Code) And FieldFilled(Net)"
        val drafted = checkDraft(ModelSource(base), "/Order/CodeRequired", "errorCondition", completed)
        // The same text SAVED into the source, checked from scratch — the agreement that makes a draft answer
        // worth acting on rather than an approximation of one.
        val saved = checkRead(ModelSource(base.replace("FieldFilled(Code)", completed)))

        assertEquals(saved.findings, drafted.edited.findings)
        assertEquals(saved.status, drafted.edited.status)
        assertEquals(saved.checksCompleted, drafted.edited.checksCompleted)
        assertEquals(emptyList(), drafted.introduced, "and the completed draft breaks nothing")
    }

    /** Each of the four DSL carriers A12 stores, analysed where it sits rather than as loose text. */
    @Test
    fun allFourDslCarriersAreAnalysedInPlace() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a rule's condition"] = codes("/Order/CodeRequired", "errorCondition", "FieldFilled(Nope)")
        actual["a computation's common precondition"] =
            codes("/Order/NetTotal", "commonPrecondition", "FieldFilled(Nope)")
        actual["an alternative's precondition"] =
            codes("/Order/NetTotal", "computationAlternatives[0].precondition", "FieldFilled(Nope)")
        actual["an alternative's operation"] =
            codes("/Order/NetTotal", "computationAlternatives[0].operation", "Sum(Nope)")

        // One class, four carriers: the point is that each member is reached at all, and a carrier the draft
        // could not apply to would show as an empty row rather than as a wrong code.
        val expected = actual.keys.associateWith { listOf("MVK_INVALID_ENTITY") }
        assertEquals(expected, actual)
    }

    /**
     * A draft REPLACES the member it names — the property a nested member can fail silently.
     *
     * An alternative's text lives two levels down (`computationAlternatives[0].operation`), so a draft that
     * mis-parsed that name could write a flat member of the same spelling BESIDE it. Every carrier would
     * still be analysed and every code would still be right, because the bogus member reads as the very
     * member it is named after — what would not happen is the original text going away. So the discriminator
     * is a baseline whose alternative is already broken: replacing it RESOLVES the defect, where writing
     * beside it leaves it standing.
     */
    @Test
    fun aDraftIntoAnAlternativeReplacesItRatherThanLandingBesideIt() {
        val broken = base.replace("Sum(Lines*/Fee)", "Sum(Nope)")
        val report = checkDraft(ModelSource(broken), "/Order/NetTotal",
            "computationAlternatives[0].operation", "Sum(Lines*/Fee)")

        assertEquals(listOf("MVK_INVALID_ENTITY"), report.resolved.map { it.code },
            "the draft replaced the broken text, so its finding is gone: ${report.resolved}")
        assertEquals(emptyList(), report.edited.findings.map { "${it.code}@${it.at}" },
            "and nothing is left over from the text it replaced")
        // Against saving the same text, which is what makes the draft answer worth acting on.
        assertEquals(checkRead(ModelSource(base)).findings, report.edited.findings)
    }

    /**
     * The over-rejection control, inherited from [checkEdit] rather than re-derived: the model is already
     * broken elsewhere and the draft is sound, so a whole-report answer would condemn a perfectly good draft.
     */
    @Test
    fun anotherDeclarationsFindingsAreNotAttributedToTheDraft() {
        val broken = base.replace("AtLeastOneFieldFilled(Lines*/Fee)", "AtLeastOneFieldFilled(Lines*/Nope)")
        val report = checkDraft(ModelSource(broken), "/Order/CodeRequired", "errorCondition",
            "FieldFilled(Code) And FieldFilled(Net)")

        assertEquals(ConsistencyStatus.Inconclusive, report.status)
        assertEquals(emptyList(), report.introduced)
        // …and the neighbour's defect stays readable, which is what an author needs to see about the model
        // they are editing even though their draft is not what broke it.
        assertTrue(report.baseline.findings.any { it.at == "/Order/FeePresent" },
            "the neighbour's own finding survives: ${report.baseline.findings}")
        assertTrue(report.edited.findings.any { it.at == "/Order/FeePresent" })
    }

    @Test
    fun aDraftForADeclarationTheModelDoesNotCarryIsRefusedWithWhatItDoesCarry() {
        val missing = checkDraft(ModelSource(base), "/Order/NoSuchRule", "errorCondition", "FieldFilled(Code)")

        assertEquals(ConsistencyStatus.Inconclusive, missing.status)
        assertEquals(emptyList(), missing.introduced, "nothing was analysed, so nothing may be attributed")
        assertTrue(missing.gaps.any { it.at == "/Order/NoSuchRule" }, "the refusal names it: ${missing.gaps}")
    }

    @Test
    fun aDraftForAMemberTheDeclarationDoesNotCarryNamesTheOnesItDoes() {
        val wrong = checkDraft(ModelSource(base), "/Order/CodeRequired", "computationAlternatives[0].operation",
            "Sum(Lines*/Fee)")

        assertEquals(emptyList(), wrong.introduced)
        val gap = wrong.gaps.single { it.at == "/Order/CodeRequired" }
        assertTrue(gap.reason.contains("errorCondition"), "the members it does carry are named: ${gap.reason}")
    }

    @Test
    fun aDraftAgainstAnUnreadableSourceRefusesRatherThanAttributing() {
        val report = checkDraft(ModelSource("{\"header\":"), "/Order/CodeRequired", "errorCondition",
            "FieldFilled(Code)")

        assertEquals(emptyList(), report.introduced)
        assertTrue(report.gaps.any { it.at == null }, "the refusal is stated: ${report.gaps}")
    }

    private fun codes(at: String, member: String, text: String) =
        checkDraft(ModelSource(base), at, member, text).introduced.map { it.code }

    private val base = """
        {"header":{"modelVersion":"29.4.0","id":"orders","locales":[{"code":"en_US"}]},
         "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
            {"type":"Field","id":"f-code","name":"Code","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Field","id":"f-net","name":"Net","Field":{"fieldType":{"type":"NumberType"}}},
            {"type":"Group","id":"g-lines","name":"Lines","Group":{"repeatability":20,"elements":[
              {"type":"Field","id":"f-fee","name":"Fee","Field":{"fieldType":{"type":"NumberType"}}}
            ]}},
            {"type":"Rule","id":"r-code","name":"CodeRequired","Rule":{
              "errorCondition":"FieldFilled(Code)","errorEntityRelPath":"../Code",
              "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"the order code"}]}},
            {"type":"Rule","id":"r-fee","name":"FeePresent","Rule":{
              "errorCondition":"AtLeastOneFieldFilled(Lines*/Fee)","errorEntityRelPath":"../Lines/Fee",
              "errorCode":"C2","errorMessage":[{"locale":"en_US","text":"a line fee"}]}},
            {"type":"Computation","id":"c-net","name":"NetTotal","Computation":{
              "computedFieldRelPath":"../Net","computationAlternatives":[{"operation":"Sum(Lines*/Fee)"}],
              "errorMessage":[{"locale":"en_US","text":"the net total"}]}}
          ]}}
         ]}}}
    """.trimIndent()
}

package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [checkEdit] — what an EDIT did to a model's consistency, apart from what the model already carried.
 *
 * **Why attribution is required rather than nice.** This checker reports over a whole model, so a consumer
 * asking "is my edit acceptable?" and shown the edited model's whole report would be told `Invalid` the moment
 * the BASELINE carried an ERROR of its own — a verdict about someone else's declaration, delivered as a
 * verdict about the edit. That is an over-rejection, the one direction this project forbids, which is why the
 * baseline's own findings may never decide the edit's status.
 *
 * The two tests that carry that property are [anEditIsNotBlamedForADefectTheBaselineAlreadyCarried] and
 * [anUnanalysableBaselineRefusesToAttributeAnythingToTheEdit]; the rest establish that attribution reports
 * what the edit genuinely did, in both directions.
 */
class CheckEditTest {

    @Test
    fun anEditThatIntroducesADefectIsAttributedToTheEdit() {
        val report = checkEdit(model(soundRule()), model(soundRule(), danglingRule("Added")))

        assertEquals(ConsistencyStatus.Invalid, report.status, "the edit introduced a violated A12 constraint")
        assertEquals(1, report.introduced.size, "exactly the finding the edit added: ${report.introduced}")
        assertEquals("/Order/Added", report.introduced.single().at)
        assertEquals(emptyList(), report.resolved)
        assertEquals(emptyList(), report.baseline.findings, "the baseline was sound, so it discloses nothing")
    }

    /**
     * The over-rejection control. The baseline is broken and the edit is sound, so a whole-report answer would
     * say `Invalid` about a perfectly good edit.
     */
    @Test
    fun anEditIsNotBlamedForADefectTheBaselineAlreadyCarried() {
        val report = checkEdit(model(danglingRule()), model(danglingRule(), soundRule("Added")))

        assertEquals(ConsistencyStatus.Inconclusive, report.status,
            "the edit added no violation, so its verdict cannot be Invalid: ${report.introduced}")
        assertEquals(emptyList(), report.introduced)
        // Not discarded either: an author editing an already-broken model needs to know that, so it rides the
        // baseline report as a disclosure rather than as one of the edit's own findings.
        assertEquals(ConsistencyStatus.Invalid, report.baseline.status)
        assertTrue(report.baseline.findings.isNotEmpty(), "the baseline's own defect stays visible")
    }

    @Test
    fun anEditThatRepairsTheBaselineReportsWhatItResolved() {
        val report = checkEdit(model(danglingRule()), model(soundRule("Added")))

        assertEquals(ConsistencyStatus.Inconclusive, report.status)
        assertEquals(emptyList(), report.introduced)
        assertEquals(1, report.resolved.size, "the baseline's finding is gone after the edit")
        assertEquals("/Order/Broken", report.resolved.single().at)
    }

    /**
     * Without a baseline analysis there is no "already carried", so every finding in the edited model would
     * read as introduced — the over-rejection this whole operation exists to prevent, arriving through the
     * back door. It refuses to attribute instead, and says so as a gap.
     */
    @Test
    fun anUnanalysableBaselineRefusesToAttributeAnythingToTheEdit() {
        val report = checkEdit(ModelSource("{\"header\":", "unreadable"), model(danglingRule()))

        assertEquals(ConsistencyStatus.Inconclusive, report.status)
        assertEquals(emptyList(), report.introduced,
            "nothing may be attributed to the edit when the baseline was never analysed")
        assertEquals(emptyList(), report.resolved)
        assertTrue(report.gaps.any { it.at == null }, "the refusal is stated as a gap: ${report.gaps}")
        // The edited model's own report still rides along, so the caller is not left with nothing.
        assertEquals(ConsistencyStatus.Invalid, report.edited.status)
    }

    @Test
    fun anUnreadableEditIsInconclusiveRatherThanInvalid() {
        val report = checkEdit(model(soundRule()), ModelSource("{\"header\":", "unreadable"))

        assertEquals(ConsistencyStatus.Inconclusive, report.status, "unreadable is not the same as invalid")
        assertEquals(emptyList(), report.introduced)
        assertTrue(report.edited.diagnostics.isNotEmpty(), "why it could not be read is stated")
    }

    private fun model(vararg declarations: String) = ModelSource(
        """
        {"header":{"modelVersion":"29.4.0","id":"order","locales":[{"code":"en_US"}]},"content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Code","Field":{"fieldType":{"type":"StringType"}}},
            ${declarations.joinToString(",")}
          ]}}
        ]}}}
        """.trimIndent(),
        "edit-probe",
    )

    private fun soundRule(name: String = "Sound") = rule(name, "FieldFilled(Code)")

    private fun danglingRule(name: String = "Broken") = rule(name, "FieldFilled(NoSuchFieldHere)")

    private fun rule(name: String, condition: String) =
        """{"type":"Rule","id":"r-$name","name":"$name","Rule":{"errorCode":"C",
           "errorMessage":[{"locale":"en_US","text":"x"}],
           "errorEntityRelPath":"../Code","errorCondition":"$condition"}}"""
}

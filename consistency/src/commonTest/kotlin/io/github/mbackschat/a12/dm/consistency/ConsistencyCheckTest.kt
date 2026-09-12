package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * B0: the first public end-to-end operation — admit, prepare, resolve, report.
 *
 * Two things it must NOT do, both of which a passing happy path would hide: emit VALID, and imply that the
 * checks it ran are the required ones. No check family beyond references exists, so every report says so
 * explicitly as a coverage gap rather than leaving `complete = false` to be interpreted.
 */
class ConsistencyCheckTest {

    private fun model(rule: String) = ModelSource(
        """
        {"header":{"id":"order"},"content":{"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Code","Field":{}},
            $rule
          ]}}
        ]}}}
        """.trimIndent(),
    )

    private fun rule(relPath: String) =
        """{"type":"Rule","id":"r0","name":"Check","Rule":{"errorCode":"C",
           "errorEntityRelPath":"$relPath","errorCondition":"FieldFilled(Code)"}}"""

    @Test
    fun aResolvableModelIsInconclusiveWithItsReferenceCheckCompleted() {
        val report = check(model(rule("../Code")))

        assertEquals(ConsistencyStatus.Inconclusive, report.status)
        assertEquals(emptyList(), report.findings)
        assertTrue(CheckFamily.DECLARED_REFERENCES in report.checksCompleted)
        assertEquals("order", report.modelId)
    }

    /** Completing a check is not completing the analysis — the unimplemented families are stated, not implied. */
    @Test
    fun everyReportNamesTheFamiliesItCouldNotRun() {
        val report = check(model(rule("../Code")))

        assertEquals(false, report.status.complete)
        assertTrue(report.gaps.isNotEmpty(), "an incomplete verdict must say what it did not check")
    }

    @Test
    fun anUnresolvedErrorTargetMakesTheModelInvalid() {
        val report = check(model(rule("../Missing")))

        assertEquals(ConsistencyStatus.Invalid, report.status)
        assertEquals(listOf("MVK_ERROR_FIELD_MISSING"), report.findings.map { it.code })
        assertEquals("/Order/Check", report.findings.single().at)
        assertTrue(
            CheckFamily.DECLARED_REFERENCES in report.checksCompleted,
            "a check that found a violation still completed",
        )
    }

    /** An unreadable source is a source-level failure, not a rule-level one and not a consistency verdict. */
    @Test
    fun anUnreadableSourceReportsAtTheSourceLevel() {
        val report = check(ModelSource("{ not json"))

        assertEquals(ConsistencyStatus.Inconclusive, report.status)
        assertEquals(emptyList(), report.findings)
        assertEquals(listOf(DiagnosticCode.SOURCE_NOT_WELL_FORMED), report.diagnostics.map { it.code })
        assertEquals(emptySet(), report.checksCompleted, "nothing was checked, so nothing completed")
    }

    /** A declaration the checker could not read is a coverage gap, not a silent omission. */
    @Test
    fun anUnreadableDeclarationBecomesACoverageGap() {
        val report = check(model("""{"type":"Rule","name":"Check","Rule":{}}"""))

        assertEquals(
            listOf(DiagnosticCode.DECLARATION_NOT_WELL_FORMED),
            report.diagnostics.map { it.code },
        )
        assertTrue(report.gaps.any { it.at == "/Order/Check" }, report.gaps.toString())
    }
}

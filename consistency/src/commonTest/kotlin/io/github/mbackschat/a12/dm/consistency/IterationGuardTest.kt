package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The iteration guard on the fixture kernel 30.8.1 was measured on (`dmtool model check`, 2026-10-22): the
 * unguarded negative draws `MVK_NEG_CONDITION_IN_ITERATION`, its guarded twin draws nothing, and a rule whose
 * shape this checker does not judge for the law carries a gap at the rule instead of reading as examined. The
 * full row grid against A12 is `CheckerIterationGuardAgreementTest` in `:adapter`.
 */
class IterationGuardTest {

    private fun withCondition(condition: String, errorPath: String = "../Claims/Amount") =
        checkRead(ModelSource(UNGUARDED_NEGATIVE
            .replace("FieldNotFilled(Claims/Amount)", condition)
            .replace("\"../Claims/Amount\"", "\"$errorPath\"")))

    @Test
    fun anUnguardedNegativeIsRefusedAtTheRepetitionItRunsOver() {
        val report = withCondition("FieldNotFilled(Claims/Amount)")
        assertEquals(listOf("MVK_NEG_CONDITION_IN_ITERATION" to "/Policy/AmountMissing"),
            report.findings.map { it.code to it.at })
        assertTrue("/Policy/Claims" in report.findings.single().explain, report.findings.single().explain)
        assertEquals(emptyList(), report.gaps.filter { it.at == "/Policy/AmountMissing" })
    }

    @Test
    fun aPositiveReadingOfTheSameRepetitionGuardsIt() {
        val report = withCondition("FieldNotFilled(Claims/Amount) And FieldFilled(Claims/ClaimId)")
        assertEquals(emptyList(), report.findings.map { it.code })
        assertEquals(emptyList(), report.gaps.filter { it.at == "/Policy/AmountMissing" })
    }

    /** No report names the law as unread model-wide any more: what remains undecided is named per rule. */
    @Test
    fun aShapeLeftToA12IsNamedAtItsRule() {
        val report = withCondition("[Claims/ClaimId] PatternViolated \\\"[0-9]+\\\" And FieldFilled(Claims/Amount)")
        assertEquals(emptyList(), report.findings.map { it.code })
        val named = report.gaps.filter { "MVK_NEG_CONDITION_IN_ITERATION" in it.reason }
        assertEquals(listOf<String?>("/Policy/AmountMissing"), named.map { it.at }, report.gaps.toString())
    }

    private companion object {

        /**
         * An insurance policy whose rule reports on each claim's amount when it is not filled, with no positive
         * guard on the row. Kernel 30.8.1 refuses it with `MVK_NEG_CONDITION_IN_ITERATION`, and admits it once
         * `And FieldFilled(Claims/ClaimId)` is conjoined (`dmtool model check`, 2026-10-22).
         */
        private val UNGUARDED_NEGATIVE = """
            {"header":{"id":"policy-claims","modelType":"document","modelVersion":"28.4.0","locales":[{"code":"en_US"}],"labels":[],"annotations":[],"modelReferences":[]}
            ,"content":{"modelInfo":{"name":"policy-claims","variant":"cockpit","revision":"Arbeitsversion","immutable":false},"modelConfig":{"decimalSeparator":".","timeZone":"Europe/Berlin","conditionLanguage":{"code":"en_US"},"fieldRefByShortNameAllowed":true},"modelRoot":{"rootGroups":[
             {"type":"Group","id":"/Policy","name":"Policy","Group":{"repeatability":1,"elements":[
             {"type":"Field","id":"/Policy/PolicyNumber","name":"PolicyNumber","Field":{"fieldType":
             {"type":"StringType"}}},
             {"type":"Field","id":"/Policy/CoverageLimit","name":"CoverageLimit","Field":{"fieldType":
             {"type":"NumberType","NumberType":{"maxFractionalDigits":2,"minFractionalDigits":2}}}},
             {"type":"Group","id":"/Policy/Claims","name":"Claims","Group":{"repeatability":100,"elements":[
             {"type":"Field","id":"/Policy/Claims/ClaimId","name":"ClaimId","Field":{"fieldType":
             {"type":"StringType"}}},
             {"type":"Field","id":"/Policy/Claims/Amount","name":"Amount","Field":{"fieldType":
             {"type":"NumberType","NumberType":{"maxFractionalDigits":2,"minFractionalDigits":2}}}},
             {"type":"Field","id":"/Policy/Claims/Status","name":"Status","Field":{"fieldType":
             {"type":"EnumerationType","EnumerationType":{"values":[{"value":"OPEN"},{"value":"APPROVED"},{"value":"REJECTED"}]}}}}],"indexFieldName":"ClaimId"}},
             {"type":"Rule","id":"/Policy/AmountMissing","name":"AmountMissing","Rule":{"errorEntityRelPath":"../Claims/Amount","errorCode":"AMOUNT_MISSING","errorCondition":"FieldNotFilled(Claims/Amount)","severity":"ERROR","errorMessage":[{"locale":"en_US","text":"A recorded claim needs an amount."}]}}]}}]}}}
        """.trimIndent()
    }
}

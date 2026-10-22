package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A completed report names the two iteration codes this checker does not reproduce, so a consumer who sees the
 * eight iteration codes it does report cannot read their absence as "iteration checked".
 *
 * Agents hit `MVK_NEG_CONDITION_IN_ITERATION` on a first attempt in several of our evaluations
 * (`eval/RESULTS.md`), which is why the generic "every other required family" gap is not disclosure enough
 * (MVK-LEDGER, re-opened 2026-10-22). The iterating rule is the control that the checker stays silent on a
 * model A12 refuses under that code, rather than the gap describing a shape nobody reaches.
 */
class IterationValidityGapTest {

    @Test
    fun aCompletedReportNamesTheIterationCodesItLeavesToA12() {
        val report = checkRead(ModelSource(UNGUARDED_NEGATIVE))

        assertTrue(report.checksCompleted.isNotEmpty(), "the gap belongs to a run that completed")
        assertEquals(emptyList(), report.findings.map { it.code },
            "this checker does not reproduce the law, so the model A12 refuses draws no finding here")
        val named = report.gaps.filter {
            it.at == null && "MVK_NEG_CONDITION_IN_ITERATION" in it.reason &&
                "MVK_PARALLEL_ITERATION_INVALID" in it.reason
        }
        assertEquals(1, named.size, report.gaps.toString())
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

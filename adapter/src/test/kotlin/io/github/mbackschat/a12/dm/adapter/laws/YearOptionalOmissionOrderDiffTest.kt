package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.modelkit.DatePrecision
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * Which check a year-optional Date meets first, the acceptance of a12-kernel-lean SPEC-2026-10-26-20
 * ([KERNEL-FINDINGS KF640]). A `0000` year meets the year-optional omission check before its month is tested,
 * so `0000-13-00` draws `datumFalsch` with the omission text, while a stated year's impossible month draws the
 * format failure. The codes are three-engine agreed; the texts are A12's, on both evidence strategies.
 *
 * Own domain (an insurance incident).
 */
class YearOptionalOmissionOrderDiffTest {

    private val model = DocumentModelDraft.named("policy-incident-date").locale("en_US")
        .rootGroup("Policy") { p -> p.stringField("Ref") { }
            .dateField("IncidentOn") { it.format("yyyy-MM-dd").datePrecision(DatePrecision.YEAR_OPTIONAL) } }
        .build().toJson()

    @Test
    fun theOmissionCheckAnswersBeforeTheMonthIsTested() {
        val values = listOf("0000-13-00", "0000-06-15", "2024-13-00", "0000-00-00", "2024-06-00")
        val dynamicEngine = RuntimeLaws.kernelEngine(model)
        val staticEngine = RuntimeLaws.kernelJavaEngine(model)
        val kernel = values.associateWith { v ->
            dynamicEngine.validationMessages(dynamicEngine.instance(incident(v)), null, Locale.US).map { "${it.errorCode}|${it.errorText}" } }
        val static = values.associateWith { v ->
            staticEngine.validationMessages(staticEngine.instance(incident(v)), null, Locale.US).map { "${it.errorCode}|${it.errorText}" } }
        val interpreted = values.associateWith { v -> DocumentDifferential.interpreterMessages(model, incident(v)).map { it.code } }
        assertEquals(kernel, static, "static Java")
        assertEquals(kernel.mapValues { (_, messages) -> messages.map { it.substringBefore('|') } }, interpreted, "the interpreter")
        val yearOmitted = listOf("datumFalsch|Since the year was specified with '0000', both the day and month must be '00'.")
        assertEquals(linkedMapOf(
            "0000-13-00" to yearOmitted,
            "0000-06-15" to yearOmitted,
            "2024-13-00" to listOf("datumFormatFalsch|Only dates in the format YYYY-MM-DD are allowed. '13' is not a valid month."),
            "0000-00-00" to emptyList(),
            "2024-06-00" to emptyList(),
        ), kernel)
    }

    private fun incident(value: String) = """{"Policy":{"Ref":"P","IncidentOn":"$value"}}"""
}

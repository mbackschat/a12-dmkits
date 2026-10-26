package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

/**
 * The interpreter-against-A12 differential over one probe request (`:adapter:probeDifferential`), the check every
 * "reconciled" in an a12-kernel-lean letter rests on. The fixture's clock decides one message, so an agreement that
 * dropped the clock on both sides would count both claims late, not one.
 *
 * Own domain: a policy whose claims are flagged when large or reported before the evaluation day.
 */
class ProbeRequestDifferentialTest {

    @TempDir lateinit var dir: Path

    private val bound = BoundModel.ofJson(DocumentModelDraft.named("claims-review").locale("en_US")
        .rootGroup("Policy") { p -> p
            .numberField("Total") { it.fractionDigits(0) }
            .group("Claims") { c -> c.repeatable(5)
                .numberField("Amount") { it.fractionDigits(0) }
                .dateField("ReportedOn") { } } }
        .build().toJson())
    private val claims = bound.group("/Policy/Claims")
    private val amount = bound.num("/Policy/Claims/Amount")
    private val reportedOn = bound.date("/Policy/Claims/ReportedOn")

    private val model = bound.addAll(
        listOf(
            bound.rule(claims).name("LargeClaim").errorField(amount.field()).`when`(amount.gt(1000))
                .code("LARGE").severity(Severity.ERROR).message(Dsl.en("The claim needs a second review.")).build(),
            bound.rule(claims).name("LateClaim").errorField(reportedOn.field())
                .`when`(reportedOn.filled().and(reportedOn.before(Dsl.today())))
                .code("LATE").severity(Severity.ERROR).message(Dsl.en("The claim was reported late.")).build(),
        ),
        listOf(bound.computation(bound.num("/Policy/Total")).name("TotalComputation")
            .calc(Dsl.sum(bound.flatten("/Policy/Claims*/Amount"))).message(Dsl.en("Total computed.")).build()),
    ).toJson()

    private val document = """{"Policy":{"Total":0,"Claims":[
        {"Amount":1500,"ReportedOn":"2026-01-10"},{"Amount":200,"ReportedOn":"2026-01-20"}]}}"""

    private fun request(extra: String = "", observe: String = """["validateFull","validatePart"]"""): Path {
        Files.writeString(dir.resolve("model.json"), model)
        return Files.writeString(dir.resolve("request.json"), """{"model":"model.json","clock":"2026-01-15T09:00:00Z",
            "observe":$observe,$extra
            "rows":[{"name":"two claims","documentInline":$document,"relevant":[{"path":"/Policy/Claims","reps":[1,0]}]}]}""")
    }

    @Test
    fun everyCallOfARowAgreesOnTheClockedRequest() {
        val report = ProbeRequestDifferential.compare(request())
        assertEquals(listOf(
            // LARGE and LATE on claim 1, and the message A12 raises for the computed Total.
            RowVerdict.Agree("two claims", Call.FULL, messages = 3, outcomes = 1, operandErrors = 0),
            // A partial call publishes messages only, so its other channels are not compared rather than empty.
            RowVerdict.Agree("two claims", Call.PARTIAL, messages = 2, outcomes = null, operandErrors = null),
        ), report.rows)
        assertTrue(report.agrees)
    }

    @Test
    fun aDisagreementNamesEachSidesOwnLinesPerChannel() {
        val shared = MessageSignature.fields("LARGE", "VALUE_ERROR", "/Policy/Claims/Amount", listOf(), listOf())
        val kernelOnly = MessageSignature.fields("LATE", "VALUE_ERROR", "/Policy/Claims/ReportedOn", listOf(), listOf())
        val kernel = DocumentObservation(listOf(shared, kernelOnly), mapOf("Total" to "VALUE|1700"), listOf(), listOf())
        val interpreter = kernel.copy(messages = listOf(shared), outcomes = mapOf("Total" to "VALUE|1500"))
        assertEquals(RowVerdict.Agree("r", Call.FULL, messages = 2, outcomes = 1, operandErrors = 0),
            ProbeRequestDifferential.verdict("r", kernel, kernel))
        assertEquals(RowVerdict.Differ("r", Call.FULL, listOf(
            "messages K> $kernelOnly",
            "outcomes K> Total|VALUE|1700",
            "outcomes I> Total|VALUE|1500",
        )), ProbeRequestDifferential.verdict("r", kernel, interpreter))
    }

    @Test
    fun aRequestBindingCustomConditionsIsRefusedBeforeAnythingRuns() {
        val refusal = assertThrows<IllegalArgumentException> {
            ProbeRequestDifferential.compare(request(extra = """"customConditions":{"Always":{"returns":true}},"""))
        }
        assertTrue("customConditions" in refusal.message!! && "kernelProbe" in refusal.message!!, refusal.message)
    }

    @Test
    fun anObservedChannelTheDifferentialDoesNotCompareIsNamed() {
        val report = ProbeRequestDifferential.compare(
            request(observe = """["validateFull","validatePart","mandatoryInformation"]"""))
        assertEquals(setOf(KernelRuntimeProbe.Observation.MANDATORY_INFORMATION), report.notCompared)
        assertTrue(report.render().contains("not compared: mandatoryInformation"), report.render())
    }
}

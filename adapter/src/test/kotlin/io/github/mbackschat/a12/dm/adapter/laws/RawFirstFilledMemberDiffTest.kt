package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.KernelConsistencyOracle
import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.core.ReferenceResolvers
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import io.github.mbackschat.a12.dm.rulekit.dsl.StringExpr
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A raw String (`noValueValidation`) as a member of a `FirstFilledValue` list, against the Kernel (a12-kernel-lean
 * SPEC-2026-10-24-16). A raw value is never interned for evaluation, and the list is the one place a model may read
 * it: a filled raw member is read there as the token `X` whatever its text, and an empty one is skipped. The
 * rival, "the member's text", is what a value read of any other String gives, and the rules comparing against
 * `"scan"` and against `"X"` separate the two in a condition as the computations do in a store.
 *
 * Own domain (a claim intake: an uploaded attachment's raw content beside a typed summary).
 */
class RawFirstFilledMemberDiffTest {

    private val skeleton = DocumentModelDraft.named("claim-intake").locale("en_US")
        .rootGroup("Claim") { c -> c
            .stringField("Attachment") { it.noValueValidation().lineBreaks() }
            .stringField("Summary") { }
            .stringField("Digest") { }
            .stringField("DigestSummaryFirst") { }
            .stringField("FirstPage") { }
            .stringField("FromUpload") { }
            .group("Upload") { u -> u.stringField("Scan") { it.noValueValidation().lineBreaks() } }
            .group("Pages") { p -> p.repeatable(3).stringField("Content") { it.noValueValidation().lineBreaks() } } }
        .build().toJson()

    private val bound = BoundModel.ofJson(skeleton)
    private val attachment = bound.field("/Claim/Attachment")
    private val summary = bound.field("/Claim/Summary")
    private val attachmentFirst = Dsl.firstFilledString(attachment, summary)

    private fun computing(target: String, value: StringExpr) =
        bound.computation(bound.str("/Claim/$target")).name("${target}Computation").calc(value)
            .message(Dsl.en("Digest computed.")).build()

    private fun rule(code: String, compared: String) = bound.rule(bound.group("/Claim")).name("${code.lowercase()}_rule")
        .errorField(bound.field("/Claim/Summary")).`when`(bound.str("/Claim/Summary").filled().and(attachmentFirst.eq(compared)))
        .code(code).severity(Severity.ERROR).message(Dsl.en("Check the summary.")).build()

    private val model = bound.addAll(
        listOf(rule("READS_THE_TEXT", "scan"), rule("READS_THE_TOKEN", "X")),
        listOf(
            computing("Digest", attachmentFirst),
            computing("DigestSummaryFirst", Dsl.firstFilledString(summary, attachment)),
            computing("FirstPage", Dsl.firstFilledString(bound.flatten("/Claim/Pages*/Content"))),
            computing("FromUpload", Dsl.firstFilledString(bound.firstFilledGroup("/Claim/Upload"))),
        ),
    ).toJson()

    private fun claim(attachment: String?, summary: String?, page: String? = null, scan: String? = null) =
        """{"Claim":{""" + listOfNotNull(
            attachment?.let { """"Attachment":${json(it)}""" },
            summary?.let { """"Summary":${json(it)}""" },
            """"Digest":"seed","DigestSummaryFirst":"seed","FirstPage":"seed","FromUpload":"seed"""",
            scan?.let { """"Upload":{"Scan":${json(it)}}""" },
            page?.let { """"Pages":[{"Content":${json(it)}}]""" },
        ).joinToString(",") + "}}"

    private fun json(text: String) = "\"" + text.replace("\n", "\\n") + "\""

    private val documents = linkedMapOf(
        "a scanned attachment beside a summary" to claim("scan", "brief", page = "page one", scan = "image"),
        "an attachment without a summary" to claim("scan", null),
        "a summary alone" to claim(null, "brief"),
        "an attachment of one space" to claim(" ", "brief", page = " ", scan = " "),
        "an attachment that is literally X" to claim("X", "brief"),
        "a multi-line attachment" to claim("line one\nline two", "brief"),
        "an empty attachment" to claim("", "brief", page = "", scan = ""),
    )

    @Test
    fun a12AndTheCheckerAdmitTheCarrier() {
        assertEquals(emptyList<String>(), KernelConsistencyOracle().checkConsistency(model, ReferenceResolvers.selfContained())
            .filter { it.severity().name == "ERROR" }.map { it.message() })
        assertEquals(emptyList<String>(), check(ModelSource(model)).findings.filter { it.justifiesRejection }.map { it.code })
    }

    @Test
    fun allThreeEnginesAgree() {
        DocumentDifferential.assertAgree(model, documents)
    }

    /** What the dynamic Kernel stores and fires, so agreement cannot pass on a reading both engines share wrongly. */
    @Test
    fun aFilledRawMemberReadsAsTheTokenX() {
        val observed = documents.mapValues { (_, json) ->
            val k = DocumentDifferential.kernel(model, json, dynamic = true)
            listOf("Digest", "DigestSummaryFirst", "FirstPage", "FromUpload").map { k.outcomes[it] ?: "absent" } +
                k.messages.map { it.code }.filter { it.startsWith("READS_") }.sorted()
        }
        assertEquals(linkedMapOf(
            "a scanned attachment beside a summary" to listOf("VALUE|X", "VALUE|brief", "VALUE|X", "VALUE|X", "READS_THE_TOKEN"),
            "an attachment without a summary" to listOf("VALUE|X", "VALUE|X", "CLEARED", "CLEARED"),
            "a summary alone" to listOf("VALUE|brief", "VALUE|brief", "CLEARED", "CLEARED"),
            "an attachment of one space" to listOf("VALUE|X", "VALUE|brief", "VALUE|X", "VALUE|X", "READS_THE_TOKEN"),
            "an attachment that is literally X" to listOf("VALUE|X", "VALUE|brief", "CLEARED", "CLEARED", "READS_THE_TOKEN"),
            "a multi-line attachment" to listOf("VALUE|X", "VALUE|brief", "CLEARED", "CLEARED", "READS_THE_TOKEN"),
            "an empty attachment" to listOf("VALUE|brief", "VALUE|brief", "CLEARED", "CLEARED"),
        ), observed)
    }
}

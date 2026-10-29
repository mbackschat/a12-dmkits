package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.mbackschat.a12.dm.adapter.KernelConsistencyOracle
import io.github.mbackschat.a12.dm.core.ReferenceResolvers
import io.github.mbackschat.a12.dm.core.Severity
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A declared length against the range A12's metamodel admits for it (KERNEL-FINDINGS KF556), against the portable
 * checker. On a String, Custom or Number type, `minLength` and `maxLength` must lie between 1 and 99,999; A12
 * answers a member outside that with one of three codeless sentences, once per sentence and field, and judges
 * nothing else with it, a hint list's lengths included. `-1`, "no bound", is admitted on the String and Custom
 * carriers only, and a field deleted from the default variant draws nothing.
 *
 * Own domain (a support ticket's description, summary, reference and effort), built by editing the drafted model's
 * JSON, because the typed builder refuses an inverted pair outright.
 */
class CheckerLengthLimitAgreementTest {

    private data class Row(val field: String, val members: Map<String, Any>, val deleted: Boolean = false)

    private fun string(vararg members: Pair<String, Any>) = Row(DESCRIPTION, mapOf(*members))
    private fun custom(vararg members: Pair<String, Any>) = Row(REFERENCE, mapOf(*members))
    private fun number(vararg members: Pair<String, Any>) = Row(EFFORT, mapOf(*members))

    private val rows = linkedMapOf(
        "string max at the limit" to string(MAX to 99_999),
        "string min at the limit" to string(MIN to 99_999),
        "string max above" to string(MAX to 100_000),
        "string min above" to string(MIN to 100_000),
        "string both above" to string(MIN to 100_000, MAX to 100_000),
        "string min above an ordinary max" to string(MIN to 100_000, MAX to 50),
        "string both above, inverted" to string(MIN to 100_001, MAX to 100_000),
        "string line breaks, max above" to string(BREAKS to true, MAX to 100_000),
        "string unvalidated, max above" to string(NO_VAL to true, BREAKS to true, MAX to 100_000),
        "string unvalidated, min above" to string(NO_VAL to true, BREAKS to true, MIN to 100_000),
        "string unvalidated, min zero" to string(NO_VAL to true, BREAKS to true, MIN to 0),
        "string unvalidated with a pattern, max above" to
            string(NO_VAL to true, BREAKS to true, MAX to 100_000, "pattern" to "[A-Z]+"),
        "string min zero" to string(MIN to 0),
        "string max zero" to string(MAX to 0),
        "string sentinels" to string(MIN to -1, MAX to -1),
        "string min signed" to string(MIN to -2),
        "string max signed" to string(MAX to -2),
        "string both zero" to string(MIN to 0, MAX to 0),
        "string min zero, max above" to string(MIN to 0, MAX to 100_000),
        "string min signed, max above" to string(MIN to -2, MAX to 100_000),
        "string min signed, max zero" to string(MIN to -2, MAX to 0),
        "string min above a zero max" to string(MIN to 5, MAX to 0),
        "string line breaks, max zero" to string(BREAKS to true, MAX to 0),
        "string max zero beside a hint" to string(MAX to 0, HINTS to listOf("Basic")),
        "string max signed beside a hint" to string(MAX to -2, HINTS to listOf("Basic")),
        "string max above beside a hint" to string(MAX to 100_000, HINTS to listOf("Basic")),
        "string min above beside a hint" to string(MIN to 100_000, HINTS to listOf("abc")),
        "string max sentinel beside a hint" to string(MAX to -1, HINTS to listOf("Basic")),
        "string max three beside a hint" to string(MAX to 3, HINTS to listOf("Basic")),
        "string deleted, max above" to Row(DESCRIPTION, mapOf(MAX to 100_000), deleted = true),
        "string deleted, inverted" to Row(DESCRIPTION, mapOf(MIN to 5, MAX to 3), deleted = true),
        "custom max above" to custom(MAX to 100_000),
        "custom min above" to custom(MIN to 100_000),
        "custom max at the limit" to custom(MAX to 99_999),
        "custom min above an ordinary max" to custom(MIN to 100_000, MAX to 50),
        "custom min zero" to custom(MIN to 0),
        "custom max sentinel" to custom(MAX to -1),
        "custom max signed" to custom(MAX to -2),
        "custom min above a zero max" to custom(MIN to 5, MAX to 0),
        "custom deleted, max above" to Row(REFERENCE, mapOf(MAX to 100_000), deleted = true),
        "custom deleted, inverted" to Row(REFERENCE, mapOf(MIN to 5, MAX to 3), deleted = true),
        "number max above" to number(MAX to 100_000),
        "number min above" to number(MIN to 100_000),
        "number min above an ordinary max" to number(MIN to 100_000, MAX to 5),
        "number min zero" to number(MIN to 0),
        "number max sentinel" to number(MAX to -1),
        "number min sentinel" to number(MIN to -1),
        "number max signed" to number(MAX to -2),
        "number min above a zero max" to number(MIN to 5, MAX to 0),
        "number max zero beside fractional digits" to number(MAX to 0, "minFractionalDigits" to 2, "maxFractionalDigits" to 2),
        "number deleted, max above" to Row(EFFORT, mapOf(MAX to 100_000), deleted = true),
    )

    /** Every row's classes, with their multiplicity; A12's order is not claimed. */
    @Test
    fun everyRowAgrees() {
        val wrong = (rows.map { (id, row) -> id to model(listOf(row)) } + ("two strings above" to twoFields) +
            ("above beside another field's inverted range" to besideInverted))
            .mapNotNull { (id, json) -> kernel(json).let { a12 -> if (a12 == ours(json)) null else "$id: A12 $a12, ours ${ours(json)}" } }
        assertEquals(emptyList<String>(), wrong)
    }

    /** The three sentences and their pre-emption, pinned, so the agreement above is not agreement on nothing. */
    @Test
    fun theSentencesAreObservable() {
        fun a12(id: String) = kernel(model(listOf(rows.getValue(id))))
        assertEquals(listOf(ABOVE), a12("string min above an ordinary max"))
        assertEquals(listOf(NOT_POSITIVE), a12("string unvalidated, min zero"))
        assertEquals(listOf(ABOVE, SIGNED), a12("string min signed, max above"))
        assertEquals(listOf(NOT_POSITIVE), a12("string both zero"))
        assertEquals(listOf(NOT_POSITIVE), a12("custom min above a zero max"))
        assertEquals(listOf(SIGNED), a12("number max sentinel"))
        assertEquals(listOf(SIGNED), a12("number min sentinel"))
        assertEquals(emptyList<String>(), a12("custom max sentinel"))
        assertEquals(emptyList<String>(), a12("number deleted, max above"))
        assertEquals(listOf("MVK_ERROR_MESSAGE_AND_PATTERN_INVALID", "MVK_STRING_TYPE_PARAMS_INCONSISTENT", ABOVE),
            a12("string unvalidated with a pattern, max above"))
        assertEquals(listOf("MVK_VALUE_TOO_LONG"), a12("string max three beside a hint"))
        assertEquals(listOf(NOT_POSITIVE), a12("string max zero beside a hint"))
        assertEquals(listOf(ABOVE), a12("string min above beside a hint"))
        assertEquals(listOf(ABOVE, ABOVE), kernel(twoFields))
        assertEquals(listOf("MVK_MIN_LENGTH_BIGGER_MAX_LENGTH", ABOVE), kernel(besideInverted))
    }

    private val twoFields: String get() = model(listOf(string(MAX to 100_000), Row(SUMMARY, mapOf(MIN to 100_000))))

    private val besideInverted: String get() = model(listOf(string(MAX to 100_000), Row(SUMMARY, mapOf(MIN to 5, MAX to 3))))

    private val oracle = KernelConsistencyOracle()
    private val mapper = ObjectMapper()

    private fun kernel(json: String): List<String> = oracle.checkConsistency(json, ReferenceResolvers.selfContained())
        .filter { it.severity() == Severity.ERROR }.map { notification ->
            val message = notification.message().trim()
            notificationClass(message)
        }.sorted()

    private fun ours(json: String): List<String> =
        rejectingCodes(json).sorted()

    private val base: String = DocumentModelDraft.named("ticket-length").locale("en_US")
        .rootGroup("Ticket") { g -> g.stringField(DESCRIPTION) { }.stringField(SUMMARY) { }
            .customField(REFERENCE) { it.type("TicketRef") }.numberField(EFFORT) { it.fractionDigits(0) } }
        .build().toJson()

    private fun model(rows: List<Row>): String {
        val root = mapper.readTree(base) as ObjectNode
        val elements = root.path("content").path("modelRoot").path("rootGroups")[0].path("Group").path("elements")
        for (row in rows) {
            val field = elements.first { it.path("name").asText() == row.field }.path("Field") as ObjectNode
            val type = field.path("fieldType") as ObjectNode
            val body = type.withObject("/" + type.path("type").asText())
            row.members.forEach { (member, value) ->
                when (value) {
                    is Int -> body.put(member, value)
                    is Boolean -> body.put(member, value)
                    is String -> body.put(member, value)
                    is List<*> -> body.putArray(member).addObject().put("locale", "en_US").putArray("values")
                        .also { values -> value.forEach { values.add(it as String) } }
                    else -> error(value)
                }
            }
            if (row.deleted) field.putObject("variantFilter").put("filterType", "ONLY").putArray("variants").add("premium")
        }
        return mapper.writeValueAsString(root)
    }

    private companion object {
        const val DESCRIPTION = "Description"
        const val SUMMARY = "Summary"
        const val REFERENCE = "Reference"
        const val EFFORT = "Effort"
        const val MIN = "minLength"
        const val MAX = "maxLength"
        const val BREAKS = "lineBreaksPermitted"
        const val NO_VAL = "noValueValidation"
        const val HINTS = "hintList"
        const val ABOVE = "RK_MEMBER_ABOVE_TECHNICAL_LIMIT"
        const val NOT_POSITIVE = "RK_MEMBER_NOT_POSITIVE"
        const val SIGNED = "RK_MEMBER_SIGNED"
    }
}

package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.mbackschat.a12.dm.adapter.KernelConsistencyOracle
import io.github.mbackschat.a12.dm.core.ReferenceResolvers
import io.github.mbackschat.a12.dm.core.Severity
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A Number type's digit counts against the ranges A12's metamodel admits for them (KERNEL-FINDINGS KF559), against
 * the portable checker. `maxIntegerDigits` lies between 1 and 15, the two fractional counts between 0 and 14 with
 * `-1` as "not declared", and a value outside draws one of the codeless sentences [KF556]'s lengths draw, or the
 * `15` and `14` maxima, once per sentence and field across lengths and digits alike. An out-of-range count is no
 * bound for a law that reads it, while a law reading only in-range members still answers; an amount's derived
 * integer count is judged like a declared one.
 *
 * Own domain (a support ticket's effort), built by editing the drafted model's JSON, because the typed builder
 * refuses most of these counts outright.
 */
class CheckerDigitMemberRangeAgreementTest {

    private data class Row(val members: Map<String, Any>, val deleted: Boolean = false)

    private fun number(vararg members: Pair<String, Any>) = Row(mapOf(*members))

    private val rows = linkedMapOf(
        "integer at its range's ends" to number(INT to 1),
        "integer at the budget" to number(INT to 15),
        "integer above the budget" to number(INT to 16),
        "integer far above the budget" to number(INT to 100_000),
        "integer zero" to number(INT to 0),
        "integer undefined" to number(INT to -1),
        "integer signed" to number(INT to -2),
        "fractional maximum at the scale cap" to number(MAX_FRACT to 14),
        "fractional pair at the scale cap" to number(MIN_FRACT to 14, MAX_FRACT to 14),
        "fractional maximum above the scale cap" to number(MAX_FRACT to 15),
        "fractional maximum far above" to number(MAX_FRACT to 100_000),
        "fractional minimum above the scale cap" to number(MIN_FRACT to 15),
        "fractional pair above the scale cap" to number(MIN_FRACT to 15, MAX_FRACT to 15),
        "fractional maximum above beside an ordinary minimum" to number(MIN_FRACT to 2, MAX_FRACT to 15),
        "fractional zero" to number(MIN_FRACT to 0, MAX_FRACT to 0),
        "fractional pair undefined" to number(MIN_FRACT to -1, MAX_FRACT to -1),
        "fractional minimum signed" to number(MIN_FRACT to -2),
        "fractional maximum signed" to number(MAX_FRACT to -2),
        "fractional maximum signed beside a minimum" to number(MIN_FRACT to 3, MAX_FRACT to -2),
        "fractional pair signed" to number(MIN_FRACT to -2, MAX_FRACT to -2),
        "fractional minimum signed, maximum undefined" to number(MIN_FRACT to -2, MAX_FRACT to -1),
        "integer above beside an in-range fractional part" to number(INT to 16, MIN_FRACT to 2, MAX_FRACT to 2),
        "integer zero beside a fractional part" to number(INT to 0, MIN_FRACT to 2, MAX_FRACT to 2),
        "arity over a fractional minimum above the cap" to number(INT to 14, MIN_FRACT to 15, MAX_FRACT to 15),
        "integer and fractional maximum both signed" to number(INT to -2, MAX_FRACT to -2),
        "integer above, fractional maximum above" to number(INT to 16, MAX_FRACT to 15),
        "integer and length both signed" to number(INT to -2, MAX_LENGTH to -2),
        "integer and length both zero" to number(INT to 0, MAX_LENGTH to 0),
        "integer above beside a length over the budget" to number(INT to 16, MAX_LENGTH to 17),
        "fractional maximum above beside a length over the budget" to number(MAX_FRACT to 15, MAX_LENGTH to 18),
        "fractional minimum above beside a length over the budget" to
            number(MIN_FRACT to 15, MAX_FRACT to 2, MAX_LENGTH to 18),
        "fractional minimum signed beside a length over the budget" to
            number(MIN_FRACT to -2, MAX_FRACT to 2, MAX_LENGTH to 18),
        "fractional minimum signed beside a zero maximum and a length over" to
            number(MIN_FRACT to -2, MAX_FRACT to 0, MAX_LENGTH to 17),
        "fractional minimum above beside a zero maximum and a length over" to
            number(MIN_FRACT to 15, MAX_FRACT to 0, MAX_LENGTH to 17),
        "fractional minimum above beside a zero maximum and a minimum length over" to
            number(MIN_FRACT to 15, MAX_FRACT to 0, MIN_LENGTH to 17),
        "fractional maximum above beside a minimum length over" to number(MAX_FRACT to 15, MIN_LENGTH to 18),
        "fractional minimum above beside a length too small" to number(MIN_FRACT to 15, MAX_FRACT to 15, MAX_LENGTH to 5),
        "fractional minimum signed beside a length too small" to number(MIN_FRACT to -2, MAX_FRACT to 2, MAX_LENGTH to 1),
        "fractional maximum above beside a length too small" to number(MIN_FRACT to 2, MAX_FRACT to 15, MAX_LENGTH to 2),
        "amount with its scale undefined" to number(TRAIT to "amount", MAX_FRACT to -1),
        "amount with its integer count zero" to number(TRAIT to "amount", INT to 0, MAX_FRACT to 2),
        "amount with its integer count above" to number(TRAIT to "amount", INT to 16, MAX_FRACT to 2),
        "amount whose scale derives a zero count" to number(TRAIT to "amount", MAX_FRACT to 15),
        "amount whose scale derives a signed count" to number(TRAIT to "amount", MAX_FRACT to 17),
        "amount whose signed scale derives a count above" to number(TRAIT to "amount", MAX_FRACT to -2),
        "percent with its integer count above" to number(TRAIT to "percent", INT to 16),
        "signless at the budget" to number(INT to 15, MAX_LENGTH to 15, "positivesOnly" to true),
        "deleted, integer above" to Row(mapOf(INT to 16), deleted = true),
        "deleted, fractional maximum above" to Row(mapOf(MAX_FRACT to 15), deleted = true),
        "deleted, integer signed" to Row(mapOf(INT to -2), deleted = true),
    )

    /** Every row's classes, with their multiplicity; A12's order is not claimed. */
    @Test
    fun everyRowAgrees() {
        val wrong = rows.map { (id, row) -> id to model(row) }
            .mapNotNull { (id, json) -> kernel(json).let { a12 -> if (a12 == ours(json)) null else "$id: A12 $a12, ours ${ours(json)}" } }
        assertEquals(emptyList<String>(), wrong)
    }

    /** The sentences, their multiplicity and their pre-emption, pinned, so the agreement above is not agreement on nothing. */
    @Test
    fun theSentencesAreObservable() {
        fun a12(id: String) = kernel(model(rows.getValue(id)))
        assertEquals(listOf(ABOVE_BUDGET), a12("integer above beside an in-range fractional part"))
        assertEquals(listOf(SIGNED), a12("integer undefined"))
        assertEquals(emptyList<String>(), a12("fractional pair undefined").filter { it.startsWith("RK_") })
        assertEquals(listOf(ABOVE_SCALE), a12("arity over a fractional minimum above the cap"))
        assertEquals(listOf(ABOVE_BUDGET, ABOVE_SCALE), a12("integer above, fractional maximum above"))
        assertEquals(listOf(SIGNED), a12("integer and length both signed"))
        assertEquals(listOf("MVK_MAX_LENGTH_TOO_BIG", ABOVE_BUDGET), a12("integer above beside a length over the budget"))
        assertEquals(listOf(ABOVE_SCALE), a12("fractional maximum above beside a length over the budget"))
        assertEquals(listOf("MVK_MAX_LENGTH_TOO_SMALL", ABOVE_SCALE), a12("fractional maximum above beside a length too small"))
        assertEquals(listOf(ABOVE_SCALE, NOT_POSITIVE), a12("amount whose scale derives a zero count"))
        assertEquals(emptyList<String>(), a12("deleted, integer above"))
    }

    /**
     * The other numeric members A12's collector writes into its metamodel document are a group's repetitions and a
     * field's row count (`CodeGenValidationDataCollector`, `FieldDataCollector`), so they are the members that could
     * draw the 15 or 14 sentence without being a digit count. None does, however large: which is what lets a member
     * class name the sentence alone.
     */
    @Test
    fun aRepetitionCountDrawsNoDigitSentence() {
        val counts = listOf(listOf(16), listOf(100000), listOf(1000000), listOf(16, 16), listOf(100000, 100000))
        val observed = counts.associateWith { repeated(it).let { json -> kernel(json) to ours(json) } }
        assertEquals(counts.associateWith { emptyList<String>() to emptyList<String>() }, observed, "(A12, ours)")
    }

    private fun repeated(counts: List<Int>): String {
        val root = mapper.readTree(base) as ObjectNode
        var elements = root.path("content").path("modelRoot").path("rootGroups")[0].path("Group").path("elements")
            as ArrayNode
        var path = "/Ticket"
        counts.forEachIndexed { level, count ->
            val name = "Visit$level"
            path += "/$name"
            val group = elements.addObject().put("type", "Group").put("id", path).put("name", name).putObject("Group")
                .put("repeatability", count)
            elements = group.putArray("elements")
        }
        elements.addObject().put("type", "Field").put("id", "$path/Note").put("name", "Note").putObject("Field")
            .putObject("fieldType").put("type", "StringType")
        return mapper.writeValueAsString(root)
    }

    private val oracle = KernelConsistencyOracle()
    private val mapper = ObjectMapper()

    private fun kernel(json: String): List<String> = oracle.checkConsistency(json, ReferenceResolvers.selfContained())
        .filter { it.severity() == Severity.ERROR }.map { notification ->
            val message = notification.message().trim()
            notificationClass(message)
        }.sorted()

    private fun ours(json: String): List<String> =
        rejectingCodes(json).sorted()

    private val base: String = DocumentModelDraft.named("ticket-digits").locale("en_US")
        .rootGroup("Ticket") { g -> g.stringField("Summary") { }.numberField(EFFORT) { it.fractionDigits(0) } }
        .build().toJson()

    private fun model(row: Row): String {
        val root = mapper.readTree(base) as ObjectNode
        val elements = root.path("content").path("modelRoot").path("rootGroups")[0].path("Group").path("elements")
        val field = elements.first { it.path("name").asText() == EFFORT }.path("Field") as ObjectNode
        val type = field.path("fieldType") as ObjectNode
        val body = type.withObject("/" + type.path("type").asText())
        row.members.forEach { (member, value) ->
            when (value) {
                is Int -> body.put(member, value)
                is Boolean -> body.put(member, value)
                is String -> body.put(member, value)
                else -> error(value)
            }
        }
        if (row.deleted) field.putObject("variantFilter").put("filterType", "ONLY").putArray("variants").add("premium")
        return mapper.writeValueAsString(root)
    }

    private companion object {
        const val EFFORT = "Effort"
        const val INT = "maxIntegerDigits"
        const val MIN_FRACT = "minFractionalDigits"
        const val MAX_FRACT = "maxFractionalDigits"
        const val MAX_LENGTH = "maxLength"
        const val MIN_LENGTH = "minLength"
        const val TRAIT = "trait"
        const val ABOVE_BUDGET = "RK_MEMBER_ABOVE_DIGIT_BUDGET"
        const val ABOVE_SCALE = "RK_MEMBER_ABOVE_SCALE_LIMIT"
        const val NOT_POSITIVE = "RK_MEMBER_NOT_POSITIVE"
        const val SIGNED = "RK_MEMBER_SIGNED"
    }
}

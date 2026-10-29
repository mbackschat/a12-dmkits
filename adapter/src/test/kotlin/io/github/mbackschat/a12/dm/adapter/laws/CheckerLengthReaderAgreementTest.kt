package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The three rule-operand readers that judge a String field by its declared length, each given a length outside the
 * 1 to 99,999 range A12's metamodel admits (KERNEL-FINDINGS KF556), against the portable checker: a String converted
 * by `FieldValueAsNumber`, a String standing as a `Date` component, and a String index key matched against a
 * constant. Unlike the type's own laws, these readers still judge with a member outside the range, so A12 can report
 * the range sentence AND the reader's class, and they do not read it alike: the conversion takes a zero or signed
 * maximum as one of at most 15 and only `-1`, A12's "no bound", as absent, while the index key takes a positive member
 * as written, past the limit too, and any other as no bound. The `Date` component wants exactly its entity's width,
 * so no value outside the range separates readings there. Each reader's in-range control is its first row.
 *
 * Own domain (a policy's holder, start date, numeric code, day and tag amounts), built by editing the drafted model's
 * JSON, because the typed builder refuses a length outside the range.
 */
class CheckerLengthReaderAgreementTest {

    private val mapper = ObjectMapper()
    private val validator = RuleValidator()

    private val model = DocumentModelDraft.named("policy-length-readers").locale("en_US")
        .rootGroup("Policy") { p -> p
            .stringField("Holder") { }
            .dateField("StartOn") { d -> d.format("yyyy-MM-dd") }
            .stringField("Code") { s -> s.pattern("[0-9]+", "digits").maxLength(15) }
            .stringField("Day") { s -> s.pattern("[0-9]+", "digits").maxLength(2) }
            .group("Tags") { g -> g.repeatable(5).indexField("K")
                .stringField("K") { s -> s.minLength(2).maxLength(3) }
                .numberField("Amount") { n -> n.fractionDigits(2) } } }
        .build().toJson()

    private data class Row(val condition: String, val field: List<String>, val member: String, val value: Int?)

    private fun conversion(value: Int?) = Row("FieldValueAsNumber(Code) > 0", listOf("Code"), MAX, value)
    private fun dateComponent(value: Int?) = Row("Date(Day, \"01\", \"2024\") > [StartOn]", listOf("Day"), MAX, value)
    private fun indexKey(member: String, value: Int) =
        Row("[Tags/Amount For \"AB\"] > 0", listOf("Tags", "K"), member, value)

    private val rows = linkedMapOf(
        "conversion, max 15" to conversion(15),
        "conversion, max 16" to conversion(16),
        "conversion, max above the limit" to conversion(100_000),
        "conversion, max zero" to conversion(0),
        "conversion, max no bound" to conversion(-1),
        "conversion, max signed" to conversion(-5),
        "conversion, max absent" to conversion(null),
        "date component, max 2" to dateComponent(2),
        "date component, max 3" to dateComponent(3),
        "date component, max above the limit" to dateComponent(100_000),
        "date component, max zero" to dateComponent(0),
        "date component, max no bound" to dateComponent(-1),
        "date component, max signed" to dateComponent(-5),
        "date component, max absent" to dateComponent(null),
        "index key, min 2" to indexKey(MIN, 2),
        "index key, min 3" to indexKey(MIN, 3),
        "index key, min above the limit" to indexKey(MIN, 100_000),
        "index key, min zero" to indexKey(MIN, 0),
        "index key, min no bound" to indexKey(MIN, -1),
        "index key, min signed" to indexKey(MIN, -5),
        "index key, max 1" to indexKey(MAX, 1),
        "index key, max above the limit" to indexKey(MAX, 100_000),
        "index key, max zero" to indexKey(MAX, 0),
        "index key, max no bound" to indexKey(MAX, -1),
        "index key, max signed" to indexKey(MAX, -5),
    )

    private fun json(row: Row): String {
        val draft = DraftRule("/Policy", "ProbeRule", "../Holder", "FieldFilled(Holder) And ${row.condition}", "PROBE",
            "ERROR")
        val root = mapper.readTree(validator.inject(model, draft)) as ObjectNode
        var node = root.path("content").path("modelRoot").path("rootGroups")[0]
        for (name in row.field) node = node.path("Group").path("elements").first { it.path("name").asText() == name }
        val body = node.path("Field").path("fieldType").path("StringType") as ObjectNode
        if (row.value == null) body.remove(row.member) else body.put(row.member, row.value)
        return root.toString()
    }

    @Test
    fun theCheckerNamesA12sClassesBesideEveryLength() {
        val observed = rows.mapValues { (_, row) -> json(row).let { kernelModelClasses(it) to ourClasses(it) } }
        assertEquals(observed.mapValues { (_, verdict) -> verdict.first to verdict.first }, observed,
            "each row as (A12, ours)")
        // A12's own answer on the rows that separate reading a member as written from reading it as absent, so the
        // agreement cannot follow a drift on both sides.
        assertEquals(linkedMapOf(
            "conversion, max zero" to setOf(NOT_POSITIVE),
            "conversion, max no bound" to setOf(NO_CONVERT),
            "conversion, max signed" to setOf(SIGNED),
            "conversion, max absent" to setOf(NO_CONVERT),
            "date component, max zero" to setOf(NOT_POSITIVE, WRONG_DATE),
            "index key, min above the limit" to setOf(ABOVE_LIMIT, "MVK_INDEX_VALUE_INVALID"),
            "index key, max zero" to setOf(NOT_POSITIVE),
            "index key, max signed" to setOf(SIGNED),
        ), observed.filterKeys { it in PINNED }.mapValues { (_, verdict) -> verdict.first })
    }

    private fun ourClasses(json: String) =
        rejectingCodes(json).toSet()

    private companion object {
        const val MIN = "minLength"
        const val MAX = "maxLength"
        const val NO_CONVERT = "MVK_NO_CONVERT_IN_NUMBER"
        const val WRONG_DATE = "MVK_WRONG_DATE_FORMAT_FOR_OP"
        const val ABOVE_LIMIT = "RK_MEMBER_ABOVE_TECHNICAL_LIMIT"
        const val NOT_POSITIVE = "RK_MEMBER_NOT_POSITIVE"
        const val SIGNED = "RK_MEMBER_SIGNED"
        val PINNED = setOf("conversion, max zero", "conversion, max no bound", "conversion, max signed",
            "conversion, max absent", "date component, max zero", "index key, min above the limit",
            "index key, max zero", "index key, max signed")
    }
}

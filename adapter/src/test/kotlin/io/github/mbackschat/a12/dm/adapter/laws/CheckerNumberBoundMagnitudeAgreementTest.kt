package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.mbackschat.a12.dm.adapter.KernelConsistencyOracle
import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.core.ReferenceResolvers
import io.github.mbackschat.a12.dm.core.Severity
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A Number bound against the magnitude A12 derives from the declared fractional digits (KERNEL-FINDINGS KF582),
 * against the portable checker, every class of every row with its multiplicity.
 *
 * Each row is the discriminator of a rival reading: the minimum is judged by its absolute value and the maximum
 * signed; a bound is judged unrounded, so `999999999999999.4` exceeds the scale-0 limit it rounds to; `-1` and a
 * count outside its member range are counts like any other; neither the integer places nor the trait enter; and a
 * field the default variant deletes is still judged. The rows also hold the sentences beside the printed-bound
 * checks and the inversion, which they neither withhold nor are withheld by.
 *
 * Own domain (an insurance policy's premium), built by editing the drafted model's JSON, because the typed builder
 * refuses these bounds outright.
 */
class CheckerNumberBoundMagnitudeAgreementTest {

    private data class Row(val body: String, val deleted: Boolean = false)

    @Test
    fun theCheckerStatesA12sMagnitudeVerdictOnEveryRow() {
        val observed = ROWS.mapValues { (_, row) -> model(row).let { kernel(it) to ours(it) } }
        assertEquals(ROWS.mapValues { (id, _) -> EXPECTED.getValue(id).sorted().let { it to it } }, observed, "(A12, ours)")
    }

    /** A12 reads a definition's type as the field's, and the checker discloses that it reads none. */
    @Test
    fun aTypeDefinitionsBoundIsLeftToA12WithAGap() {
        val json = model(Row("""{"maxFractionalDigits":0,"maxValue":1e15}"""), typeDefinition = true)
        assertEquals(listOf(TOO_LONG, MAX), kernel(json))
        val report = check(ModelSource(json))
        assertEquals(emptyList<String>(), report.findings.filter { it.justifiesRejection }.map { it.code })
        assertTrue(report.gaps.any { "typeDefinitions" in it.reason }, "the silence is disclosed: ${report.gaps}")
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

    private val base: String = DocumentModelDraft.named("policy-premiums").locale("en_US")
        .rootGroup("Policy") { p -> p.stringField("Number") { } }.build().toJson()

    private fun model(row: Row, typeDefinition: Boolean = false): String {
        val root = mapper.readTree(base) as ObjectNode
        val elements = root.path("content").path("modelRoot").path("rootGroups")[0].path("Group").path("elements") as ArrayNode
        val field = elements.addObject().put("type", "Field").put("id", "/Policy/Premium").put("name", "Premium")
            .putObject("Field")
        val body = mapper.readTree(row.body) as ObjectNode
        if (typeDefinition) {
            (root.path("content") as ObjectNode).putArray("typeDefinitions").addObject().put("id", "PremiumType")
                .put("name", "PremiumType").put("revision", "1").putObject("fieldType").put("type", "NumberType")
                .set<ObjectNode>("NumberType", body)
            field.putObject("fieldType").put("type", "TypeDefType").putObject("TypeDefType")
                .put("typeDefinitionId", "PremiumType")
        } else {
            field.putObject("fieldType").put("type", "NumberType").set<ObjectNode>("NumberType", body)
        }
        if (row.deleted) field.putObject("variantFilter").put("filterType", "ONLY").putArray("variants").add("Broker")
        return mapper.writeValueAsString(root)
    }

    private companion object {
        const val MIN = "RK_NUMBER_MIN_VALUE_EXCEEDED"
        const val MAX = "RK_NUMBER_MAX_VALUE_EXCEEDED"
        const val TOO_LONG = "RK_BOUND_TOO_MANY_DIGITS"
        const val SIGNED_ZERO = "RK_BOUND_SIGNED_ZERO"
        const val ABOVE_SCALE = "RK_MEMBER_ABOVE_SCALE_LIMIT"
        const val SIGNED = "RK_MEMBER_SIGNED"
        const val INVERTED = "MVK_MIN_VALUE_BIGGER_THAN_MAX_VALUE"

        fun bounds(places: Int?, min: String?, max: String?, vararg extra: Pair<String, String>): Row = Row(
            (listOfNotNull(places?.let { "\"maxFractionalDigits\":$it" }, min?.let { "\"minValue\":$it" },
                max?.let { "\"maxValue\":$it" }) + extra.map { (k, v) -> "\"$k\":$v" }).joinToString(",", "{", "}"))

        val ROWS = linkedMapOf(
            "max 1e14 at scale 1" to bounds(1, null, "1e14"),
            "max 99999999999999.9 at scale 1, the limit itself" to bounds(1, null, "99999999999999.9"),
            "max 1e15 at scale 0" to bounds(0, null, "1e15"),
            "max 999999999999999 at scale 0, the limit itself" to bounds(0, null, "999999999999999"),
            "max 999999999999999.4 at scale 0, unrounded" to bounds(0, null, "999999999999999.4"),
            "min 1e15 at scale 0, by absolute value" to bounds(0, "1e15", null),
            "min -1e15 at scale 0" to bounds(0, "-1e15", null),
            "min -999999999999999 at scale 0, the limit itself" to bounds(0, "-999999999999999", null),
            "max -1e15 at scale 0, signed" to bounds(0, null, "-1e15"),
            "max 1e15, scale absent" to bounds(null, null, "1e15"),
            "max 1e15 at scale -1" to bounds(-1, null, "1e15"),
            "max 9999999999999990 at scale -1, the limit itself" to bounds(-1, null, "9999999999999990"),
            "max 9999999999999991 at scale -1" to bounds(-1, null, "9999999999999991"),
            "max 10 at scale 14" to bounds(14, null, "10"),
            "max 9.99999999999999 at scale 14, the limit itself" to bounds(14, null, "9.99999999999999"),
            "max 2 at scale 15, outside its range" to bounds(15, null, "2"),
            "max 0.5 at scale 15" to bounds(15, null, "0.5"),
            "max 1 at scale 20" to bounds(20, null, "1"),
            "max 1e15 at scale -2" to bounds(-2, null, "1e15"),
            "max 1e17 at scale -2" to bounds(-2, null, "1e17"),
            "max 1e6 at scale 2 beside 3 integer places" to bounds(2, null, "1e6", "maxIntegerDigits" to "3"),
            "max 1e13 at scale 2, an amount" to bounds(2, null, "1e13", "trait" to "\"amount\""),
            "max 9999999999999.99 at scale 2, an amount, the limit itself" to
                bounds(2, null, "9999999999999.99", "trait" to "\"amount\""),
            "max 1e15 at scale 0, deleted by the default variant" to bounds(0, null, "1e15").copy(deleted = true),
            "both bounds at scale 0" to bounds(0, "-1e15", "1e15"),
            "min 1e15 over max 1 at scale 0" to bounds(0, "1e15", "1"),
            "min 1e14 over max 1 at scale 1, beside the inversion" to bounds(1, "1e14", "1"),
            "max -0.4 beside min -1e15 at scale 0" to bounds(0, "-1e15", "-0.4"),
            "max 999999999999.9995 at scale 3" to bounds(3, null, "999999999999.9995"),
            "max 1e8 at scale 7" to bounds(7, null, "1e8"),
        )

        val EXPECTED: Map<String, List<String>> = mapOf(
            "max 1e14 at scale 1" to listOf(MAX),
            "max 99999999999999.9 at scale 1, the limit itself" to emptyList(),
            "max 1e15 at scale 0" to listOf(MAX, TOO_LONG),
            "max 999999999999999 at scale 0, the limit itself" to emptyList(),
            "max 999999999999999.4 at scale 0, unrounded" to listOf(MAX),
            "min 1e15 at scale 0, by absolute value" to listOf(MIN, TOO_LONG),
            "min -1e15 at scale 0" to listOf(MIN, TOO_LONG),
            "min -999999999999999 at scale 0, the limit itself" to emptyList(),
            "max -1e15 at scale 0, signed" to listOf(TOO_LONG),
            "max 1e15, scale absent" to listOf(MAX, TOO_LONG),
            "max 1e15 at scale -1" to listOf(TOO_LONG),
            "max 9999999999999990 at scale -1, the limit itself" to listOf(TOO_LONG),
            "max 9999999999999991 at scale -1" to listOf(MAX, TOO_LONG),
            "max 10 at scale 14" to listOf(MAX),
            "max 9.99999999999999 at scale 14, the limit itself" to emptyList(),
            "max 2 at scale 15, outside its range" to listOf(MAX, ABOVE_SCALE),
            "max 0.5 at scale 15" to listOf(ABOVE_SCALE),
            "max 1 at scale 20" to listOf(MAX, ABOVE_SCALE),
            "max 1e15 at scale -2" to listOf(TOO_LONG, SIGNED),
            "max 1e17 at scale -2" to listOf(MAX, TOO_LONG, SIGNED),
            "max 1e6 at scale 2 beside 3 integer places" to emptyList(),
            "max 1e13 at scale 2, an amount" to listOf(MAX),
            "max 9999999999999.99 at scale 2, an amount, the limit itself" to emptyList(),
            "max 1e15 at scale 0, deleted by the default variant" to listOf(MAX),
            "both bounds at scale 0" to listOf(MIN, MAX, TOO_LONG),
            "min 1e15 over max 1 at scale 0" to listOf(MIN, TOO_LONG),
            "min 1e14 over max 1 at scale 1, beside the inversion" to listOf(MIN, INVERTED),
            "max -0.4 beside min -1e15 at scale 0" to listOf(MIN, SIGNED_ZERO, TOO_LONG),
            "max 999999999999.9995 at scale 3" to listOf(MAX, TOO_LONG),
            "max 1e8 at scale 7" to listOf(MAX),
        )
    }
}

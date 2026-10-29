package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The checker against A12: a temporal `format` A12 refuses draws its codeless sentence "The date format is
 * invalid." on a surviving field, and the checker names it from the derived refused set and nowhere beyond it
 * (KERNEL-FINDINGS KF648).
 *
 * The four carriers are the three date kinds' `format` and a fragment's `formatOfFragment`, all answering alike;
 * a type definition carries its format to the field that uses it and is not judged unused. An `ONLY` filter the
 * default variant does not pass lifts the sentence. The refused formats sampled here are members of
 * [TEMPORAL-FORMAT-VOCABULARY]'s Refused table, so the whole-set claim rests on the guard that derives it, while
 * every ADMITTED format is a row, because a format wrongly carried in the set is the one error that over-rejects.
 *
 * `QQQ` is outside the probed universe: A12 refuses it, and the checker's silence there is deliberate, since a
 * refusal resting on the limit of a probe is the over-rejection risk KF313 measured.
 *
 * Own domain (an insurance policy's start date, start time, issue timestamp and renewal season).
 */
class CheckerTemporalFormatAgreementTest {

    private val mapper = ObjectMapper()

    private val base = DocumentModelDraft.named("policy-temporal-format").locale("en_US")
        .rootGroup("Policy") { p -> p.stringField("Ref") { }.dateField("Starts") { }.timeField("At") { }
            .dateTimeField("Issued") { }.dateFragmentField("Renewal") { f -> f.fragmentFormat("MM") } }
        .build().toJson()

    @Test
    fun aRefusedFormatDrawsTheSentenceOnEveryCarrierAndAnAdmittedOneNothing() {
        val actual = linkedMapOf<String, Pair<Set<String>, Set<String>>>()
        val expected = linkedMapOf<String, Pair<Set<String>, Set<String>>>()
        for ((field, member) in CARRIERS) for (format in REFUSED_SAMPLE + ADMITTED + OUTSIDE_THE_UNIVERSE) {
            for (filtered in listOf(false, true)) {
                val root = mapper.readTree(base) as ObjectNode
                val element = field(root, field)
                element.withObject("/fieldType/${member.first}").put(member.second, format)
                if (filtered) element.onlyUnderAnotherVariant()
                val row = "$field `$format`${if (filtered) " ONLY" else ""}"
                actual[row] = verdicts(root.toString())
                val refused = !filtered && format !in ADMITTED
                expected[row] = setOfIf(refused) to setOfIf(refused && format !in OUTSIDE_THE_UNIVERSE)
            }
        }
        assertEquals(expected, actual, "as (A12, ours)")
    }

    @Test
    fun aTypeDefinitionCarriesItsFormatToTheFieldThatUsesIt() {
        val actual = listOf("unused", "kept", "ONLY").associateWith { use ->
            val root = mapper.readTree(base) as ObjectNode
            (root.path("content") as ObjectNode).putArray("typeDefinitions").addObject().put("id", "ClockType")
                .put("name", "ClockType").put("revision", "1")
                .putObject("fieldType").put("type", "TimeType").putObject("TimeType").put("format", "HH:mm")
            if (use != "unused") {
                val element = field(root, "At")
                element.putObject("fieldType").put("type", "TypeDefType").putObject("TypeDefType").put("typeDefinitionId", "ClockType")
                if (use == "ONLY") element.onlyUnderAnotherVariant()
            }
            verdicts(root.toString())
        }
        val drawn = setOfIf(true) to setOfIf(true)
        val none = setOfIf(false) to setOfIf(false)
        assertEquals(mapOf("unused" to none, "kept" to drawn, "ONLY" to none), actual, "as (A12, ours)")
    }

    private fun verdicts(json: String): Pair<Set<String>, Set<String>> =
        kernelModelClasses(json).toSortedSet() to
            check(ModelSource(json)).findings.filter { it.justifiesRejection }.mapTo(sortedSetOf()) { it.code }

    private fun setOfIf(drawn: Boolean): Set<String> = if (drawn) setOf(DATE_FORMAT) else emptySet()

    private fun field(root: ObjectNode, name: String): ObjectNode =
        (root.path("content").path("modelRoot").path("rootGroups").get(0).path("Group").path("elements") as ArrayNode)
            .single { it.path("name").asText() == name }.path("Field") as ObjectNode

    private fun ObjectNode.onlyUnderAnotherVariant() {
        putObject("variantFilter").put("filterType", "ONLY").putArray("variants").add("premium")
    }

    private companion object {
        const val DATE_FORMAT = "RK_DATE_FORMAT_INVALID"

        /** Each carrier field with its type body and the member that declares its format. */
        val CARRIERS = linkedMapOf(
            "Starts" to ("DateType" to "format"),
            "At" to ("TimeType" to "format"),
            "Issued" to ("DateTimeType" to "format"),
            "Renewal" to ("DateFragmentType" to "formatOfFragment"),
        )

        /** Refused-table members across its axes: a missing component, a separator, case, width, a literal, a fraction. */
        val REFUSED_SAMPLE = listOf(
            "HH:mm", "MM.yyyy", "yyyy/MM", "dd.MM", "YYYY-MM-DD", "DD.MM.YYYY", "yy", "HH.mm.ss",
            "hh:mm:ss a", "yyyy-MM-dd HH:mm", "yyyy-MM-dd'T'HH:mm:ss.SSS",
        )

        /** The whole Admitted table of [TEMPORAL-FORMAT-VOCABULARY], kernel 30.8.1. */
        val ADMITTED = listOf(
            "HH:mm:ss", "MM", "MM-dd", "MMdd", "dd.MM.", "dd.MM.yyyy", "ddMM", "yyyy", "yyyy-MM", "yyyy-MM-dd",
            "yyyy-MM-dd'T'HH:mm:ss", "yyyyMM", "yyyyMMdd",
        )

        val OUTSIDE_THE_UNIVERSE = listOf("QQQ")
    }
}

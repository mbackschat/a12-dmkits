package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.mbackschat.a12.dm.adapter.KernelConsistencyOracle
import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.core.ConsistencyCheckFailedException
import io.github.mbackschat.a12.dm.core.ReferenceResolvers
import io.github.mbackschat.a12.dm.core.Severity
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A blank declared `format` on the three types that construct from it, against the portable checker (KERNEL-FINDINGS
 * KF584), every class of every row with its multiplicity.
 *
 * The rival readings each row discriminates: "blank" is Java's whitespace class, so the em space is blank and the
 * no-break space is not; an ABSENT format is the builder's default and admitted, so the gate is on a present blank
 * string rather than on emptiness; and the three types share one gate where a reading could give it to `DateType`
 * alone. The fragment type's own member is the control the checker already stated.
 *
 * Own domain (an insurance policy's dates), built by editing the drafted model's JSON, because the typed builder
 * never writes a blank format.
 */
class CheckerBlankDateFormatAgreementTest {

    private data class Row(val type: String, val member: String, val format: String?, val deleted: Boolean = false)

    @Test
    fun theCheckerStatesA12sBuildVerdictOnEveryRow() {
        val observed = ROWS.mapValues { (_, row) -> fieldModel(row).let { kernel(it) to ours(it) } }
        assertEquals(ROWS.mapValues { (id, _) -> EXPECTED.getValue(id).let { it to it } }, observed, "(A12, ours)")
    }

    /** Not Java whitespace, so the model builds and A12 refuses the format in prose the checker leaves to it. */
    @Test
    fun aNoBreakSpaceFormatIsLeftToA12WithAGap() {
        for (type in TYPES_WITH_FORMAT) {
            val json = fieldModel(Row(type, "format", NO_BREAK_SPACE))
            assertEquals(listOf(DATE_FORMAT_SENTENCE), kernel(json), type)
            val report = check(ModelSource(json))
            assertEquals(emptyList<String>(), report.findings.filter { it.justifiesRejection }.map { it.code }, type)
            assertTrue(report.gaps.any { "admitted vocabulary" in it.reason }, "the silence is disclosed: ${report.gaps}")
        }
    }

    /** A12 builds a definition's type as the field's; the checker reads none and says so at the field. */
    @Test
    fun aTypeDefinitionsBlankFormatIsLeftToA12WithAGapAtTheField() {
        val json = typeDefinitionModel("""{"format":""}""")
        assertEquals(listOf(UNBUILT), kernel(json))
        val report = check(ModelSource(json))
        assertEquals(emptyList<String>(), report.findings.filter { it.justifiesRejection }.map { it.code })
        assertTrue(report.gaps.any { it.at == FIELD_PATH && "StartType" in it.reason }, "a gap at the field: ${report.gaps}")
    }

    private val oracle = KernelConsistencyOracle()
    private val mapper = ObjectMapper()

    private fun kernel(json: String): List<String> = try {
        oracle.checkConsistency(json, ReferenceResolvers.selfContained())
    } catch (unbuildable: ConsistencyCheckFailedException) {
        return listOf(UNBUILT)
    } catch (unbuildable: IllegalArgumentException) {
        return listOf(UNBUILT)
    }.filter { it.severity() == Severity.ERROR }.map { notification ->
        val message = notification.message().trim()
        Regex("\\[(\\w+)]$").find(message)?.groupValues?.get(1)
            ?: codelessClass(message).let { if (it == UNKEYED_A12_CLASS) "codeless:${message.substringAfterLast(": ")}" else it }
    }.sorted()

    private fun ours(json: String): List<String> =
        check(ModelSource(json)).findings.filter { it.justifiesRejection }.map { it.code }.sorted()

    private val base: String = DocumentModelDraft.named("policy-dates").locale("en_US")
        .rootGroup("Policy") { p -> p.stringField("Number") { } }.build().toJson()

    private fun newField(root: ObjectNode): ObjectNode {
        val elements = root.path("content").path("modelRoot").path("rootGroups")[0].path("Group").path("elements") as ArrayNode
        return elements.addObject().put("type", "Field").put("id", FIELD_PATH).put("name", "Start").putObject("Field")
    }

    private fun fieldModel(row: Row): String {
        val root = mapper.readTree(base) as ObjectNode
        val field = newField(root)
        val body = field.putObject("fieldType").put("type", row.type).putObject(row.type)
        row.format?.let { body.put(row.member, it) }
        if (row.deleted) field.putObject("variantFilter").put("filterType", "ONLY").putArray("variants").add("Broker")
        return mapper.writeValueAsString(root)
    }

    private fun typeDefinitionModel(dateBody: String): String {
        val root = mapper.readTree(base) as ObjectNode
        (root.path("content") as ObjectNode).putArray("typeDefinitions").addObject().put("id", "StartType")
            .put("name", "StartType").put("revision", "1").putObject("fieldType").put("type", "DateType")
            .set<ObjectNode>("DateType", mapper.readTree(dateBody))
        newField(root).putObject("fieldType").put("type", "TypeDefType").putObject("TypeDefType")
            .put("typeDefinitionId", "StartType")
        return mapper.writeValueAsString(root)
    }

    private companion object {
        const val UNBUILT = "CK_MODEL_NOT_BUILDABLE"
        const val FIELD_PATH = "/Policy/Start"
        const val DATE_FORMAT_SENTENCE = "codeless:The date format is invalid."
        val NO_BREAK_SPACE = Char(0x00A0).toString()
        val EM_SPACE = Char(0x2003).toString()
        val TYPES_WITH_FORMAT = listOf("DateType", "DateTimeType", "TimeType")

        val BLANKS = linkedMapOf("empty" to "", "a space" to " ", "a tab" to "\t", "a newline" to "\n", "an em space" to EM_SPACE)

        val ROWS: Map<String, Row> = buildMap {
            for (type in TYPES_WITH_FORMAT) {
                put("$type, format absent", Row(type, "format", null))
                for ((name, blank) in BLANKS) put("$type, format $name", Row(type, "format", blank))
                put("$type, format empty, deleted by the default variant", Row(type, "format", "", deleted = true))
            }
            put("DateFragmentType, formatOfFragment empty", Row("DateFragmentType", "formatOfFragment", ""))
        }

        val EXPECTED: Map<String, List<String>> = ROWS.mapValues { (_, row) ->
            if (row.format == null || row.deleted) emptyList() else listOf(UNBUILT)
        }
    }
}

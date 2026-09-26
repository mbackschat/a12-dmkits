package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.core.ModelConsistencyException
import io.github.mbackschat.a12.dm.core.ModelInputException
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/** R0's variant-container witnesses, paired before measurement in CONFORMANCE-PLAN's acceptance matrix. */
class FieldVariantStageLawsTest {
    @Test
    fun declarationChecksReadThePredictedStage() {
        val rows = FieldVariantPopulation.rows()
        assertEquals(123, rows.size, "R0a's frozen witness denominator")
        assertEquals(rows.size, rows.map { it.label }.distinct().size, "each witness must have a distinct label")
        val differences = rows.mapNotNull { row ->
            val actual = kernelClasses(row.json)
            if (actual == row.predicted) null else "${row.label}: predicted ${row.predicted}, observed $actual"
        }
        assertEquals(emptyList<String>(), differences)
    }

    @Test
    fun theVariantPopulationHasOnlyLedgeredCheckerDifferences() {
        val clusters = sortedMapOf<String, Int>()
        val examples = mutableListOf<String>()
        var compared = 0
        for (row in FieldVariantPopulation.rows()) {
            val a12 = kernelClasses(row.json)
            val ours = check(ModelSource(row.json)).findings.filter { it.justifiesRejection }.map { it.code }.toSet()
            compared++
            val verdict = checkerVerdict(a12, ours) ?: continue
            val key = "$verdict\t${column(a12)}\t${column(ours)}"
            clusters[key] = clusters.getOrDefault(key, 0) + 1
            examples += "$key\t${row.label}"
        }
        assertEquals(123, compared, "every R0a input must reach both oracles")
        val actual = clusters.entries.joinToString("") { "${it.value}\t${it.key}\n" }
        // Run output is disposable; the resource ledger below is the cross-run record.
        File("build/checker-variants").apply { mkdirs() }.also {
            File(it, "actual.tsv").writeText(actual)
            File(it, "examples.tsv").writeText(examples.joinToString("\n"))
        }
        val ledger = checkNotNull(javaClass.getResource("/checker-near-miss/variants.tsv")).readText()
        assertEquals(ledger, actual)
    }

    private fun column(codes: Set<String>) = codes.sorted().joinToString(",").ifEmpty { "(none)" }

    private fun kernelClasses(json: String): Set<String> = try {
        BoundModel.ofJson(json)
        emptySet()
    } catch (unreadable: ModelInputException) {
        setOf("CK_MODEL_NOT_BUILDABLE")
    } catch (rejected: ModelConsistencyException) {
        val messages = rejected.notifications().map { it.message().trim() }
        check(messages.isNotEmpty()) { "A12 refused the model without a notification" }
        messages.map { message ->
            Regex("\\[(\\w+)]$").find(message)?.groupValues?.get(1) ?: when {
                message.contains("the kernel could not build this model:") -> "CK_MODEL_NOT_BUILDABLE"
                message.contains("The field contains one or several unsupported signs.") -> "RK_UNSUPPORTED_CHARACTER"
                else -> UNKEYED_A12_CLASS
            }
        }.toSet()
    }
}

/** P3: each defect and its valid control, each under all three default-variant filter modes. */
internal object FieldVariantPopulation {
    private val mapper = ObjectMapper()
    private val order = DocumentModelDraft.named("order-variant-stages").locale("en_US")
        .rootGroup("Order") { o -> o
            .stringField("Reference") { }
            .stringField("Code") { s -> s.maxLength(8).pattern("[A-Z]+", "Use capitals")
                .suggestions("en_US", "OPEN", "PAID") }
            .enumField("Status") { e -> e.value("OPEN").value("PAID").category("Band", "A", "B") }
            .numberField("Total") { n -> n.fractionDigits(2) }
            .dateField("Placed") { d -> d.format("yyyy-MM-dd") } }
        .build().toJson()

    enum class Filter { ABSENT, ONLY, ALL_EXCEPT }

    private enum class Stage { CONTAINER, BEFORE_CONTAINER }

    class Row(val label: String, val json: String, val predicted: Set<String>)

    private class Witness(
        val name: String,
        val field: String,
        val code: String,
        val stage: Stage = Stage.CONTAINER,
        val filteredCode: String? = null,
        val mutate: (ObjectNode, ObjectNode, ObjectNode) -> Unit,
    )

    private val witnesses = listOf(
        Witness("unspecified type", "Code", "MVK_UNSPECIFIED_TYPE_NO_CODEGEN") { _, _, f ->
            f.putObject("fieldType").put("type", "UnspecifiedType").putObject("UnspecifiedType")
        },
        Witness("unknown type", "Code", "CK_MODEL_NOT_BUILDABLE", Stage.BEFORE_CONTAINER) { _, _, f ->
            f.withObject("/fieldType").put("type", "UnknownType")
        },
        Witness("temporal configuration", "Placed", "MVK_OPTIONAL_DATE_TYPE_INVALID") { _, _, f ->
            f.withObject("/fieldType/DateType").put("format", "yyyy-MM").put("datePrecision", "DAY_OPTIONAL")
        },
        Witness("field alphabet", "Code", "MVK_NAME_STARTS_WITH_XML_A12_DV") { _, e, _ -> e.put("name", "xmlCode") },
        Witness("reserved field segment", "Code", "MVK_NAME_INVALID_A12_DV,MVK_NAME_INVALID_DOT_COLON_A12_DV") { _, e, _ ->
            e.put("name", "Code.value")
        },
        Witness("category alignment", "Status", "MVK_MISSING_CATEGORY_VALUE") { _, _, f ->
            (f.withObject("/fieldType/EnumerationType").withArray("categories").get(0) as ObjectNode)
                .withArray("values").remove(1)
        },
        Witness("supported enum characters", "Status", "MVK_ENUM_VALUE_WITH_INVALID_CHARACTER") { root, _, _ ->
            root.withObject("/content/modelConfig").putArray("supportedCharacters").add("A-M")
        },
        Witness("enum text", "Status", "MVK_INTERNAL_VALUE_INVALID") { _, _, f ->
            (f.withObject("/fieldType/EnumerationType").withArray("values").get(0) as ObjectNode).put("value", "O|PEN")
        },
        Witness("model text repertoire", "Code", "RK_UNSUPPORTED_CHARACTER") { _, _, f ->
            (f.withObject("/fieldType/StringType").withArray("errorMessage").get(0) as ObjectNode).put("text", "Use 😀")
        },
        Witness("hint values", "Code", "MVK_VALUE_TOO_LONG") { _, _, f ->
            f.withObject("/fieldType/StringType").put("maxLength", 3)
        },
        Witness("enum special value", "Status", "MVK_S_VALUE_INVALID") { _, _, f ->
            f.withObject("/fieldType/EnumerationType").put("sValue", "CLOSED")
        },
        Witness("pattern admission", "Code", "MVK_PATTERN_INVALID") { _, _, f ->
            f.withObject("/fieldType/StringType").put("pattern", "a{2}+")
        },
        Witness("pattern message", "Code", "MVK_ERROR_MESSAGE_AND_NO_PATTERN") { _, _, f ->
            f.withObject("/fieldType/StringType").remove("pattern")
        },
        Witness("field message", "Code", "INVALID_CHARACTERS") { _, _, f ->
            (f.withObject("/fieldType/StringType").withArray("errorMessage").get(0) as ObjectNode).put("text", "Use\tcapitals")
        },
        Witness("string parameters", "Code", "MVK_MIN_LENGTH_BIGGER_MAX_LENGTH") { _, _, f ->
            f.withObject("/fieldType/StringType").put("minLength", 9).remove("hintList")
        },
        Witness("number digits", "Total", "MVK_FRACT_DIGITS_INVALID") { _, _, f ->
            f.withObject("/fieldType/NumberType").put("minFractionalDigits", 3)
        },
        Witness("number scale limit", "Total", UNKEYED_A12_CLASS) { _, _, f ->
            f.withObject("/fieldType/NumberType").put("maxFractionalDigits", 15)
        },
        Witness("original model number limit", "Total", UNKEYED_A12_CLASS, Stage.BEFORE_CONTAINER) { _, _, f ->
            f.withObject("/fieldType/NumberType").put("maxValue", "99999999999999999999")
        },
        Witness("enum values", "Status", "MVK_INTERNAL_VALUES_DUPLICATE,MVK_CATEGORY_VALUE_DUPLICATE,$UNKEYED_A12_CLASS",
            filteredCode = UNKEYED_A12_CLASS) { _, _, f ->
            (f.withObject("/fieldType/EnumerationType").withArray("values").get(1) as ObjectNode).put("value", "OPEN")
        },
        Witness("string slot binding", "Code", "CK_MODEL_NOT_BUILDABLE", filteredCode = UNKEYED_A12_CLASS) { _, e, _ ->
            e.putObject("name")
        },
    )

    fun rows(): List<Row> = declarationRows() + orderingRows()

    private fun declarationRows(): List<Row> = witnesses.flatMap { witness ->
        listOf(false, true).flatMap { defective ->
            Filter.entries.map { filter ->
                val root = mapper.readTree(order) as ObjectNode
                val element = root.path("content").path("modelRoot").path("rootGroups").get(0)
                    .path("Group").path("elements").single { it.path("name").asText() == witness.field } as ObjectNode
                val field = element.path("Field") as ObjectNode
                if (defective) witness.mutate(root, element, field)
                if (filter != Filter.ABSENT) field.putObject("variantFilter").put("filterType", filter.name)
                    .putArray("variants").add("premium")
                val predicted = when {
                    !defective -> emptySet()
                    filter == Filter.ONLY && witness.filteredCode != null -> setOf(witness.filteredCode)
                    filter == Filter.ONLY && witness.stage == Stage.CONTAINER -> emptySet()
                    else -> witness.code.split(',').toSet()
                }
                Row("${witness.name}/$defective/$filter", root.toString(), predicted)
            }
        }
    }

    private fun orderingRows(): List<Row> {
        val indexed = DocumentModelDraft.named("order-filtered-key").locale("en_US")
            .rootGroup("Order") { o -> o.indexField("Reference")
                .stringField("Reference") { }
                .numberField("Total") { n -> n.fractionDigits(2) } }.build().toJson()
        return Filter.entries.map { filter ->
            val root = mapper.readTree(indexed) as ObjectNode
            val field = root.path("content").path("modelRoot").path("rootGroups").get(0)
                .path("Group").path("elements").first().path("Field") as ObjectNode
            if (filter != Filter.ABSENT) field.putObject("variantFilter").put("filterType", filter.name)
                .putArray("variants").add("premium")
            Row("index reference/$filter", root.toString(),
                if (filter == Filter.ONLY) setOf("MVK_INVALID_INDEX_FIELD") else emptySet())
        }
    }
}

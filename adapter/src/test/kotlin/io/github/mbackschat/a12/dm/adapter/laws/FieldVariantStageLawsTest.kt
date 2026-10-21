package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.SortOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/** R0's variant-container witnesses, paired before measurement in CONFORMANCE-PLAN's acceptance matrix. */
class FieldVariantStageLawsTest {
    @Test
    fun declarationChecksReadThePredictedStage() {
        assertPredictions(FieldVariantPopulation.initialRows(), 123)
    }

    @Test
    fun remainingFieldFamiliesReadThePredictedStage() {
        assertPredictions(FieldVariantPopulation.remainingRows(), 16)
    }

    @Test
    fun mixedFieldArmsHaveThePredictedFilterSensitivity() {
        assertPredictions(FieldVariantPopulation.mixedRows(), 78)
    }

    @Test
    fun orderingReferencesUseThePredictedVariantLookup() {
        assertPredictions(FieldVariantPopulation.orderingLookupRows(), 30)
    }

    private fun assertPredictions(rows: List<FieldVariantPopulation.Row>, expectedSize: Int) {
        assertEquals(expectedSize, rows.size, "the partition's frozen witness denominator")
        assertEquals(rows.size, rows.map { it.label }.distinct().size, "each witness must have a distinct label")
        val differences = rows.mapNotNull { row ->
            val actual = kernelModelClasses(row.json)
            if (actual == row.predicted) null else "${row.label}: predicted ${row.predicted}, observed $actual"
        }
        assertEquals(emptyList<String>(), differences)
    }

    @Test
    fun theVariantPopulationHasOnlyLedgeredCheckerDifferences() {
        assertLedger(FieldVariantPopulation.rows(), 139, "variants")
    }

    @Test
    fun theMixedFieldPopulationHasOnlyLedgeredCheckerDifferences() {
        assertLedger(FieldVariantPopulation.mixedRows(), 78, "variants-mixed")
    }

    @Test
    fun theOrderingPopulationHasOnlyLedgeredCheckerDifferences() {
        assertLedger(FieldVariantPopulation.orderingLookupRows(), 30, "variants-ordering")
    }

    private fun assertLedger(rows: List<FieldVariantPopulation.Row>, expectedSize: Int, name: String) {
        val clusters = sortedMapOf<String, Int>()
        val examples = mutableListOf<String>()
        var compared = 0
        for (row in rows) {
            val a12 = kernelModelClasses(row.json)
            val ours = check(ModelSource(row.json)).findings.filter { it.justifiesRejection }.map { it.code }.toSet()
            compared++
            val verdict = checkerVerdict(a12, ours) ?: continue
            val key = "$verdict\t${column(a12)}\t${column(ours)}"
            clusters[key] = clusters.getOrDefault(key, 0) + 1
            examples += "$key\t${row.label}"
        }
        assertEquals(expectedSize, compared, "every input must reach both oracles")
        val actual = clusters.entries.joinToString("") { "${it.value}\t${it.key}\n" }
        // Run output is disposable; the resource ledger below is the cross-run record.
        File("build/checker-$name").apply { mkdirs() }.also {
            File(it, "actual.tsv").writeText(actual)
            File(it, "examples.tsv").writeText(examples.joinToString("\n"))
        }
        val ledger = checkNotNull(javaClass.getResource("/checker-near-miss/$name.tsv")).readText()
        assertEquals(ledger, actual)
    }

    private fun column(codes: Set<String>) = codes.sorted().joinToString(",").ifEmpty { "(none)" }


}

/** P3 uses one generator so kernel predictions and checker comparisons receive identical models. */
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

    private enum class Ordering { INDEX, SORT }

    private enum class FilterEffect { REMOVES_REFUSAL, RETAINS_REFUSAL }

    class Row(val label: String, val json: String, val predicted: Set<String>)

    private class Witness(
        val name: String,
        val field: String,
        val code: String,
        val filterEffect: FilterEffect = FilterEffect.REMOVES_REFUSAL,
        val filteredCode: String? = null,
        val mutate: (ObjectNode, ObjectNode, ObjectNode) -> Unit,
    )

    private val witnesses = listOf(
        Witness("unspecified type", "Code", "MVK_UNSPECIFIED_TYPE_NO_CODEGEN") { _, _, f ->
            f.putObject("fieldType").put("type", "UnspecifiedType").putObject("UnspecifiedType")
        },
        Witness("unknown type", "Code", "CK_MODEL_NOT_BUILDABLE", FilterEffect.RETAINS_REFUSAL) { _, _, f ->
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
        Witness("number scale limit", "Total", "RK_MEMBER_ABOVE_SCALE_LIMIT") { _, _, f ->
            f.withObject("/fieldType/NumberType").put("maxFractionalDigits", 15)
        },
        // The magnitude sentence is asked of the stored model's every field (KF582), so an `ONLY` filter keeps it;
        // the digit sentence is the printed bound's (KF569), judged on the default variant.
        Witness("original model number limit", "Total", "RK_NUMBER_MAX_VALUE_EXCEEDED,RK_BOUND_TOO_MANY_DIGITS",
            FilterEffect.RETAINS_REFUSAL, filteredCode = "RK_NUMBER_MAX_VALUE_EXCEEDED") { _, _, f ->
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

    fun rows(): List<Row> = initialRows() + remainingRows()

    fun initialRows(): List<Row> = declarationRows(witnesses) + orderingRows()

    fun remainingRows(): List<Row> = declarationRows(listOf(
        Witness("field name collision", "Code", "CK_MODEL_NOT_BUILDABLE") { _, e, _ -> e.put("name", "Reference") },
    )) + bindingRows()

    fun mixedRows(): List<Row> = declarationRows(listOf(
        Witness("absent field type", "Code", "CK_MODEL_NOT_BUILDABLE", FilterEffect.RETAINS_REFUSAL) { _, _, f ->
            f.remove("fieldType")
        },
        Witness("null field type", "Code", "CK_MODEL_NOT_BUILDABLE", FilterEffect.RETAINS_REFUSAL) { _, _, f ->
            f.putNull("fieldType")
        },
        Witness("absent date body", "Placed", "CK_MODEL_NOT_BUILDABLE", FilterEffect.RETAINS_REFUSAL) { _, _, f ->
            f.putObject("fieldType").put("type", "DateType")
        },
        Witness("absent range format", "Placed", "RK_DATE_RANGE_FORMAT_INVALID") { _, _, f ->
            f.putObject("fieldType").put("type", "DateRangeType").putObject("DateRangeType")
        },
        Witness("absent fragment format", "Placed", "CK_MODEL_NOT_BUILDABLE") { _, _, f ->
            f.putObject("fieldType").put("type", "DateFragmentType").putObject("DateFragmentType")
        },
        Witness("absent enum values", "Status", "CK_MODEL_NOT_BUILDABLE") { _, _, f ->
            f.putObject("fieldType").put("type", "EnumerationType").putObject("EnumerationType")
        },
        Witness("absent custom name", "Code", "CUSTOM_FIELD_TYPE_CODENAME_MISSING") { _, _, f ->
            f.putObject("fieldType").put("type", "CustomFieldType").putObject("CustomFieldType")
        },
        Witness("absent typedef id", "Code", "CK_MODEL_NOT_BUILDABLE", filteredCode = UNKEYED_A12_CLASS) { _, _, f ->
            f.putObject("fieldType").put("type", "TypeDefType").putObject("TypeDefType")
        },
        Witness("unresolved typedef id", "Code", "CK_MODEL_NOT_BUILDABLE") { _, _, f ->
            f.putObject("fieldType").put("type", "TypeDefType").putObject("TypeDefType")
                .put("typeDefinitionId", "missing")
        },
        Witness("incompatible range pair", "Placed", "RK_DATE_RANGE_FORMAT_INVALID") { _, _, f ->
            f.putObject("fieldType").put("type", "DateRangeType").putObject("DateRangeType")
                .put("format", "yyyy-MM").put("rangeSeparator", "-")
        },
        Witness("empty message entry", "Code", "CK_MODEL_NOT_BUILDABLE", FilterEffect.RETAINS_REFUSAL) { _, _, f ->
            f.withObject("/fieldType/StringType").putArray("errorMessage").addObject()
        },
        Witness("inverted number bounds", "Total", "MVK_MIN_VALUE_BIGGER_THAN_MAX_VALUE") { _, _, f ->
            f.withObject("/fieldType/NumberType").put("minValue", 2).put("maxValue", 1)
        },
        Witness("integer digit limit", "Total", "RK_MEMBER_ABOVE_DIGIT_BUDGET") { _, _, f ->
            f.withObject("/fieldType/NumberType").put("maxIntegerDigits", 16)
        },
    ))

    private fun declarationRows(witnesses: List<Witness>): List<Row> = witnesses.flatMap { witness ->
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
                    filter == Filter.ONLY && witness.filterEffect == FilterEffect.REMOVES_REFUSAL -> emptySet()
                    else -> witness.code.split(',').toSet()
                }
                Row("${witness.name}/$defective/$filter", root.toString(), predicted)
            }
        }
    }

    private fun bindingRows(): List<Row> {
        fun row(label: String, refused: Boolean = false, edit: (ObjectNode) -> Unit): Row {
            val root = mapper.readTree(order) as ObjectNode
            val field = root.path("content").path("modelRoot").path("rootGroups").get(0)
                .path("Group").path("elements").single { it.path("name").asText() == "Code" }
                .path("Field") as ObjectNode
            edit(field)
            return Row("filter binding/$label", root.toString(),
                if (refused) setOf("CK_MODEL_NOT_BUILDABLE") else emptySet())
        }
        return listOf(
            row("absent") { },
            row("ONLY") { it.putObject("variantFilter").put("filterType", "ONLY").putArray("variants").add("premium") },
            row("ALL_EXCEPT") { it.putObject("variantFilter").put("filterType", "ALL_EXCEPT").putArray("variants").add("premium") },
            row("null") { it.putNull("variantFilter") },
            row("unknown mode", true) { it.putObject("variantFilter").put("filterType", "SOMETIMES") },
            row("absent mode", true) { it.putObject("variantFilter").putArray("variants").add("premium") },
            row("string filter", true) { it.put("variantFilter", "ONLY") },
            row("null mode", true) { it.putObject("variantFilter").putNull("filterType") },
            row("object mode", true) { it.putObject("variantFilter").putObject("filterType") },
            row("numeric mode", true) { it.putObject("variantFilter").put("filterType", 9) },
        )
    }

    fun orderingLookupRows(): List<Row> = Ordering.entries.flatMap { ordering ->
        val base = DocumentModelDraft.named("order-variant-lookup").locale("en_US")
            .rootGroup("Order") { root -> root.stringField("Key") { it.lineBreaks() }
                .group("Items") { items ->
                    items.repeatable(2).stringField("Key") { it.lineBreaks() }
                        .stringField("Spare") { it.lineBreaks() }
                    when (ordering) {
                        Ordering.INDEX -> items.indexField("Key")
                        Ordering.SORT -> items.sortField("Key", SortOrder.ASC)
                    }
                } }.build().toJson()
        val invalid = "MVK_INVALID_${ordering.name}_FIELD"
        val raw = "MVK_${ordering.name}_FIELD_NO_VALUE_VALIDATION"
        fun element(group: ObjectNode, name: String): ObjectNode =
            group.withArray("elements").single { it.path("name").asText() == name } as ObjectNode
        fun filter(group: ObjectNode, name: String, mode: Filter = Filter.ONLY) {
            element(group, name).withObject("/Field/variantFilter").put("filterType", mode.name)
                .putArray("variants").add("premium")
        }
        fun raw(group: ObjectNode, name: String) {
            element(group, name).withObject("/Field/fieldType/StringType").put("noValueValidation", true)
        }
        fun remove(group: ObjectNode) {
            val elements = group.withArray("elements")
            elements.remove(elements.indexOfFirst { it.path("name").asText() == "Key" })
        }
        fun row(
            label: String,
            reference: String = "Key",
            code: String? = null,
            mutate: (ObjectNode, ObjectNode) -> Unit = { _, _ -> },
        ): Row {
            val root = mapper.readTree(base) as ObjectNode
            val parent = root.withObject("/content/modelRoot/rootGroups/0/Group")
            val local = element(parent, "Items").withObject("/Group")
            when (ordering) {
                Ordering.INDEX -> local.put("indexFieldName", reference)
                Ordering.SORT -> (local.withArray("sortFields").get(0) as ObjectNode).put("name", reference)
            }
            mutate(parent, local)
            return Row("ordering/$ordering/$label", root.toString(), code?.let(::setOf) ?: emptySet())
        }
        listOf(
            row("direct control"),
            row("direct ONLY", code = invalid) { _, local -> filter(local, "Key") },
            row("direct ALL_EXCEPT") { _, local -> filter(local, "Key", Filter.ALL_EXCEPT) },
            row("direct absent", code = "CK_MODEL_NOT_BUILDABLE") { _, local -> remove(local) },
            row("remote control", "../Key"),
            row("remote ONLY", "../Key") { parent, _ -> filter(parent, "Key") },
            row("remote absent", "../Key", "CK_MODEL_NOT_BUILDABLE") { parent, _ -> remove(parent) },
            row("local ONLY", "../Key", invalid) { _, local -> filter(local, "Key") },
            row("both ONLY", "../Key", invalid) { parent, local -> filter(parent, "Key"); filter(local, "Key") },
            row("local absent", "../Key", invalid) { _, local -> remove(local) },
            row("remote raw", "../Key") { parent, _ -> raw(parent, "Key") },
            row("local raw", "../Key", raw) { _, local -> raw(local, "Key") },
            row("first twin filtered", code = raw) { _, local ->
                filter(local, "Key")
                raw(local, "Spare")
                element(local, "Spare").put("name", "Key")
            },
            row("second twin filtered", code = raw) { _, local ->
                filter(local, "Spare")
                raw(local, "Key")
                element(local, "Spare").put("name", "Key")
            },
            row("both twins filtered", code = invalid) { _, local ->
                filter(local, "Key")
                filter(local, "Spare")
                element(local, "Spare").put("name", "Key")
            },
        )
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

package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/** R4's path/carrier/filter population; predictions are frozen in CONFORMANCE-PLAN before execution. */
class ElementPathStageLawsTest {
    private enum class Carrier { ROOT, NESTED, FIELD }
    private enum class Filter { ABSENT, ONLY, ALL_EXCEPT }
    private data class Name(val text: String, val root: String = "", val nested: String = root, val field: String = nested)

    @Test
    fun pathNamesAndMessagesFollowTheirPredictedStages() {
        val path = "MVK_PATH_INVALID,$UNKEYED_A12_CLASS"
        val dot = "MVK_NAME_INVALID_DOT_COLON_A12_DV"
        val names = listOf(
            Name("Code"),
            *listOf("Codeé", "éCode", "Code中", "中Code", "Code١", "١Code").map { Name(it, path) }.toTypedArray(),
            Name("Codeä", field = path), Name("Codeß", field = path),
            Name("äCode", nested = path), Name("ßCode", nested = path),
            Name("1Code", "MVK_NAME_STARTS_WITH_DIGIT", path, "MVK_PATH_INVALID,MVK_NAME_STARTS_WITH_DIGIT_A12_DV"),
            Name("_Code", nested = "NAME_STARTS_WITH_UNDERSCORE_AND_WRONG_PATTERN", field = ""),
            Name("xmlCode", "MVK_NAME_STARTS_WITH_XML", field = "MVK_NAME_STARTS_WITH_XML_A12_DV"),
            *listOf("Co.de", "Co:de", ":Code").map { Name(it, path, field = dot) }.toTypedArray(),
            *listOf("Co.1de", "Co._de", "Co..de", "Co.").map { Name(it, path) }.toTypedArray(),
            Name("Co/de", "MVK_PATHS_INCONSISTENT"), Name("Co-de", path),
            Name("Id", "MVK_ROOT_GROUP_NAME_INVALID", nested = ""),
        )
        assertEquals(24, names.size)
        assertPopulation(names, 432, "element-paths")
    }

    @Test
    fun mixedNamesFollowThePredictedPathGate() {
        val path = "MVK_PATH_INVALID,$UNKEYED_A12_CLASS"
        val names = listOf(
            Name("1Co.de", path, field = "MVK_PATH_INVALID,MVK_NAME_INVALID_DOT_COLON_A12_DV,MVK_NAME_STARTS_WITH_DIGIT_A12_DV"),
            Name("1Codeé", path), Name("xmlCodeé", path), Name("_Codeé", path),
            Name("Co/-de", "MVK_PATH_INVALID", nested = path), Name("-Co/de", path),
        )
        assertPopulation(names, 108, "element-paths-mixed")
    }

    private fun assertPopulation(names: List<Name>, expectedCount: Int, ledgerName: String) {
        val path = "MVK_PATH_INVALID"
        val host = DocumentModelDraft.named("order-element-paths").locale("en_US")
            .rootGroup("Order") { order -> order.stringField("Reference") { }
                .group("Part") { part -> part.stringField("Sibling") { }
                    .stringField("Code") { it.pattern("[A-Z]+", "Use capitals") } } }
            .build().toJson()
        val mapper = ObjectMapper()
        val models = mutableSetOf<String>()
        val predictions = mutableListOf<String>()
        val clusters = sortedMapOf<String, Int>()
        val examples = mutableListOf<String>()
        for (name in names) for (carrier in Carrier.entries) for (tab in listOf(false, true)) for (filter in Filter.entries) {
            val tree = mapper.readTree(host)
            val root = tree.at("/content/modelRoot/rootGroups/0") as ObjectNode
            val nested = root.at("/Group/elements/1") as ObjectNode
            val field = nested.at("/Group/elements/1") as ObjectNode
            when (carrier) {
                Carrier.ROOT -> root
                Carrier.NESTED -> nested
                Carrier.FIELD -> field
            }.put("name", name.text)
            if (filter != Filter.ABSENT) field.withObject("/Field/variantFilter")
                .put("filterType", filter.name).putArray("variants").add("premium")
            (field.at("/Field/fieldType/StringType/errorMessage/0") as ObjectNode)
                .put("text", if (tab) "Use\tcapitals" else "Use capitals")
            val json = tree.toString()
            val label = "${name.text}:$carrier:tab=$tab:$filter"
            models += json
            val nameCodes = when (carrier) {
                Carrier.ROOT -> name.root
                Carrier.NESTED -> name.nested
                Carrier.FIELD -> if (filter == Filter.ONLY) "" else name.field
            }.split(',').filter { it.isNotEmpty() }.toSet()
            val expected = nameCodes + if (tab && filter != Filter.ONLY &&
                path !in nameCodes && "MVK_PATHS_INCONSISTENT" !in nameCodes) setOf("INVALID_CHARACTERS") else emptySet()
            val kernel = kernelModelClasses(json)
            if (expected != kernel) predictions += "$label: predicted $expected, observed $kernel"
            val portable = check(ModelSource(json)).findings.filter { it.justifiesRejection }.map { it.code }.toSet()
            checkerVerdict(kernel, portable)?.let { verdict ->
                val key = "$verdict\t${column(kernel)}\t${column(portable)}"
                clusters[key] = clusters.getOrDefault(key, 0) + 1
                examples += "$key\t$label"
            }
        }
        assertEquals(expectedCount, models.size, "every population member is a distinct model")
        val actual = clusters.entries.joinToString("") { "${it.value}\t${it.key}\n" }
        // Disposable run outputs; the resource ledger retains the comparison across runs.
        File("build/checker-element-paths").apply { mkdirs() }.also {
            File(it, "$ledgerName.tsv").writeText(actual)
            File(it, "$ledgerName-examples.tsv").writeText(examples.joinToString("\n"))
            File(it, "$ledgerName-predictions.txt").writeText(predictions.joinToString("\n"))
        }
        assertEquals(emptyList<String>(), predictions, "independent kernel predictions")
        assertEquals(checkNotNull(javaClass.getResource("/checker-near-miss/$ledgerName.tsv")).readText(), actual)
    }

    private fun column(codes: Set<String>) = codes.sorted().joinToString(",").ifEmpty { "(none)" }
}

package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
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
        val dot = "MVK_NAME_INVALID_DOT_COLON_A12_DV"
        val names = listOf(
            Name("Code"),
            *listOf("Codeé", "éCode", "Code中", "中Code", "Code١", "١Code").map(::refused).toTypedArray(),
            Name("Codeä", field = FIELD_PATH), Name("Codeß", field = FIELD_PATH),
            Name("äCode", nested = NESTED_PATH, field = FIELD_PATH), Name("ßCode", nested = NESTED_PATH, field = FIELD_PATH),
            Name("1Code", "MVK_NAME_STARTS_WITH_DIGIT", NESTED_PATH, "MVK_PATH_INVALID,MVK_NAME_STARTS_WITH_DIGIT_A12_DV"),
            Name("_Code", nested = "NAME_STARTS_WITH_UNDERSCORE_AND_WRONG_PATTERN", field = ""),
            Name("xmlCode", "MVK_NAME_STARTS_WITH_XML", field = "MVK_NAME_STARTS_WITH_XML_A12_DV"),
            *listOf("Co.de", "Co:de", ":Code").map { Name(it, ROOT_PATH, NESTED_PATH, dot) }.toTypedArray(),
            *listOf("Co.1de", "Co._de", "Co..de", "Co.").map(::refused).toTypedArray(),
            Name("Co/de", "MVK_PATHS_INCONSISTENT"), refused("Co-de"),
            Name("Id", "MVK_ROOT_GROUP_NAME_INVALID", nested = ""),
        )
        assertEquals(24, names.size)
        assertPopulation(names, 432, "element-paths")
    }

    @Test
    fun mixedNamesFollowThePredictedPathGate() {
        val names = listOf(
            Name("1Co.de", ROOT_PATH, NESTED_PATH, "MVK_PATH_INVALID,MVK_NAME_INVALID_DOT_COLON_A12_DV,MVK_NAME_STARTS_WITH_DIGIT_A12_DV"),
            refused("1Codeé"), refused("xmlCodeé"), refused("_Codeé"),
            // A name carrying `/` is left to A12 by the checker's sentences (KF580), so these rows keep disagreeing.
            Name("Co/-de", "MVK_PATH_INVALID", NESTED_PATH, FIELD_PATH),
            Name("-Co/de", "MVK_PATH_INVALID,RK_ROOT_GROUP_NAME_ALPHABET", NESTED_PATH, "MVK_PATH_INVALID,RK_GROUP_PATH_INVALID"),
        )
        assertPopulation(names, 108, "element-paths-mixed")
    }

    /** A name every carrier refuses, each beside the sentence its own alphabet states (KF580). */
    private fun refused(text: String) = Name(text, ROOT_PATH, NESTED_PATH, FIELD_PATH)

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
            val portable = rejectingCodes(json).toSet()
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

    private companion object {
        const val ROOT_PATH = "MVK_PATH_INVALID,RK_ROOT_GROUP_NAME_ALPHABET,RK_GROUP_PATH_INVALID"
        const val NESTED_PATH = "MVK_PATH_INVALID,RK_GROUP_PATH_INVALID"
        const val FIELD_PATH = "MVK_PATH_INVALID,RK_FIELD_NAME_ALPHABET"
    }
}

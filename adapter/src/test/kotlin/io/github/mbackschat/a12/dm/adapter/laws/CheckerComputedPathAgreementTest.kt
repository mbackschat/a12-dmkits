package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.mbackschat.a12.dm.adapter.KernelConsistencyOracle
import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.core.ConsistencyCheckFailedException
import io.github.mbackschat.a12.dm.core.ReferenceResolvers
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Three element-name laws A12 states beside the path gate (KERNEL-FINDINGS KF583), against the portable checker,
 * every class with the path it attaches to: a computed field whose PATH carries a dot or a colon, an underscore
 * group name below an `xml…` ancestor, and a blank name, which leaves the model unbuilt.
 *
 * The host is KF580's: `/Order` holds `Reference` and `Part`, `Part` holds `Sibling`, `Code` and `Bin`, and `Bin`
 * holds `Slot`, `Label`, the rule `Check` and the computation `Fill`, which computes `Label`. Own domain (an order's
 * parts and bins), built by editing the drafted model's JSON, because the typed builder refuses these names.
 */
class CheckerComputedPathAgreementTest {

    private data class Row(
        val names: Map<At, String> = emptyMap(),
        val computation: Boolean = true,
        val only: At? = null,
        val operation: String? = null,
        val indexedByLabel: Boolean = false,
    )

    private enum class At(val pointer: String) {
        ROOT(ROOT_POINTER),
        PART("$ROOT_POINTER/Group/elements/1"),
        BIN("$ROOT_POINTER/Group/elements/1/Group/elements/2"),
        SLOT("$ROOT_POINTER/Group/elements/1/Group/elements/2/Group/elements/0"),
        LABEL("$ROOT_POINTER/Group/elements/1/Group/elements/2/Group/elements/1"),
        CHECK("$ROOT_POINTER/Group/elements/1/Group/elements/2/Group/elements/2"),
        FILL("$ROOT_POINTER/Group/elements/1/Group/elements/2/Group/elements/3"),
    }

    @Test
    fun theCheckerStatesA12sDottedPathAndUnderscoreClasses() {
        val observed = ROWS.mapValues { (_, row) -> model(row).let { kernel(it) to ours(it) } }
        assertEquals(observed.mapValues { it.value.first to it.value.first }, observed, "each row as (A12, ours)")
        // A12's own classes on the rows that separate the readings, so the agreement cannot follow a drift on both
        // sides; the group-path sentences every row also carries are KF580's and left out here.
        val pinned = PINNED.mapValues { (id, _) ->
            observed.getValue(id).first.map { it.substringBefore('@') }.filter { it != GROUP_SENTENCE }.sorted()
        }
        assertEquals(PINNED.mapValues { it.value.sorted() }, pinned)
    }

    /**
     * A blank name leaves the model unbuilt, and the checker says so; a no-break space is not blank, so its row
     * is an ordinary agreement row. A blank name an `ONLY` filter deletes is built.
     */
    @Test
    fun aBlankNameLeavesTheModelUnbuilt() {
        val wrong = BLANKS.flatMap { blank -> At.entries.map { at -> blank to at } }.mapNotNull { (blank, at) ->
            val json = model(Row(mapOf(at to blank)))
            val a12 = kernel(json)
            val ours = ours(json)
            val label = "$at named ${blank.map { "U+%04X".format(it.code) }}"
            when {
                a12 != listOf(UNBUILT) -> "$label: A12 built it: $a12"
                UNBUILT !in ours.map { it.substringBefore('@') } -> "$label: ours $ours"
                else -> null
            }
        }
        assertEquals(emptyList<String>(), wrong)
        // The computed field is left out: its renamed relative path is LEXED, and the lexer refusal it draws is
        // CONSISTENCY-GAPS DG143, not a name law.
        for (at in At.entries - At.LABEL) {
            val json = model(Row(mapOf(at to "\u00A0")))
            assertEquals(kernel(json), ours(json), "$at named a no-break space")
        }
        for (at in listOf(At.LABEL, At.CHECK, At.FILL)) {
            val json = model(Row(mapOf(at to " "), computation = at != At.LABEL, only = at))
            assertEquals(emptyList<String>(), kernel(json), "$at blank but deleted: A12 builds it")
            assertTrue(UNBUILT !in ours(json).map { it.substringBefore('@') }, "$at blank but deleted: ours")
        }
    }

    private val oracle = KernelConsistencyOracle()
    private val mapper = ObjectMapper()

    private val base: String = DocumentModelDraft.named("order-paths").locale("en_US")
        .rootGroup("Order") { order -> order.stringField("Reference") { }
            .group("Part") { part -> part.stringField("Sibling") { }.stringField("Code") { }
                .group("Bin") { bin -> bin.stringField("Slot") { }.stringField("Label") { } } } }
        .build().toJson()
        .let { RuleValidator().inject(it, DraftRule("/Order/Part/Bin", "Check", "../Slot", "FieldFilled(Slot)", "C1", "ERROR")) }
        .let { json ->
            val tree = mapper.readTree(json)
            val computation = (tree.at(At.BIN.pointer + "/Group/elements") as ArrayNode).addObject()
                .put("type", "Computation").put("name", "Fill").put("id", "/Order/Part/Bin/Fill")
            val body = computation.putObject("Computation").put("computedFieldRelPath", "../Label")
                .put("commonPrecondition", "FieldFilled(Slot)")
            body.putArray("computationAlternatives").addObject().put("precondition", "FieldFilled(Slot)")
                .put("operation", "\"bin\"")
            body.putArray("errorMessage").addObject().put("locale", "en_US").put("text", "Label from the slot")
            mapper.writeValueAsString(tree)
        }

    private fun model(row: Row): String {
        val tree = mapper.readTree(base)
        val fill = tree.at(At.FILL.pointer + "/Computation") as ObjectNode
        row.names[At.LABEL]?.let { fill.put("computedFieldRelPath", "../$it") }
        row.operation?.let { (fill.path("computationAlternatives")[0] as ObjectNode).put("operation", it) }
        if (row.indexedByLabel) (tree.at(At.BIN.pointer + "/Group") as ObjectNode).put("repeatability", 5)
            .put("indexFieldName", "Label")
        row.only?.let { at ->
            val element = tree.at(at.pointer) as ObjectNode
            (element.path(element.path("type").asText()) as ObjectNode).putObject("variantFilter")
                .put("filterType", "ONLY").putArray("variants").add("Wholesale")
        }
        if (!row.computation) (tree.at(At.BIN.pointer + "/Group/elements") as ArrayNode).remove(3)
        // Renamed bottom-up, so each pointer still names the element it named in the drafted tree.
        for ((at, name) in row.names.entries.sortedByDescending { it.key.pointer.length }) {
            (tree.at(at.pointer) as ObjectNode).put("name", name)
        }
        return mapper.writeValueAsString(tree)
    }

    private fun kernel(json: String): List<String> = try {
        oracle.checkConsistency(json, ReferenceResolvers.selfContained())
    } catch (unbuildable: ConsistencyCheckFailedException) {
        return listOf(UNBUILT)
    } catch (unbuildable: IllegalArgumentException) {
        // An empty ROOT name fails one step earlier, as an illegal relative path rather than a blank name.
        return listOf(UNBUILT)
    }.map { notification ->
        val message = notification.message().trim()
        val at = ATTACHED.find(message)?.groupValues?.get(1) ?: "(unattached)"
        val code = Regex("\\[(\\w+)]$").find(message)?.groupValues?.get(1)
            ?: codelessClass(message).let { if (it == UNKEYED_A12_CLASS) "codeless:$message" else it }
        "$code@$at"
    }.sorted()

    private fun ours(json: String): List<String> =
        check(ModelSource(json)).findings.filter { it.justifiesRejection }.map { "${it.code}@${it.at}" }.sorted()

    private companion object {
        const val ROOT_POINTER = "/content/modelRoot/rootGroups/0"
        const val UNBUILT = "CK_MODEL_NOT_BUILDABLE"
        const val PATH = "MVK_PATH_INVALID"
        const val INVALID_FIELDNAME = "MVK_ERROR_INVALID_FIELDNAME"
        const val GROUP_SENTENCE = "RK_GROUP_PATH_INVALID"
        const val XML = "MVK_NAME_STARTS_WITH_XML"
        const val UNDERSCORE = "NAME_STARTS_WITH_UNDERSCORE_AND_WRONG_PATTERN"
        val ATTACHED = Regex("^(?:Group|Field|Rule) with path '([^']*)': ")
        val BLANKS = listOf("", " ", "\t", "\n", "\u2003")

        val ROWS = linkedMapOf(
            "control" to Row(),
            "a dotted part" to Row(mapOf(At.PART to "Co.de")),
            "a dotted part, nothing computed" to Row(mapOf(At.PART to "Co.de"), computation = false),
            "a leading dot in the part" to Row(mapOf(At.PART to ".Code")),
            "a colon in the part" to Row(mapOf(At.PART to "Co:de")),
            "a colon in the part, nothing computed" to Row(mapOf(At.PART to "Co:de"), computation = false),
            "a dotted root" to Row(mapOf(At.ROOT to "Co.de")),
            "a colon in the root" to Row(mapOf(At.ROOT to "Co:de")),
            "a dotted bin" to Row(mapOf(At.BIN to "Co.de")),
            "a hyphenated bin, the control" to Row(mapOf(At.BIN to "Co-de")),
            "a dotted computed field" to Row(mapOf(At.LABEL to "La.bel")),
            "a colon in the computed field" to Row(mapOf(At.LABEL to "Ns:Label")),
            "a dotted field nothing computes" to Row(mapOf(At.SLOT to "Sl.ot")),
            "a dotted part beside a refused operation" to Row(mapOf(At.PART to "Co.de"), operation = "[Nope]"),
            "a dotted computed field beside a refused operation" to Row(mapOf(At.LABEL to "La.bel"), operation = "[Nope]"),
            "a dotted part beside an index target" to Row(mapOf(At.PART to "Co.de"), indexedByLabel = true),
            "an index target, the control" to Row(indexedByLabel = true),
            "a dotted part beside a suppressed computation" to Row(mapOf(At.PART to "Co.de"), only = At.FILL),
            "an xml part" to Row(mapOf(At.PART to "xmlCode")),
            "an underscore bin below an xml part" to Row(mapOf(At.PART to "xmlCode", At.BIN to "_Bin")),
            "an underscore bin below an upper-case xml part" to Row(mapOf(At.PART to "XMLCode", At.BIN to "_Bin")),
            "an underscore part below an xml root" to Row(mapOf(At.ROOT to "xmlOrder", At.PART to "_Part")),
            "an xml bin below an underscore part" to Row(mapOf(At.PART to "_Code", At.BIN to "xmlBin")),
            "an xml bin below an xml part" to Row(mapOf(At.PART to "xmlCode", At.BIN to "xmlBin")),
            "an underscore slot below an xml part" to Row(mapOf(At.PART to "xmlCode", At.SLOT to "_Slot")),
            "an underscore bin below an underscore part" to Row(mapOf(At.PART to "_Code", At.BIN to "_Bin")),
        )

        val PINNED = mapOf(
            "a dotted part" to listOf(INVALID_FIELDNAME, PATH),
            "a colon in the root" to listOf(INVALID_FIELDNAME, PATH, "RK_ROOT_GROUP_NAME_ALPHABET"),
            "a dotted computed field" to listOf(INVALID_FIELDNAME, "MVK_NAME_INVALID_DOT_COLON_A12_DV"),
            "a dotted part beside a refused operation" to listOf("MVK_INVALID_ENTITY", PATH),
            "a dotted part beside an index target" to listOf("MVK_ERROR_INDEX_AS_CALCULATED_FIELD", PATH),
            "a dotted part beside a suppressed computation" to listOf(PATH),
            "an underscore bin below an xml part" to listOf(XML, XML, UNDERSCORE),
        )
    }
}

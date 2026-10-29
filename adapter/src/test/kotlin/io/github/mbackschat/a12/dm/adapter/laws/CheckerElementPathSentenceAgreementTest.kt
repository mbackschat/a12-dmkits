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
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * The codeless sentences A12 states about the names an element path is made of (KERNEL-FINDINGS KF580), against the
 * portable checker, element by element: each row compares every class A12 reports, with the path it attaches to.
 *
 * The rows separate the three alphabets (root group, nested group, field), the reach of the group-path sentence (every
 * group, field, rule and computation beneath a bad group, none above it), the Java trim of the whole judged value
 * (which a trailing blank on a nested group's name escapes for its own path and its direct children only), the
 * 60-character name part, and the two carriers left to A12: a supplementary character, where the charset sentence
 * takes every sentence's place, and a name carrying `/`, where the checker states none.
 *
 * Own domain (an order with its parts and storage bins), built by editing the drafted model's JSON, because the typed
 * builder refuses every defective name here outright.
 */
class CheckerElementPathSentenceAgreementTest {

    private enum class At(val pointer: String) {
        ROOT("/content/modelRoot/rootGroups/0"),
        PART("/content/modelRoot/rootGroups/0/Group/elements/1"),
        CODE("/content/modelRoot/rootGroups/0/Group/elements/1/Group/elements/1"),
        BIN("/content/modelRoot/rootGroups/0/Group/elements/1/Group/elements/2"),
        SLOT("/content/modelRoot/rootGroups/0/Group/elements/1/Group/elements/2/Group/elements/0"),
        CHECK("/content/modelRoot/rootGroups/0/Group/elements/1/Group/elements/2/Group/elements/2"),
        FILL("/content/modelRoot/rootGroups/0/Group/elements/1/Group/elements/2/Group/elements/3"),
    }

    private data class Row(
        val names: Map<At, String>,
        val only: At? = null,
        val slash: Boolean = false,
        val computation: Boolean = true,
    )

    private val rows = linkedMapOf<String, Row>().apply {
        put("control", Row(emptyMap()))
        val names = linkedMapOf(
            "an accent" to "Codeé", "a hyphen" to "Co-de", "a leading digit" to "0Code", "a leading umlaut" to "äCode",
            "an inner umlaut" to "Coäde", "a leading underscore" to "_Code", "a leading dot" to ".Code",
            "an inner dot" to "Co.de", "an inner space" to "Co de", "a leading space" to " Code",
            "a trailing space" to "Code ", "a leading tab" to "\tCode", "a trailing tab" to "Code\t",
            "a leading no-break space" to "\u00A0Code", "an Arabic digit" to "Co\u0661de",
            "60 characters" to "C" + "o".repeat(59), "61 characters" to "C" + "o".repeat(60),
            "60 characters and a trailing space" to "C" + "o".repeat(59) + " ",
            "60 characters and a trailing no-break space" to "C" + "o".repeat(59) + "\u00A0",
            "a supplementary character" to "Co\uD83D\uDE00de",
        )
        for ((label, name) in names) for (at in listOf(At.ROOT, At.PART, At.BIN, At.CODE)) {
            // A dot on a computed field's path draws a calculation-gate class besides (KF415), outside this subject.
            put("${at.name.lowercase()} with $label", Row(mapOf(at to name), computation = '.' !in name || at == At.CODE))
        }
        put("a bad field under a bad group", Row(mapOf(At.PART to "Codeé", At.CODE to "Codeé")))
        put("a bad group under a bad root", Row(mapOf(At.ROOT to "Codeé", At.PART to "Codeé")))
        put("a bad rule and a computation under a bad group", Row(mapOf(At.PART to "Codeé", At.CHECK to "1Check")))
        put("a supplementary field under a bad group", Row(mapOf(At.PART to "Codeé", At.CODE to "Co\uD83D\uDE00de")))
        for ((label, name) in linkedMapOf("a digit" to "0Code", "an xml prefix" to "xmlCode", "a dot" to "Co.de")) {
            put("a field led by $label under a bad group", Row(mapOf(At.PART to "Codeé", At.CODE to name)))
        }
        for ((label, name) in linkedMapOf("an xml prefix" to "xmlCode", "an underscore" to "_Code", "a digit" to "0Code")) {
            put("a group led by $label under a bad group", Row(mapOf(At.PART to "Codeé", At.BIN to name)))
        }
        put("a bad group above a rule deleted at the default variant", Row(mapOf(At.PART to "Codeé"), only = At.CHECK))
        put("a bad group above a field deleted at the default variant", Row(mapOf(At.PART to "Codeé"), only = At.SLOT))
        put("a bad field deleted at the default variant", Row(mapOf(At.SLOT to "Slo-t"), only = At.SLOT))
        put("a bad group above a computation deleted at the default variant", Row(mapOf(At.PART to "Codeé"), only = At.FILL))
        for (at in listOf(At.ROOT, At.PART, At.CODE)) for (name in listOf("Co/-de", "-Co/de")) {
            put("${at.name.lowercase()} named $name", Row(mapOf(at to name), slash = true))
        }
    }

    @Test
    fun theCheckerStatesA12sPathSentencesAtEveryElement() {
        val observed = rows.mapValues { (_, row) -> model(row).let { kernel(it) to ours(it) } }
        val agreeing = rows.filterValues { !it.slash }.keys
        assertEquals(observed.filterKeys { it in agreeing }.mapValues { (_, verdict) -> verdict.first to verdict.first },
            observed.filterKeys { it in agreeing }, "each row as (A12, ours)")
        // A12's own answer on the rows that separate the alphabets, the reach and the trim, so the agreement cannot
        // follow a drift on both sides.
        assertEquals(linkedMapOf(
            "control" to emptyList(),
            "root with an accent" to listOf("$PATH@/Codeé", "$ROOT_ALPHABET@/Codeé") +
                listOf("/Codeé/Part", "/Codeé/Part/Bin", "/Codeé/Part/Bin/Check", "/Codeé/Part/Bin/Fill",
                    "/Codeé/Part/Bin/Label", "/Codeé/Part/Bin/Slot", "/Codeé/Part/Code", "/Codeé/Part/Sibling",
                    "/Codeé/Reference").map { "$GROUP@$it" },
            "root with a leading digit" to listOf("MVK_NAME_STARTS_WITH_DIGIT@/0Code"),
            "part with a leading umlaut" to listOf("$PATH@/Order/äCode") +
                listOf("/Order/äCode", "/Order/äCode/Bin", "/Order/äCode/Bin/Check", "/Order/äCode/Bin/Fill",
                    "/Order/äCode/Bin/Label", "/Order/äCode/Bin/Slot", "/Order/äCode/Code", "/Order/äCode/Sibling")
                    .map { "$GROUP@$it" },
            "part with an inner umlaut" to emptyList(),
            "code with an inner umlaut" to listOf("$PATH@/Order/Part/Coäde", "$FIELD_ALPHABET@/Order/Part/Coäde"),
            "code with a leading digit" to listOf("$PATH@/Order/Part/0Code", "MVK_NAME_STARTS_WITH_DIGIT_A12_DV@/Order/Part/0Code"),
            "part with a trailing space" to listOf("$PATH@/Order/Code ") +
                listOf("/Order/Code /Bin", "/Order/Code /Bin/Check", "/Order/Code /Bin/Fill", "/Order/Code /Bin/Label",
                    "/Order/Code /Bin/Slot").map { "$GROUP@$it" },
            "root with 61 characters" to listOf("$LENGTH@/C${"o".repeat(60)}") +
                listOf("Part", "Part/Bin", "Part/Bin/Check", "Part/Bin/Fill", "Part/Bin/Label", "Part/Bin/Slot",
                    "Part/Code", "Part/Sibling", "Reference").map { "$GROUP@/C${"o".repeat(60)}/$it" },
            "root with 60 characters and a trailing no-break space" to
                listOf("$PATH@/C${"o".repeat(59)}\u00A0", "$ROOT_ALPHABET@/C${"o".repeat(59)}\u00A0") +
                listOf("Part", "Part/Bin", "Part/Bin/Check", "Part/Bin/Fill", "Part/Bin/Label", "Part/Bin/Slot",
                    "Part/Code", "Part/Sibling", "Reference").map { "$GROUP@/C${"o".repeat(59)}\u00A0/$it" },
            "a bad group above a rule deleted at the default variant" to listOf("$PATH@/Order/Codeé") +
                listOf("/Order/Codeé", "/Order/Codeé/Bin", "/Order/Codeé/Bin/Fill", "/Order/Codeé/Bin/Label",
                    "/Order/Codeé/Bin/Slot", "/Order/Codeé/Code", "/Order/Codeé/Sibling").map { "$GROUP@$it" },
        ).mapValues { (_, classes) -> classes.sorted() }, observed.filterKeys { it in PINNED }.mapValues { it.value.first })
        // A name carrying `/` is left to A12: the checker's verdict stays the coded class, and it states no sentence.
        for ((label, verdict) in observed.filterKeys { rows.getValue(it).slash }) {
            assertNotEquals(verdict.first, verdict.second, "$label: A12 states sentences the checker leaves to it")
            assertEquals(emptyList<String>(), verdict.second.filter { it.substringBefore('@') in SENTENCES }, label)
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
        if (!row.computation) (tree.at(At.BIN.pointer + "/Group/elements") as ArrayNode).remove(3)
        row.only?.let { at ->
            val element = tree.at(at.pointer) as ObjectNode
            (element.path(element.path("type").asText()) as ObjectNode).putObject("variantFilter")
                .put("filterType", "ONLY").putArray("variants").add("Wholesale")
        }
        // Renamed bottom-up, so each pointer still names the element it named in the drafted tree.
        for ((at, name) in row.names.entries.sortedByDescending { it.key.pointer.length }) {
            (tree.at(at.pointer) as ObjectNode).put("name", name)
        }
        return mapper.writeValueAsString(tree)
    }

    private fun kernel(json: String): List<String> = try {
        oracle.checkConsistency(json, ReferenceResolvers.selfContained())
    } catch (unbuildable: ConsistencyCheckFailedException) {
        return listOf("CK_MODEL_NOT_BUILDABLE@/")
    }.map { notification ->
            val message = notification.message().trim()
            val at = ATTACHED.find(message)?.groupValues?.get(1) ?: "(unattached)"
            val code = legibleNotificationClass(message)
        "$code@$at"
        }.sorted()

    private fun ours(json: String): List<String> =
        check(ModelSource(json)).findings.filter { it.justifiesRejection }.map { "${it.code}@${it.at}" }.sorted()

    private companion object {
        const val PATH = "MVK_PATH_INVALID"
        const val GROUP = "RK_GROUP_PATH_INVALID"
        const val ROOT_ALPHABET = "RK_ROOT_GROUP_NAME_ALPHABET"
        const val FIELD_ALPHABET = "RK_FIELD_NAME_ALPHABET"
        const val LENGTH = "RK_MEMBER_ABOVE_60_CHARACTERS"
        val SENTENCES = setOf(GROUP, ROOT_ALPHABET, FIELD_ALPHABET, LENGTH)
        val ATTACHED = Regex("^(?:Group|Field|Rule) with path '([^']*)': ")
        val PINNED = setOf("control", "root with an accent", "root with a leading digit", "part with a leading umlaut",
            "part with an inner umlaut", "code with an inner umlaut", "code with a leading digit",
            "part with a trailing space", "root with 61 characters", "root with 60 characters and a trailing no-break space",
            "a bad group above a rule deleted at the default variant")
    }
}

package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.mbackschat.a12.dm.adapter.KernelConsistencyOracle
import io.github.mbackschat.a12.dm.core.ReferenceResolvers
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * How often A12 repeats a coded class it draws once per carrier, against the portable checker (KERNEL-FINDINGS
 * KF558). A root or nested group whose name begins with `xml` draws `MVK_NAME_STARTS_WITH_XML` once for itself and
 * once for every group below it, fields not counted, and not at all when the path is refused above it; `RULE_HAS_NO_ERRORTEXT` comes once per declared locale
 * without a text; the opaque-string, unspecified-type and category-duplicate classes once per field or category.
 * The codeless repeated-value sentence an enum draws beside its duplicate class is deliberately unpublished
 * ([KF551]).
 *
 * Own domain (an insurance claim and its parties).
 */
class CheckerClassMultiplicityAgreementTest {

    private val mapper = ObjectMapper()
    private val oracle = KernelConsistencyOracle()

    private fun draft(root: String = "Claim", bilingual: Boolean = false): ObjectNode {
        val json = DocumentModelDraft.named("claim-multiplicity").locale("en_US")
            .rootGroup(root) { c -> c.stringField("Reference") { }.stringField("Note") { }
                .group("Parties") { p -> p.stringField("Insurer") { }.stringField("Broker") { } } }
            .build().toJson()
        val node = mapper.readTree(json) as ObjectNode
        if (bilingual) (node.path("header").path("locales") as ArrayNode).addObject().put("code", "de_DE")
        return node
    }

    private fun fields(root: ObjectNode) =
        root.path("content").path("modelRoot").path("rootGroups")[0].path("Group").path("elements") as ArrayNode

    private fun field(root: ObjectNode, name: String) = fields(root).first { it.path("name").asText() == name }.path("Field") as ObjectNode

    private val rows: Map<String, String> by lazy {
        linkedMapOf(
            "a root group named xml" to draft(root = "xmlClaim").toString(),
            "two opaque strings without line breaks" to draft().also { r ->
                for (name in listOf("Reference", "Note")) field(r, name).putObject("fieldType").put("type", "StringType")
                    .putObject("StringType").put("noValueValidation", true)
            }.toString(),
            "two unspecified fields" to draft().also { r ->
                for (name in listOf("Reference", "Note")) field(r, name).putObject("fieldType").put("type", "UnspecifiedType")
            }.toString(),
            "a rule with no message in two locales" to draft(bilingual = true).also { r ->
                val rule = fields(r).addObject().put("type", "Rule").put("id", "r1").put("name", "Probe")
                rule.putObject("Rule").put("errorCondition", "FieldFilled(Reference)").put("errorEntityRelPath", "../Reference")
                    .put("errorCode", "C1").put("severity", "ERROR").putArray("errorMessage")
            }.toString(),
            "a message under an empty locale code, two locales" to draft(bilingual = true).also { r ->
                val rule = fields(r).addObject().put("type", "Rule").put("id", "r1").put("name", "Probe")
                rule.putObject("Rule").put("errorCondition", "FieldFilled(Reference)").put("errorEntityRelPath", "../Reference")
                    .put("errorCode", "C1").put("severity", "ERROR").putArray("errorMessage").addObject()
                    .put("locale", "").put("text", "Check it.")
            }.toString(),
            "blank texts in two locales" to draft(bilingual = true).also { r ->
                val rule = fields(r).addObject().put("type", "Rule").put("id", "r1").put("name", "Probe")
                val messages = rule.putObject("Rule").put("errorCondition", "FieldFilled(Reference)")
                    .put("errorEntityRelPath", "../Reference").put("errorCode", "C1").put("severity", "ERROR").putArray("errorMessage")
                messages.addObject().put("locale", "en_US").put("text", "")
                messages.addObject().put("locale", "de_DE").put("text", "")
            }.toString(),
            // Kotlin's isBlank reads a no-break space as blank where Java's String.isBlank does not.
            "no-break-space texts in two locales" to draft(bilingual = true).also { r ->
                val rule = fields(r).addObject().put("type", "Rule").put("id", "r1").put("name", "Probe")
                val messages = rule.putObject("Rule").put("errorCondition", "FieldFilled(Reference)")
                    .put("errorEntityRelPath", "../Reference").put("errorCode", "C1").put("severity", "ERROR").putArray("errorMessage")
                messages.addObject().put("locale", "en_US").put("text", "\u00A0")
                messages.addObject().put("locale", "de_DE").put("text", "\u2007")
            }.toString(),
            "an enum value repeated under two categories" to draft().also { r ->
                val enumeration = field(r, "Note").putObject("fieldType").put("type", "EnumerationType")
                    .putObject("EnumerationType")
                val values = enumeration.putArray("values")
                listOf("OPEN", "OPEN").forEach { values.addObject().put("value", it) }
                val categories = enumeration.putArray("categories")
                listOf("Band", "Segment").forEach { name ->
                    categories.addObject().put("name", name).putArray("values").add("A").add("B")
                }
            }.toString(),
        )
    }

    private fun xmlRoot(build: (DocumentModelDraft.GroupBuilder) -> DocumentModelDraft.GroupBuilder) =
        DocumentModelDraft.named("claim-xml").locale("en_US").rootGroup("xmlClaim") { build(it) }.build().toJson()

    private val xmlRows = linkedMapOf(
        "xml root, one field" to xmlRoot { it.stringField("Reference") { } },
        "xml root, three fields" to xmlRoot { it.stringField("A") { }.stringField("B") { }.stringField("C") { } },
        "xml root, one group" to xmlRoot { it.stringField("A") { }.group("G") { g -> g.stringField("X") { } } },
        "xml root, two groups" to xmlRoot { it.stringField("A") { }.group("G") { g -> g.stringField("X") { } }
            .group("H") { g -> g.stringField("Y") { } } },
        "xml root, nested groups" to xmlRoot { it.stringField("A") { }.group("G") { g -> g.stringField("X") { }
            .group("K") { k -> k.stringField("Z") { } } } },
        "a nested xml group with a child group" to DocumentModelDraft.named("claim-xml").locale("en_US")
            .rootGroup("Claim") { it.stringField("A") { }.group("xmlG") { g -> g.stringField("X") { }
                .group("K") { k -> k.stringField("Z") { } } } }.build().toJson(),
        "xml root, a group with two fields" to xmlRoot { it.stringField("A") { }.group("G") { g -> g.stringField("X") { }.stringField("W") { } } },
        "an xml root its own path refuses, with a child group" to DocumentModelDraft.named("claim-xml").locale("en_US")
            .rootGroup("xmlClaimé") { it.stringField("A") { }.group("G") { g -> g.stringField("X") { } } }.build().toJson(),
    )

    /** Every row's classes, with their multiplicity. */
    @Test
    fun everyRowAgrees() {
        val wrong = (rows + xmlRows).mapNotNull { (id, json) ->
            val a12 = kernel(json).filterNot { it == UNKEYED_A12_CLASS }
            if (a12 == ours(json)) null else "$id: A12 $a12, ours ${ours(json)}"
        }
        assertEquals(emptyList<String>(), wrong)
    }

    /** The multiplicities themselves, pinned on A12, so the agreement above is not agreement on nothing. */
    @Test
    fun theRepeatsAreObservable() {
        fun count(id: String, code: String) = kernel((rows + xmlRows).getValue(id)).count { it == code }
        assertEquals(1, count("xml root, three fields", XML))
        assertEquals(3, count("xml root, two groups", XML))
        assertEquals(3, count("xml root, nested groups", XML))
        assertEquals(2, count("a nested xml group with a child group", XML))
        assertEquals(0, count("an xml root its own path refuses, with a child group", XML))
        assertEquals(2, count("a rule with no message in two locales", NO_TEXT))
        assertEquals(2, count("a message under an empty locale code, two locales", NO_TEXT))
        assertEquals(2, count("two opaque strings without line breaks", "MVK_STRING_TYPE_NO_VAL_AND_NO_LINE_BREAK"))
        assertEquals(2, count("two unspecified fields", "MVK_UNSPECIFIED_TYPE_NO_CODEGEN"))
        assertEquals(2, count("an enum value repeated under two categories", "MVK_CATEGORY_VALUE_DUPLICATE"))
    }

    private fun kernel(json: String): List<String> = oracle.checkConsistency(json, ReferenceResolvers.selfContained())
        .mapNotNull { Regex("\\[(\\w+)]$").find(it.message().trim())?.groupValues?.get(1) ?: codelessClass(it.message()) }
        .sorted()

    private fun ours(json: String): List<String> =
        rejectingCodes(json).sorted()

    private companion object {
        const val XML = "MVK_NAME_STARTS_WITH_XML"
        const val NO_TEXT = "RULE_HAS_NO_ERRORTEXT"
    }
}

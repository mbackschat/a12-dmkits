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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * What one declaration's report withholds and what still stands, and how often a `sortFields` list speaks
 * (KERNEL-FINDINGS KF339 and KF345, a12-kernel-lean SPEC-2026-10-13-08 and -11), against the portable checker,
 * with the notification list's multiplicity kept.
 *
 * A rule whose error target names nothing reports its target pair, and a refused condition its own class; beside
 * either, the rule's name, its code and its message's presence classes stand, while the condition's class and
 * every error-text class, `RULE_HAS_NO_ERRORTEXT` included, are withheld. A `sortFields` list judges each distinct
 * name once wherever it stands, its duplicate class reads only the three entries it holds, and every entry beyond
 * those draws a codeless capacity sentence. `The error code is empty.`, which an absent or zero-length code draws
 * beside `MVK_ERROR_CODE_MISSING` and alone on a rule an `ONLY` filter deletes, is published as
 * `RK_ERROR_CODE_EMPTY` (KF579), and an unbuildable model only has to be reported so.
 * The -11 rows add a model with no declared locale, a group as the error target, two texts for one locale in either
 * order, and a five-entry sort list, which draws the capacity sentence twice.
 *
 * Own domain (a claims policy and its items), built by editing the drafted model's JSON, because the typed
 * builder refuses every defective shape here outright.
 */
class CheckerDeclarationWithholdingAgreementTest {

    private data class RuleRow(
        val condition: String = "FieldFilled(Reference)",
        val target: String = "../Reference",
        val name: String = "Probe",
        val code: String? = "C1",
        val texts: Map<String, String> = mapOf("en_US" to "see it"),
        val bilingual: Boolean = false,
        val extra: List<Pair<String, String>> = emptyList(),
        val noLocale: Boolean = false,
        val only: Boolean = false,
        val allExcept: Boolean = false,
    )

    private val tab = "see" + Char(9) + "it"

    private val ruleRows = linkedMapOf(
        "control" to RuleRow(),
        "target" to RuleRow(target = "../NoSuchField"),
        "target, reserved name" to RuleRow(target = "../NoSuchField", name = "vk_vkProbe"),
        "target, no code" to RuleRow(target = "../NoSuchField", code = null),
        "target, blank code" to RuleRow(target = "../NoSuchField", code = ""),
        "target, no message" to RuleRow(target = "../NoSuchField", texts = emptyMap()),
        "target, blank text" to RuleRow(target = "../NoSuchField", texts = mapOf("en_US" to "")),
        "target, tab" to RuleRow(target = "../NoSuchField", texts = mapOf("en_US" to tab)),
        "target, second locale missing" to RuleRow(target = "../NoSuchField", bilingual = true),
        "target, broken condition" to RuleRow(target = "../NoSuchField", condition = "FieldFilled(NoSuchField)"),
        "condition" to RuleRow(condition = "FieldFilled(NoSuchField)"),
        "condition, reserved name" to RuleRow(condition = "FieldFilled(NoSuchField)", name = "vk_vkProbe"),
        "condition, no code" to RuleRow(condition = "FieldFilled(NoSuchField)", code = null),
        "condition, no message" to RuleRow(condition = "FieldFilled(NoSuchField)", texts = emptyMap()),
        "condition, blank text" to RuleRow(condition = "FieldFilled(NoSuchField)", texts = mapOf("en_US" to "")),
        "condition, tab" to RuleRow(condition = "FieldFilled(NoSuchField)", texts = mapOf("en_US" to tab)),
        "condition, second locale missing" to RuleRow(condition = "FieldFilled(NoSuchField)", bilingual = true),
        "condition, two defects" to RuleRow(condition = "FieldFilled(NoSuchField) And [Premium] > \"x\""),
        "condition, two defects reversed" to RuleRow(condition = "[Premium] > \"x\" And FieldFilled(NoSuchField)"),
        "unreferenced" to RuleRow(condition = "FieldFilled(Premium)"),
        "unreferenced, tab" to RuleRow(condition = "FieldFilled(Premium)", texts = mapOf("en_US" to tab)),
        "unreferenced, no message" to RuleRow(condition = "FieldFilled(Premium)", texts = emptyMap()),
        "unreferenced, reserved name" to RuleRow(condition = "FieldFilled(Premium)", name = "vk_vkProbe"),
        "twin reserved name" to RuleRow(name = "vk_vkProbe"),
        "twin no code" to RuleRow(code = null),
        "twin empty code" to RuleRow(code = ""),
        "twin blank code" to RuleRow(code = " "),
        "twin tab code" to RuleRow(code = "\t"),
        "no code, deleted at the default variant" to RuleRow(code = null, only = true),
        "empty code, deleted at the default variant" to RuleRow(code = "", only = true),
        "blank code, deleted at the default variant" to RuleRow(code = " ", only = true),
        "tab code, deleted at the default variant" to RuleRow(code = "\t", only = true),
        "empty code beside a broken target, deleted" to RuleRow(target = "../NoSuchField", code = "", only = true),
        "no code, all but one variant" to RuleRow(code = null, allExcept = true),
        "empty code, all but one variant" to RuleRow(code = "", allExcept = true),
        "blank code, all but one variant" to RuleRow(code = " ", allExcept = true),
        "twin no message" to RuleRow(texts = emptyMap()),
        "twin blank text" to RuleRow(texts = mapOf("en_US" to "")),
        "twin tab" to RuleRow(texts = mapOf("en_US" to tab)),
        "twin second locale missing" to RuleRow(bilingual = true),
        "no locale, a text" to RuleRow(noLocale = true),
        "no locale, no text" to RuleRow(noLocale = true, texts = emptyMap()),
        "group target" to RuleRow(target = "../Items"),
        "group target, tab" to RuleRow(target = "../Items", texts = mapOf("en_US" to tab)),
        "group target, unresolved name" to RuleRow(target = "../Items", condition = "FieldFilled(NoSuchField)"),
        "group target, read inside" to RuleRow(target = "../Items", condition = "FieldFilled(Items/Kind)"),
        "same locale twice, tab then plain" to RuleRow(texts = mapOf("en_US" to tab), extra = listOf("en_US" to "see it")),
        "same locale twice, plain then tab" to RuleRow(extra = listOf("en_US" to tab)),
        "same locale twice, empty then plain" to RuleRow(texts = mapOf("en_US" to ""), extra = listOf("en_US" to "see it")),
        "same locale twice, plain then empty" to RuleRow(extra = listOf("en_US" to "")),
        "two locales without a text" to RuleRow(bilingual = true, texts = emptyMap(), extra = listOf("en_US" to "")),
    )

    private val sortRows = linkedMapOf(
        "sort plain" to listOf("Kind"),
        "sort one opaque" to listOf("Memo"),
        "sort two opaque" to listOf("Memo", "Remark"),
        "sort opaque, outside name" to listOf("Memo", "../Reference"),
        "sort outside name, opaque" to listOf("../Reference", "Memo"),
        "sort unknown, opaque" to listOf("NoSuchField", "Memo"),
        "sort plain twice" to listOf("Kind", "Kind"),
        "sort opaque twice" to listOf("Memo", "Memo"),
        "sort opaque thrice" to listOf("Memo", "Memo", "Memo"),
        "sort four entries" to listOf("Kind", "Label", "Memo", "Remark"),
        "sort five entries" to listOf("Kind", "Label", "Memo", "Remark", "Kind"),
        "sort two names twice" to listOf("Kind", "Kind", "Label", "Label"),
        "sort two opaque names twice" to listOf("Memo", "Memo", "Remark", "Remark"),
        "sort opaque repeated beside another" to listOf("Memo", "Remark", "Memo"),
        "sort outside name twice" to listOf("../Reference", "../Reference"),
        "sort repeat within three" to listOf("Kind", "Label", "Kind"),
        "sort repeat at the fourth" to listOf("Kind", "Label", "Memo", "Kind"),
        "sort opaque repeat at the fourth" to listOf("Memo", "Kind", "Label", "Memo"),
        "sort repeat only beyond capacity" to listOf("Kind", "Label", "Memo", "Remark", "Remark"),
    )

    private val computationRows = linkedMapOf(
        "computation broken, reserved name" to Triple("[NoSuchField] + 1", "vk_vkProbe", "see it"),
        "computation broken, tab" to Triple("[NoSuchField] + 1", "TotalCalc", tab),
        "computation twin tab" to Triple("[Premium] + 1", "TotalCalc", tab),
    )

    private val models: Map<String, String> by lazy {
        ruleRows.mapValues { rule(it.value) } + sortRows.mapValues { sorted(it.value) } +
            computationRows.mapValues { (_, c) -> computation(c.first, c.second, c.third) }
    }

    /** Every row's classes, with their multiplicity; A12's order is not claimed. */
    @Test
    fun everyRowAgrees() {
        val wrong = models.mapNotNull { (id, json) ->
            val a12 = kernel(json)
            val ours = ours(json)
            when {
                a12.any { it.startsWith(UNBUILDABLE) } ->
                    if (UNBUILDABLE in ours) null else "$id: A12 refused to build, ours $ours"
                a12 != ours -> "$id: A12 $a12, ours $ours"
                else -> null
            }
        }
        assertEquals(emptyList<String>(), wrong)
    }

    /** The rows that make the agreement above mean something, pinned on A12 itself. */
    @Test
    fun theWithholdingAndTheMultiplicityAreObservable() {
        fun a12(id: String) = kernel(models.getValue(id))
        assertEquals(listOf(ERROR_FIELD_MISSING, MISSING_ERROR_FIELD, "MVK_RULE_NAME_INVALID"), a12("target, reserved name"))
        assertEquals(listOf(ERROR_FIELD_MISSING, "MVK_ERROR_MESSAGE_MISSING", MISSING_ERROR_FIELD), a12("target, no message"))
        assertEquals(listOf("MVK_ERROR_MESSAGE_MISSING", "RULE_HAS_NO_ERRORTEXT"), a12("twin no message"))
        assertEquals(listOf("MVK_INVALID_ENTITY"), a12("condition, tab"))
        assertEquals(listOf("INVALID_CHARACTERS"), a12("twin tab"))
        assertEquals(listOf(SORT_TYPE, SORT_TYPE), a12("sort two opaque"))
        assertEquals(listOf(DUPLICATE, SORT_TYPE), a12("sort opaque thrice"))
        assertEquals(listOf(DUPLICATE, "RK_SORT_LIST_ABOVE_CAPACITY"), a12("sort two names twice"))
        assertEquals(listOf(SORT_TYPE, "RK_SORT_LIST_ABOVE_CAPACITY"), a12("sort opaque repeat at the fourth"))
        assertEquals(listOf("MVK_INVALID_SORT_FIELD", DUPLICATE), a12("sort outside name twice"))
        assertEquals(listOf(SORT_TYPE, SORT_TYPE, "RK_SORT_LIST_ABOVE_CAPACITY", "RK_SORT_LIST_ABOVE_CAPACITY"),
            a12("sort five entries"))
        assertEquals(listOf("MVK_ERROR_MESSAGE_MISSING", "MVK_SUPP_LANGUAGES_MISSING"), a12("no locale, a text"))
        assertEquals(listOf("MVK_ERROR_FIELD_NOT_REFERENCED"), a12("group target, tab"))
        assertEquals(listOf("INVALID_CHARACTERS"), a12("same locale twice, plain then tab"))
        assertEquals(emptyList<String>(), a12("same locale twice, tab then plain"))
        assertEquals(listOf("MVK_ERROR_CODE_MISSING", EMPTY_CODE), a12("twin empty code"))
        assertEquals(listOf("MVK_ERROR_CODE_MISSING"), a12("twin blank code"))
        assertEquals(listOf(EMPTY_CODE), a12("no code, deleted at the default variant"))
        assertEquals(listOf(EMPTY_CODE), a12("empty code, deleted at the default variant"))
        assertEquals(emptyList<String>(), a12("blank code, deleted at the default variant"))
        assertEquals(listOf("MVK_ERROR_CODE_MISSING", EMPTY_CODE), a12("no code, all but one variant"))
        assertEquals(listOf("MVK_ERROR_CODE_MISSING"), a12("blank code, all but one variant"))
    }

    private val oracle = KernelConsistencyOracle()
    private val mapper = ObjectMapper()

    private fun kernel(json: String): List<String> = try {
        oracle.checkConsistency(json, ReferenceResolvers.selfContained())
    } catch (unbuildable: ConsistencyCheckFailedException) {
        return listOf("$UNBUILDABLE: ${unbuildable.message?.substringAfter("model: ")}")
    }
        .map { notification ->
            val message = notification.message().trim()
            Regex("\\[(\\w+)]$").find(message)?.groupValues?.get(1) ?: when {
                else -> codelessClass(message).let { if (it == UNKEYED_A12_CLASS) "codeless:$message" else it }
            }
        }.sorted()

    private fun ours(json: String): List<String> =
        check(ModelSource(json)).findings.filter { it.justifiesRejection }.map { it.code }.sorted()

    private val base: String = DocumentModelDraft.named("claims-policy").locale("en_US")
        .rootGroup("Policy") { p -> p.stringField("Reference") { }.numberField("Premium") { it.fractionDigits(0) }
            .numberField("Total") { it.fractionDigits(0) }
            .group("Items") { i -> i.repeatable(4).stringField("Kind") { }.stringField("Label") { }
                .stringField("Memo") { it.noValueValidation().lineBreaks() }
                .stringField("Remark") { it.noValueValidation().lineBreaks() } } }
        .build().toJson()

    private fun root(bilingual: Boolean): ObjectNode {
        val root = mapper.readTree(base) as ObjectNode
        if (bilingual) (root.path("header").path("locales") as ArrayNode).addObject().put("code", "de_DE")
        return root
    }

    private fun policyElements(root: ObjectNode) =
        root.path("content").path("modelRoot").path("rootGroups")[0].path("Group").path("elements") as ArrayNode

    private fun rule(row: RuleRow): String {
        val root = root(row.bilingual)
        val node = policyElements(root).addObject()
        node.put("type", "Rule").put("id", "rprobe").put("name", row.name)
        val body = node.putObject("Rule")
        body.put("errorCondition", row.condition).put("errorEntityRelPath", row.target).put("severity", "ERROR")
        row.code?.let { body.put("errorCode", it) }
        val messages = body.putArray("errorMessage")
        row.texts.forEach { (locale, text) -> messages.addObject().put("locale", locale).put("text", text) }
        row.extra.forEach { (locale, text) -> messages.addObject().put("locale", locale).put("text", text) }
        if (row.noLocale) (root.path("header") as ObjectNode).putArray("locales")
        if (row.only) body.putObject("variantFilter").put("filterType", "ONLY").putArray("variants").add("Broker")
        if (row.allExcept) {
            body.putObject("variantFilter").put("filterType", "ALL_EXCEPT").putArray("variants").add("Broker")
        }
        return mapper.writeValueAsString(root)
    }

    private fun computation(operation: String, name: String, text: String): String {
        val root = root(false)
        val node = policyElements(root).addObject()
        node.put("type", "Computation").put("id", "cprobe").put("name", name)
        val body = node.putObject("Computation")
        body.put("computedFieldRelPath", "../Total")
        body.putArray("computationAlternatives").addObject().put("operation", operation)
        body.putArray("errorMessage").addObject().put("locale", "en_US").put("text", text)
        return mapper.writeValueAsString(root)
    }

    private fun sorted(names: List<String>): String {
        val root = root(false)
        val items = policyElements(root).first { it.path("name").asText() == "Items" }.path("Group") as ObjectNode
        val list = items.putArray("sortFields")
        names.forEach { list.addObject().put("name", it) }
        return mapper.writeValueAsString(root)
    }

    private companion object {
        const val UNBUILDABLE = "CK_MODEL_NOT_BUILDABLE"
        const val EMPTY_CODE = "RK_ERROR_CODE_EMPTY"
        const val ERROR_FIELD_MISSING = "MVK_ERROR_FIELD_MISSING"
        const val MISSING_ERROR_FIELD = "MVK_MISSING_ERROR_FIELD"
        const val SORT_TYPE = "MVK_SORT_FIELD_NO_VALUE_VALIDATION"
        const val DUPLICATE = "MVK_SORT_FIELD_DUPLICATE"
    }
}

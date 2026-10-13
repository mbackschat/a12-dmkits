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
 * (KERNEL-FINDINGS KF339 and KF345, a12-kernel-lean SPEC-2026-10-13-08), against the portable checker, with the
 * notification list's multiplicity kept.
 *
 * A rule whose error target names nothing reports its target pair, and a refused condition its own class; beside
 * either, the rule's name, its code and its message's presence classes stand, while the condition's class and
 * every error-text class, `RULE_HAS_NO_ERRORTEXT` included, are withheld. A `sortFields` list judges each distinct
 * name once wherever it stands, its duplicate class reads only the three entries it holds, and every entry beyond
 * those draws a codeless capacity sentence. `The error code is empty.`, a codeless sentence that only ever
 * accompanies `MVK_ERROR_CODE_MISSING`, is not published, and an unbuildable model only has to be reported so.
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
        "twin no message" to RuleRow(texts = emptyMap()),
        "twin blank text" to RuleRow(texts = mapOf("en_US" to "")),
        "twin tab" to RuleRow(texts = mapOf("en_US" to tab)),
        "twin second locale missing" to RuleRow(bilingual = true),
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
                EMPTY_CODE in a12 && MISSING_CODE !in a12 -> "$id: the empty-code sentence stands alone in $a12"
                a12.filterNot { it == EMPTY_CODE } != ours -> "$id: A12 $a12, ours $ours"
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
                message.endsWith("The error code is empty.") -> EMPTY_CODE
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
        const val EMPTY_CODE = "codeless:empty-code"
        const val MISSING_CODE = "MVK_ERROR_CODE_MISSING"
        const val ERROR_FIELD_MISSING = "MVK_ERROR_FIELD_MISSING"
        const val MISSING_ERROR_FIELD = "MVK_MISSING_ERROR_FIELD"
        const val SORT_TYPE = "MVK_SORT_FIELD_NO_VALUE_VALIDATION"
        const val DUPLICATE = "MVK_SORT_FIELD_DUPLICATE"
    }
}

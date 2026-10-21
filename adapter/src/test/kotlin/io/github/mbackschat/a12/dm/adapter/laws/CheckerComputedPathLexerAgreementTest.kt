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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A computation's `computedFieldRelPath` is lexed as a condition operand is (KERNEL-FINDINGS KF586), against the
 * portable checker, every class with its multiplicity: a computed field whose name carries a character the
 * condition lexer has no terminal for draws `MVK_LEXER_STANDARD_ERROR` at the computation, beside the name's own
 * classes at the field.
 *
 * The characters are KF575's, with `ß` and `_` as controls the lexer takes. Own domain (an order's bins, one
 * computation filling a bin's label), built by editing the drafted model's JSON, because the typed builder refuses
 * these names.
 */
class CheckerComputedPathLexerAgreementTest {

    @Test
    fun theCheckerStatesA12sLexerClassAtTheComputation() {
        val observed = NAMES.associateWith { name -> model(name).let { kernel(it) to ours(it) } }
        assertEquals(observed.mapValues { it.value.first to it.value.first }, observed, "each name as (A12, ours)")
    }

    /** Where the lexer gate sits among the computed target's other gates: beside the index and dot gates, after a refused operation. */
    @Test
    fun theLexerGateBesideTheComputedTargetsOtherGates() {
        val nbsp = named(0x00A0)
        val rows = mapOf(
            "the index field" to model(nbsp, indexed = true),
            "a dot beside it" to model("La.b" + Char(0x00A0) + "el"),
            "a refused operation" to model(nbsp, operation = "[Nope]"),
            "a refused precondition" to model(nbsp, precondition = "FieldFilled(Nope)"),
            "a rule's target naming the field, no computation" to model(nbsp, computation = false, rule = true),
            "a rule's target naming the field, beside the computation" to model(nbsp, rule = true),
        )
        val observed = rows.mapValues { (_, json) -> kernel(json) to ours(json) }
        assertEquals(observed.mapValues { it.value.first to it.value.first }, observed, "each row as (A12, ours)")
        // The field's sentence and the index member's own (KF589) beside the lexer class.
        assertEquals(listOf("MVK_LEXER_STANDARD_ERROR", "MVK_PATH_INVALID", "RK_FIELD_NAME_ALPHABET", "RK_FIELD_NAME_ALPHABET"),
            observed.getValue("the index field").first)
    }

    /** A rule's error target is a relative path too; a group target keeps its name out of the condition text. */
    @Test
    fun aRulesErrorTargetIsLexedAsWell() {
        val observed = listOf("Bin", "B" + Char(0x00A0) + "in").associateWith { bin ->
            ruleModel(bin).let { kernel(it) to ours(it) }
        }
        assertEquals(observed.mapValues { it.value.first to it.value.first }, observed, "each group name as (A12, ours)")
        assertTrue("MVK_LEXER_STANDARD_ERROR" !in observed.getValue("B" + Char(0x00A0) + "in").first, "the rule slot is not lexed")
    }

    private fun ruleModel(bin: String): String {
        val tree = mapper.readTree(base)
        val order = tree.at("/content/modelRoot/rootGroups/0/Group/elements") as ArrayNode
        (order[0] as ObjectNode).put("name", bin).put("id", "/Order/$bin")
        (order[0].path("Group").path("elements")[0] as ObjectNode).put("id", "/Order/$bin/Slot")
        (order[0].path("Group").path("elements")[1] as ObjectNode).put("id", "/Order/$bin/Label")
        val rule = order.addObject().put("type", "Rule").put("name", "Check").put("id", "/Order/Check").putObject("Rule")
            .put("errorCondition", "FieldFilled(Slot)").put("errorEntityRelPath", "../$bin").put("errorCode", "C1")
        rule.putArray("errorMessage").addObject().put("locale", "en_US").put("text", "Slot given")
        return mapper.writeValueAsString(tree)
    }

    private val oracle = KernelConsistencyOracle()
    private val mapper = ObjectMapper()

    private val base: String = DocumentModelDraft.named("order-bin-labels").locale("en_US")
        .rootGroup("Order") { order -> order.group("Bin") { bin -> bin.stringField("Slot") { }.stringField("Label") { } } }
        .build().toJson()

    private fun model(
        label: String,
        indexed: Boolean = false,
        operation: String = "\"bin\"",
        precondition: String = "FieldFilled(Slot)",
        computation: Boolean = true,
        rule: Boolean = false,
    ): String {
        val tree = mapper.readTree(base)
        val binGroup = tree.at("/content/modelRoot/rootGroups/0/Group/elements/0/Group") as ObjectNode
        if (indexed) binGroup.put("repeatability", 5).put("indexFieldName", label)
        val bin = binGroup.path("elements") as ArrayNode
        (bin[1] as ObjectNode).put("name", label).put("id", "/Order/Bin/$label")
        if (computation) {
            val body = bin.addObject().put("type", "Computation").put("name", "Fill").put("id", "/Order/Bin/Fill")
                .putObject("Computation").put("computedFieldRelPath", "../$label").put("commonPrecondition", precondition)
            body.putArray("computationAlternatives").addObject().put("precondition", precondition).put("operation", operation)
            body.putArray("errorMessage").addObject().put("locale", "en_US").put("text", "Label from the slot")
        }
        if (rule) {
            val body = bin.addObject().put("type", "Rule").put("name", "Check").put("id", "/Order/Bin/Check")
                .putObject("Rule").put("errorCondition", "FieldFilled(Slot)").put("errorEntityRelPath", "../$label")
                .put("errorCode", "C1").put("severity", "ERROR")
            body.putArray("errorMessage").addObject().put("locale", "en_US").put("text", "Label the slot")
        }
        return mapper.writeValueAsString(tree)
    }

    private fun kernel(json: String): List<String> = try {
        oracle.checkConsistency(json, ReferenceResolvers.selfContained())
    } catch (unbuildable: ConsistencyCheckFailedException) {
        return listOf("CK_MODEL_NOT_BUILDABLE")
    }.map { notification ->
        val message = notification.message().trim()
        val code = Regex("\\[(\\w+)]$").find(message)?.groupValues?.get(1)
            ?: codelessClass(message).let { if (it == UNKEYED_A12_CLASS) "codeless:$message" else it }
        code
    }.sorted()

    private fun ours(json: String): List<String> =
        check(ModelSource(json)).findings.filter { it.justifiesRejection }.map { it.code }.sorted()

    private companion object {
        fun named(vararg codes: Int) = "La" + codes.joinToString("") { Char(it).toString() } + "bel"
        val NAMES = listOf(
            named(0x00DF), named(0x005F), named(0x00A0), named(0x2007), named(0x0663), named(0xFF15), named(0x00E9),
            "Label" + Char(0x0663), named(0x000B),
        )
    }
}

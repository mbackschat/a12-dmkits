package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.mbackschat.a12.dm.adapter.KernelConsistencyOracle
import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.core.ReferenceResolvers
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * An executable's name and error-code alphabets (KERNEL-FINDINGS KF578), against the portable checker. A12 asks
 * both of a rule and of a computation that survives the default variant and answers each with a sentence of its
 * own and no class: the name must be an ASCII letter followed by ASCII letters, digits and underscores, and the
 * error code may hold letters, digits, a space and the underscore, the letters including the German umlauts and
 * `ß`. A computation's code alphabet is asked of its name, and a refused name withholds the reserved-prefix class.
 *
 * Own domain (an insurance policy and its premium calculation), built by editing the drafted model's JSON, because
 * the typed builder refuses every defective name here outright.
 */
class CheckerDeclarationAlphabetAgreementTest {

    private data class Row(
        val name: String = "Probe",
        val code: String? = "C1",
        val computation: Boolean = false,
        val only: Boolean = false,
        val condition: String = "FieldFilled(Reference)",
        val target: String = "../Reference",
    )

    private val rows = linkedMapOf(
        "control" to Row(),
        "a name starting with a digit" to Row(name = "1Probe"),
        "a name starting with an underscore" to Row(name = "_Probe"),
        "a dotted name" to Row(name = "Pro.be"),
        "a hyphenated name" to Row(name = "Pro-be"),
        "a name with a colon" to Row(name = "Pro:be"),
        "a name with a space" to Row(name = "Pro be"),
        "a name with an umlaut" to Row(name = "Prämie"),
        "a name with an accent" to Row(name = "Proébe"),
        "a name with an Arabic digit" to Row(name = "Probe١"),
        "a name with a digit and an underscore" to Row(name = "Probe_1"),
        "a one-letter name" to Row(name = "P"),
        "a reserved name" to Row(name = "vk_vkProbe"),
        "a reserved dotted name" to Row(name = "vk_vk.x"),
        "a code with a dot" to Row(code = "C.1"),
        "a code with a hyphen" to Row(code = "C-1"),
        "a code with a colon" to Row(code = "C:1"),
        "a code with a tab" to Row(code = "C\tD"),
        "a code with a no-break space" to Row(code = "C\u00A0D"),
        "a code with an accent" to Row(code = "Cé"),
        "a code with a cedilla" to Row(code = "Ç"),
        "a code with an Arabic digit" to Row(code = "C١"),
        "a code with a space" to Row(code = "C 1"),
        "a code with a leading space" to Row(code = " C"),
        "a code with umlauts" to Row(code = "CÄÖÜ"),
        "a code with sharp s" to Row(code = "Cß"),
        "a code starting with a digit" to Row(code = "1C"),
        "a code that is a tab" to Row(code = "\t"),
        "a code that is a no-break space" to Row(code = "\u00A0"),
        "a code that is a figure space" to Row(code = "\u2007"),
        "a code that is a narrow no-break space" to Row(code = "\u202F"),
        "a code that is an em space" to Row(code = "\u2003"),
        "a code with a supplementary character" to Row(code = "C\uD83D\uDE00"),
        "a name with a supplementary character" to Row(name = "Probe\uD83D\uDE00"),
        "a computation named with a supplementary character" to Row(name = "Prem\uD83D\uDE00", computation = true),
        "a dotted name and code" to Row(name = "Pro.be", code = "C.1"),
        "a refused name, deleted at the default variant" to Row(name = "1Probe", only = true),
        "a refused code, deleted at the default variant" to Row(code = "C.1", only = true),
        "a refused name beside a broken target" to Row(name = "1Probe", target = "../NoSuchField"),
        "a refused code beside a broken condition" to Row(code = "C.1", condition = "FieldFilled(NoSuchField)"),
        "a computation" to Row(name = "Premium", computation = true),
        "a computation named with a digit first" to Row(name = "1Premium", computation = true),
        "a dotted computation" to Row(name = "Prem.ium", computation = true),
        "a computation named with a space" to Row(name = "Prem ium", computation = true),
        "a computation named with an umlaut" to Row(name = "Prämie", computation = true),
        "a dotted computation, deleted at the default variant" to Row(name = "Prem.ium", computation = true, only = true),
    )

    @Test
    fun theCheckerNamesA12sSentencesOnEveryRow() {
        val observed = rows.mapValues { (_, row) -> model(row).let { kernel(it) to ours(it) } }
        assertEquals(observed.mapValues { (_, verdict) -> verdict.first to verdict.first }, observed,
            "each row as (A12, ours)")
        // A12's own answer on the rows that separate the two alphabets and their reach, so the agreement cannot
        // follow a drift on both sides.
        val sentences = observed.mapValues { (_, verdict) -> verdict.first.filter { it.startsWith("RK_") } }
        assertEquals(linkedMapOf(
            "control" to emptyList(),
            "a name with an umlaut" to listOf(NAME),
            "a reserved dotted name" to listOf(NAME),
            "a code with umlauts" to emptyList(),
            "a code with an accent" to listOf(CODE),
            "a code with a no-break space" to listOf(CODE),
            "a dotted name and code" to listOf(CODE, NAME),
            "a refused name, deleted at the default variant" to emptyList(),
            "a dotted computation" to listOf(CODE, NAME),
            "a computation named with a space" to listOf(NAME),
        ), sentences.filterKeys { it in PINNED })
        assertEquals(listOf("MVK_RULE_NAME_INVALID"), observed.getValue("a reserved name").first)
        assertEquals(listOf("MVK_ERROR_CODE_MISSING"), observed.getValue("a code that is a tab").first)
        assertEquals(listOf(CODE), observed.getValue("a code that is a no-break space").first)
        assertEquals(listOf("RK_UNSUPPORTED_CHARACTER"),
            observed.getValue("a computation named with a supplementary character").first)
    }

    private val oracle = KernelConsistencyOracle()
    private val mapper = ObjectMapper()

    private val base = DocumentModelDraft.named("policy-alphabets").locale("en_US")
        .rootGroup("Policy") { p -> p.stringField("Reference") { }.stringField("Note") { } }
        .build().toJson()

    private fun kernel(json: String): List<String> = oracle.checkConsistency(json, ReferenceResolvers.selfContained())
        .map { notification ->
            val message = notification.message().trim()
            Regex("\\[(\\w+)]$").find(message)?.groupValues?.get(1)
                ?: codelessClass(message).let { if (it == UNKEYED_A12_CLASS) "codeless:$message" else it }
        }.sorted()

    private fun ours(json: String): List<String> =
        check(ModelSource(json)).findings.filter { it.justifiesRejection }.map { it.code }.sorted()

    private fun model(row: Row): String {
        val root = mapper.readTree(if (row.computation) base else withRule(row)) as ObjectNode
        val elements = root.path("content").path("modelRoot").path("rootGroups")[0].path("Group").path("elements")
            as ArrayNode
        val element = if (row.computation) {
            val computation = elements.addObject().put("type", "Computation")
            val body = computation.putObject("Computation").put("computedFieldRelPath", "../Note")
                .put("commonPrecondition", "FieldFilled(Reference)")
            body.putArray("computationAlternatives").addObject().put("precondition", "FieldFilled(Reference)")
                .put("operation", "\"standard\"")
            body.putArray("errorMessage").addObject().put("locale", "en_US").put("text", "Note from the reference")
            computation
        } else {
            elements.first { it.path("name").asText() == "Probe" } as ObjectNode
        }
        element.put("name", row.name).put("id", "/Policy/${row.name}")
        val body = element.path(if (row.computation) "Computation" else "Rule") as ObjectNode
        if (!row.computation) {
            if (row.code == null) body.remove("errorCode") else body.put("errorCode", row.code)
        }
        if (row.only) body.putObject("variantFilter").put("filterType", "ONLY").putArray("variants").add("Broker")
        return mapper.writeValueAsString(root)
    }

    private fun withRule(row: Row): String =
        RuleValidator().inject(base, DraftRule("/Policy", "Probe", row.target, row.condition, "C1", "ERROR"))

    private companion object {
        const val NAME = "RK_RULE_NAME_ALPHABET"
        const val CODE = "RK_ERROR_CODE_ALPHABET"
        val PINNED = setOf("control", "a name with an umlaut", "a reserved dotted name", "a code with umlauts",
            "a code with an accent", "a code with a no-break space", "a dotted name and code",
            "a refused name, deleted at the default variant", "a dotted computation", "a computation named with a space")
    }
}

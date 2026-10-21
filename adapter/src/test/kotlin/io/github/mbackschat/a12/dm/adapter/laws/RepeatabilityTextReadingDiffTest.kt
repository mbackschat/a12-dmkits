package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.util.RawValue
import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * How A12's reader binds a group's `repeatability`, and that the portable checker and the interpreter bind it alike
 * (KERNEL-FINDINGS KF590). Text is Java-trimmed and read by `Integer.parseInt`, so a non-ASCII digit counts and the
 * empty text is 0; a JSON number is truncated toward zero; a fraction or exponent in text, a count past `Int`, a
 * no-break space and a boolean leave no model, and so do a JSON number whose exact value lies past `Int` and an
 * object or an array; the text `null` binds no value, held as 0 (KF593). Each built model then holds four rows, so a count of 3 draws the
 * over-repetition pair and 10 does not, which shows the count itself and not only that one was read.
 *
 * Own domain (a claims register).
 */
class RepeatabilityTextReadingDiffTest {

    private enum class A12 { BUILT, MAX_VALUES_INVALID, MAX_TOO_BIG, NOT_BUILT }

    private val values = linkedMapOf<String, Pair<Any, A12>>(
        "3, the control" to (3 to A12.BUILT), "\"3\"" to ("3" to A12.BUILT), "\" 3 \"" to (" 3 " to A12.BUILT),
        "\"\\t3\\n\"" to ("\t3\n" to A12.BUILT), "\"\\u0663\"" to ("\u0663" to A12.BUILT),
        "\"\\uFF13\"" to ("\uFF13" to A12.BUILT), "\"+3\"" to ("+3" to A12.BUILT), "\"03\"" to ("03" to A12.BUILT),
        "3.7" to (3.7 to A12.BUILT), "1e1" to (1e1 to A12.BUILT),
        "\"\"" to ("" to A12.MAX_VALUES_INVALID), "\"-1\"" to ("-1" to A12.MAX_VALUES_INVALID),
        "-3.7" to (-3.7 to A12.MAX_VALUES_INVALID),
        "\"3.7\"" to ("3.7" to A12.NOT_BUILT), "\"1e1\"" to ("1e1" to A12.NOT_BUILT), "\"0x3\"" to ("0x3" to A12.NOT_BUILT),
        "\"\\u00A03\"" to ("\u00A03" to A12.NOT_BUILT), "\"3\\u00A0\"" to ("3\u00A0" to A12.NOT_BUILT),
        "2147483648" to (2147483648L to A12.NOT_BUILT), "\"99999999999\"" to ("99999999999" to A12.NOT_BUILT),
        "4294967298" to (4294967298L to A12.NOT_BUILT), "3e10" to (3e10 to A12.NOT_BUILT),
        "true" to (true to A12.NOT_BUILT),
        // The text `null`, in that case only, is the null value, which the `int` member holds as 0 (KF593).
        "\"null\"" to ("null" to A12.MAX_VALUES_INVALID), "\" null \"" to (" null " to A12.MAX_VALUES_INVALID),
        "\"NULL\"" to ("NULL" to A12.NOT_BUILT),
        // A JSON number binds only when its exact value fits `Int`, before it is truncated.
        "1.0E9" to (1.0E9 to A12.BUILT), "2147483647.0" to (2147483647.0 to A12.MAX_TOO_BIG),
        "2147483647.5" to (2147483647.5 to A12.NOT_BUILT), "-2147483648.5" to (-2147483648.5 to A12.NOT_BUILT),
        "2147483648.0" to (2147483648.0 to A12.NOT_BUILT), "-2147483649.0" to (-2147483649.0 to A12.NOT_BUILT),
        // A12 leaves no model for a scale past `Int`, even where the value would truncate to 0.
        "1e-2147483649" to (RawValue("1e-2147483649") to A12.NOT_BUILT),
        "{}" to (emptyMap<String, Any>() to A12.NOT_BUILT), "[]" to (emptyList<Any>() to A12.NOT_BUILT),
    )

    private fun model(value: Any): String {
        val root = MAPPER.readTree(DocumentModelDraft.named("claims").locale("en_US")
            .rootGroup("Register") { r ->
                r.group("Claims") { c -> c.repeatable(5).numberField("Amount") { it.fractionDigits(0) } }.numberField("Total") { it.fractionDigits(0) }
            }
            .build().toJson()) as ObjectNode
        val claims = root.path("content").path("modelRoot").path("rootGroups")[0].path("Group").path("elements")
            .first { it.path("name").asText() == "Claims" }.path("Group") as ObjectNode
        // A lexeme Java's own BigDecimal cannot construct is written as raw JSON text.
        if (value is RawValue) claims.putRawValue("repeatability", value) else claims.set<ObjectNode>("repeatability", MAPPER.valueToTree(value))
        return root.toString()
    }

    private fun a12(json: String): A12 {
        if (!RuntimeLaws.deserializes(json)) return A12.NOT_BUILT
        // Its classless range sentence rides beside the class (KF590), so only the classes are compared.
        val codes = RuntimeLaws.consistencyMessages(json).mapNotNull { CODE.find(it)?.groupValues?.get(1) }.toSet()
        return when (codes) {
            emptySet<String>() -> A12.BUILT
            setOf("MVK_MAX_VALUES_INVALID") -> A12.MAX_VALUES_INVALID
            setOf("MVK_MAX_TOO_BIG") -> A12.MAX_TOO_BIG
            else -> error("unexpected A12 classes $codes")
        }
    }

    /** The checker's class for a model: only its coded findings, since its `RK_` sentences carry no class of A12's. */
    private fun checker(json: String): A12 {
        val codes = check(ModelSource(json)).findings.filter { it.justifiesRejection && !it.code.startsWith("RK_") }.map { it.code }.toSet()
        return when (codes) {
            emptySet<String>() -> A12.BUILT
            setOf("MVK_MAX_VALUES_INVALID") -> A12.MAX_VALUES_INVALID
            setOf("MVK_MAX_TOO_BIG") -> A12.MAX_TOO_BIG
            setOf("CK_MODEL_NOT_BUILDABLE") -> A12.NOT_BUILT
            else -> error("unexpected checker classes $codes")
        }
    }

    @Test
    fun theCheckerAndTheInterpreterBindTheCountAsA12Does() {
        val expected = values.mapValues { (_, row) ->
            "A12 ${row.second}, checker ${row.second}, interpreter ${if (row.second == A12.NOT_BUILT) "refuses" else "loads"}"
        }
        val observed = values.mapValues { (_, row) ->
            val json = model(row.first)
            "A12 ${a12(json)}, checker ${checker(json)}, interpreter ${if (runCatching { ModelLoader.load(json) }.isSuccess) "loads" else "refuses"}"
        }
        assertEquals(expected, observed)
    }

    @Test
    fun everyBuiltModelReadsTheSameCountOnAllThreeEngines() {
        val fourRows = """{"Register":{"Claims":[${(1..4).joinToString(",") { """{"Amount":$it}""" }}]}}"""
        for ((label, row) in values) if (row.second == A12.BUILT) {
            val json = model(row.first)
            DocumentDifferential.assertAgree(json, mapOf("four claims under $label" to fourRows))
            val overRepetition = DocumentDifferential.kernel(json, fourRows, dynamic = true).messages.isNotEmpty()
            assertEquals(label !in setOf("1e1", "1.0E9"), overRepetition, "$label: four rows exceed a count of 3 and fit 10")
        }
    }

    /**
     * The checker reads the same count when it judges a star: every built count above one makes `Claims*` a legal
     * wildcard, so neither A12 nor the checker states `MVK_INVALID_WILDCARD` for `3.7`, `"\u0663"` or `"\uFF13"`.
     */
    @Test
    fun aStarOverEveryBuiltCountIsAWildcardOnBothSides() {
        val wildcard = "MVK_INVALID_WILDCARD"
        fun observe(value: Any): String {
            val json = RuntimeLaws.withRule(model(value), "/Register", "RS", "../Total", "SR", "AtLeastOneFieldFilled(Claims*/Amount)")
            val a12 = RuntimeLaws.consistencyMessages(json).any { wildcard in it }
            val checker = check(ModelSource(json)).findings.any { it.code == wildcard }
            return "A12 $a12, checker $checker"
        }
        val observed = values.filterValues { it.second == A12.BUILT }.mapValues { (_, row) -> observe(row.first) }
        assertEquals(observed.mapValues { "A12 false, checker false" }, observed)
        assertEquals("A12 true, checker true", observe(1), "a count of 1 is the control that the class fires")
    }

    private companion object {
        val MAPPER = ObjectMapper()
        val CODE = Regex("""\[(MVK_\w+)]""")
    }
}

package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.interpreter.PreparedModel
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * How A12 reads four kinds of model and document text, and that the interpreter reads each alike (KERNEL-FINDINGS
 * KF591): a base year bound like a count, through Java's `trim` and `Integer.parseInt`, a number bound through
 * Java's `trim` and an ASCII number syntax, a label kept as written, and a Boolean cell compared by Java's
 * `equalsIgnoreCase`, which folds the long s onto `s`. Kotlin's own readings count the no-break spaces as blank, read
 * every platform's digit table and fold case by it, so each row below once split the two.
 *
 * Own domain (an insurance cover).
 */
class ModelTextReadingDiffTest {

    @Test
    fun aBaseYearIsBoundAsJavaBindsAnInteger() {
        val rows = linkedMapOf<String, Pair<Any?, String>>(
            "\"2024\", the control" to ("2024" to FIRES), "\" 2024 \"" to (" 2024 " to FIRES),
            "\"\\u0662\\u0660\\u0662\\u0664\"" to ("\u0662\u0660\u0662\u0664" to FIRES), "2024" to (2024 to FIRES),
            "2024.7, truncated" to (2024.7 to FIRES),
            "\"\\u00A02024\"" to ("\u00A02024" to NOT_BUILT), "\"2024.0\"" to ("2024.0" to NOT_BUILT), "\"x\"" to ("x" to NOT_BUILT),
            "\"\"" to ("" to NO_BASE_YEAR), "\"  \"" to ("  " to NO_BASE_YEAR),
            // The text `null`, in that case only, is the null value to A12's reader (KF593).
            "\"null\"" to ("null" to NO_BASE_YEAR), "\" null \"" to (" null " to NO_BASE_YEAR), "null" to (null to NO_BASE_YEAR),
            "\"NULL\"" to ("NULL" to NOT_BUILT), "\"Null\"" to ("Null" to NOT_BUILT),
            // A JSON number binds only when its exact value fits `Int`, before it is truncated.
            "2147483647.0" to (2147483647.0 to BUILT), "2147483647.5" to (2147483647.5 to NOT_BUILT),
            "-2147483648.5" to (-2147483648.5 to NOT_BUILT), "2147483648.0" to (2147483648.0 to NOT_BUILT),
            "{}" to (emptyMap<String, Any>() to NOT_BUILT),
        )
        val observed = rows.mapValues { (_, row) ->
            val root = cover { it }
            (root.at("/content/modelInfo") as ObjectNode).set<ObjectNode>("baseYear", MAPPER.valueToTree(row.first))
            val json = RuntimeLaws.withRule(root.toString(), "/Cover", "RBY", "../Amount", "BY", "FieldFilled(Amount) And BaseYear == 2024")
            // The checker's column is what it states about the base year: missing, unbuildable, or nothing.
            val codes = check(ModelSource(json)).findings.map { it.code }
            val checker = when {
                "MVK_NO_BASE_YEAR" in codes -> NO_BASE_YEAR
                "CK_MODEL_NOT_BUILDABLE" in codes -> NOT_BUILT
                else -> "silent"
            }
            "A12 ${a12(json) { kernelFired(json, AMOUNT).isNotEmpty() }}, interpreter ${interpreter(json) { interpreterFired(json, AMOUNT).isNotEmpty() }}, " +
                "checker $checker"
        }
        assertEquals(rows.mapValues { (_, row) ->
            "A12 ${row.second}, interpreter ${row.second.forInterpreter()}, checker ${if (row.second == NO_BASE_YEAR || row.second == NOT_BUILT) row.second else "silent"}"
        }, observed)
    }

    @Test
    fun aNumberBoundIsReadAsJavaReadsADecimal() {
        val rows = linkedMapOf<String, Pair<Any, String>>(
            "\"5\", the control" to ("5" to BUILT), "\" 5 \"" to (" 5 " to BUILT), "\"5.0\"" to ("5.0" to BUILT), "5" to (5 to BUILT),
            "\"\\u00A05\"" to ("\u00A05" to NOT_BUILT), "\"\\u0665\"" to ("\u0665" to NOT_BUILT), "\"abc\"" to ("abc" to NOT_BUILT),
            // The text `null` declares no bound; the reader refuses text past 1000 characters and a scale past `Int`.
            "\"null\"" to ("null" to BUILT), "1000 digits" to ("1".repeat(1000) to BUILT), "1001 digits" to ("1".repeat(1001) to NOT_BUILT),
            "\"1e2147483648\"" to ("1e2147483648" to BUILT), "\"1e9999999999\"" to ("1e9999999999" to NOT_BUILT),
            "\"5.\"" to ("5." to BUILT), "\".5\"" to (".5" to BUILT), "\"1e5\"" to ("1e5" to BUILT), "\"+5\"" to ("+5" to BUILT),
        )
        val observed = rows.mapValues { (_, row) ->
            val root = cover { it }
            (root.at("/content/modelRoot/rootGroups/0/Group/elements/0/Field/fieldType/NumberType") as ObjectNode)
                .set<ObjectNode>("minValue", MAPPER.valueToTree(row.first))
            val json = root.toString()
            val checker = check(ModelSource(json)).findings.any { it.code == "CK_MODEL_NOT_BUILDABLE" }
            "A12 ${a12(json) { false }}, interpreter ${interpreter(json) { false }}, checker ${if (checker) NOT_BUILT else BUILT}"
        }
        assertEquals(rows.mapValues { (_, row) ->
            "A12 ${row.second}, interpreter ${row.second.forInterpreter()}, checker ${row.second}"
        }, observed)
    }

    /** A Java-blank label is refused at admission; a no-break space is text, and both engines render it as written. */
    @Test
    fun aLabelIsRenderedAsWritten() {
        val labels = linkedMapOf("\"Gold\", the control" to "Gold", "\"\\u00A0\"" to "\u00A0", "\"\"" to "", "\" \"" to " ", "\"\\u2003\"" to "\u2003")
        val observed = labels.mapValues { (_, label) ->
            val base = DocumentModelDraft.named("tiers").locale("en_US").rootGroup("Policy") { g ->
                g.enumField("Tier") { e -> e.value("GOLD") { l -> l.label("en_US", label) }.value("SILVER") { l -> l.label("en_US", "Silver") } }
                    .numberField("Amount") { it.fractionDigits(0).label("en_US", label) }
            }.build().toJson()
            val json = RuntimeLaws.withRuleMessages(base, "/Policy", "RT", "../Amount", "TL", "FieldFilled(Tier) And FieldFilled(Amount)",
                "tier=[\$Tier.value\$] field=[\$Amount\$]", "x")
            val doc = """{"Policy":{"Tier":"GOLD","Amount":1}}"""
            if (RuntimeLaws.consistencyMessages(json).any { "MVK_INTERNAL_VALUES_AND_DISPLAY_VAL" in it }) "A12 refuses the label"
            else "kernel ${kernelFired(json, doc)}, interpreter ${interpreterFired(json, doc)}"
        }
        assertEquals(linkedMapOf(
            "\"Gold\", the control" to "kernel [TL=tier=[Gold] field=[Gold]], interpreter [TL=tier=[Gold] field=[Gold]]",
            "\"\\u00A0\"" to "kernel [TL=tier=[\u00A0] field=[\u00A0]], interpreter [TL=tier=[\u00A0] field=[\u00A0]]",
            "\"\"" to "A12 refuses the label", "\" \"" to "A12 refuses the label", "\"\\u2003\"" to "A12 refuses the label",
        ), observed)
    }

    /** Java's `equalsIgnoreCase` folds the long s onto `s`, so `fal\u017Fe` is false; a lone long s is no Boolean. */
    @Test
    fun aBooleanCellIsComparedAsJavaComparesIgnoringCase() {
        val base = DocumentModelDraft.named("flags").locale("en_US")
            .rootGroup("Policy") { g -> g.booleanField("Flag") { }.numberField("Amount") { it.fractionDigits(0) } }.build().toJson()
        val json = RuntimeLaws.withRuleMessages(base, "/Policy", "RF", "../Amount", "FT", "FieldFilled(Amount) And FieldFilled(Flag)", "flag=[\$Flag.value\$]", "x")
        val cells = listOf("true", "TRUE", "tRuE", "fal\u017Fe", "FAL\u017FE", "\u017F", " true")
        // A formal class is compared by its code alone, since the interpreter words its own texts (IF3).
        fun codes(fired: List<String>) = fired.map { if (it.startsWith("FT=")) it else it.substringBefore('=') }
        val kernel = cells.associateWith { codes(kernelFired(json, """{"Policy":{"Flag":"$it","Amount":1}}""")) }
        val interpreter = cells.associateWith { codes(interpreterFired(json, """{"Policy":{"Flag":"$it","Amount":1}}""")) }
        assertEquals(kernel, interpreter)
        assertEquals(listOf("FT=flag=[false]"), kernel.getValue("fal\u017Fe").filter { it.startsWith("FT=") }, "the long s folds onto s")
    }

    private fun cover(edit: (ObjectNode) -> ObjectNode): ObjectNode = edit(MAPPER.readTree(DocumentModelDraft.named("cover").locale("en_US").baseYear(2000)
        .rootGroup("Cover") { g -> g.numberField("Amount") { it.fractionDigits(0) } }.build().toJson()) as ObjectNode)

    /** A12's class for [json]: not built, refused for want of a base year, or built, then [fires] decides the rest. */
    private fun a12(json: String, fires: () -> Boolean): String = when {
        !RuntimeLaws.deserializes(json) -> NOT_BUILT
        RuntimeLaws.consistencyMessages(json).any { "MVK_NO_BASE_YEAR" in it } -> NO_BASE_YEAR
        else -> if (fires()) FIRES else BUILT
    }

    private fun interpreter(json: String, fires: () -> Boolean): String = when {
        runCatching { ModelLoader.load(json) }.isFailure -> "refuses"
        else -> if (fires()) FIRES else "loads"
    }

    private fun String.forInterpreter() = when (this) {
        NOT_BUILT -> "refuses"
        FIRES -> FIRES
        else -> "loads"
    }

    private fun kernelFired(json: String, doc: String): List<String> =
        RuntimeLaws.kernelEngine(json).let { e -> e.validationMessages(e.instance(doc), null, Locale.US).map { it.errorCode + "=" + it.errorText } }

    private fun interpreterFired(json: String, doc: String): List<String> = PreparedModel.loadExpandedJson(json).let { p ->
        p.createInterpreter().validateFull(p.documents.readDocument(doc)).findings.map { it.code + "=" + it.text }
    }

    private companion object {
        val MAPPER = ObjectMapper()
        const val AMOUNT = """{"Cover":{"Amount":1}}"""
        const val FIRES = "fires"
        const val BUILT = "built"
        const val NOT_BUILT = "not built"
        const val NO_BASE_YEAR = "no base year"
    }
}

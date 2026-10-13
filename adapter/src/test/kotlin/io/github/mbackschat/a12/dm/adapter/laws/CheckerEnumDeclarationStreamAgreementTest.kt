package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.mbackschat.a12.dm.adapter.KernelConsistencyOracle
import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.core.ReferenceResolvers
import io.github.mbackschat.a12.dm.core.Severity
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * An Enumeration declaration's notifications as A12 collects them (KERNEL-FINDINGS KF551, a12-kernel-lean
 * SPEC-2026-10-13-04), against the portable checker. A12 reports one enum field in blocks: invalid characters,
 * the unsupported-signs sentence, every category's unclassified values, every surplus token, the spoilt
 * pairings, the value duplicates and the repeated-value sentence. It keeps one of identical notifications, judges
 * a restricted charset per UTF-16 unit, and names no value carrying a supplementary-plane character in a coded
 * class. The rows re-express a12-kernel-lean's 37-row capture in our own domain and add five more: mixed values
 * (a letter beside a supplementary character) and supplementary surplus tokens, which settle that any such
 * character withholds the entry.
 *
 * Own domain (a retail store's service tier), built by editing the drafted model's JSON, because the typed
 * builder refuses a misaligned category outright.
 */
class CheckerEnumDeclarationStreamAgreementTest {

    private data class Row(val values: List<String>, val categories: List<Pair<String, List<String>>>?,
        val restricted: Boolean = false, val label: String? = null)

    private val rows = linkedMapOf(
        "control" to Row(listOf("BASIC", "PLUS", "GOLD"), listOf(BAND to listOf("STANDARD", "HIGH", "TOP"),
            SEGMENT to listOf("RETAIL", "TRADE", "ONLINE"))),
        "one category two short" to Row(listOf("BASIC", "PLUS", "GOLD"), listOf(BAND to listOf("STANDARD"))),
        "two categories two short" to Row(listOf("BASIC", "PLUS", "GOLD"),
            listOf(BAND to listOf("STANDARD"), SEGMENT to listOf("RETAIL"))),
        "an empty category" to Row(listOf("BASIC", "PLUS", "GOLD"), listOf(BAND to emptyList())),
        "a repeated unclassified value" to Row(listOf("BASIC", "PLUS", "PLUS"), listOf(BAND to listOf("STANDARD"))),
        "a repeated unclassified value, two categories" to Row(listOf("BASIC", "PLUS", "PLUS"),
            listOf(BAND to listOf("STANDARD"), SEGMENT to listOf("RETAIL"))),
        "a repeated surplus token" to Row(listOf("BASIC"), listOf(BAND to listOf("STANDARD", "HIGH", "HIGH"))),
        "distinct surplus tokens" to Row(listOf("BASIC"), listOf(BAND to listOf("STANDARD", "HIGH", "TOP"))),
        "repeated category tokens" to Row(listOf("BASIC", "PLUS"), listOf(BAND to listOf("STANDARD", "STANDARD"))),
        "a duplicate without a category" to Row(listOf("BASIC", "BASIC"), null),
        "a triple duplicate" to Row(listOf("BASIC", "BASIC", "BASIC"), listOf(BAND to listOf("STANDARD", "HIGH", "TOP"))),
        "two duplicates, two categories" to Row(listOf("BASIC", "BASIC", "PLUS", "PLUS"),
            listOf(BAND to listOf("STANDARD", "HIGH"), SEGMENT to listOf("RETAIL", "TRADE"))),
        "distinct bad characters" to Row(listOf("BASIC", "ok"), null, restricted = true),
        "a repeated bad character" to Row(listOf("BASIC", "oo"), null, restricted = true),
        "a repeated bad value" to Row(listOf("BASIC", "ok", "ok"), null, restricted = true),
        "bad characters before a short category" to Row(listOf("BASIC", "ok"), listOf(BAND to listOf("STANDARD")),
            restricted = true),
        "the restricted control" to Row(listOf("BASIC", "PLUS"), listOf(BAND to listOf("STANDARD", "HIGH")),
            restricted = true),
        "lowercase under the default charset" to Row(listOf("BASIC", "ok"), null),
        "a bad supplementary character" to Row(listOf("BASIC", "o$CART"), null, restricted = true),
        "a duplicate mapped earlier" to Row(listOf("BASIC", "BASIC"), listOf(BAND to listOf("STANDARD"))),
        "a surplus token repeating a mapped one" to Row(listOf("BASIC"), listOf(BAND to listOf("STANDARD", "STANDARD"))),
        "a duplicate unmapped, then mapped" to Row(listOf("PLUS", "BASIC", "PLUS"), listOf(BAND to listOf("STANDARD", "HIGH"))),
        "two surplus tokens, one repeated" to Row(listOf("BASIC", "PLUS"),
            listOf(BAND to listOf("STANDARD", "HIGH", "TOP", "TOP", "PEAK"))),
        "a sign value under a full category" to Row(listOf("BASIC", CART), listOf(BAND to listOf("STANDARD", "HIGH"))),
        "a sign value duplicated" to Row(listOf(CART, CART), listOf(BAND to listOf("STANDARD", "HIGH"))),
        "a sign value first" to Row(listOf(CART, "BASIC"), listOf(BAND to listOf("STANDARD"))),
        "two sign values" to Row(listOf(CART, BAG), null),
        "a sign in a label" to Row(listOf("BASIC"), null, label = "Basic $CART"),
        "BMP non-ASCII under the default charset" to Row(listOf("Ü", "è"), null),
        "one value with two signs" to Row(listOf(CART + CART), null),
        "a sign beside arity and a duplicate" to Row(listOf("BASIC", "BASIC", CART), listOf(BAND to listOf("STANDARD"))),
        "a sign beside restricted arity and a duplicate" to Row(listOf("ok", "ok", CART), listOf(BAND to listOf("STANDARD")),
            restricted = true),
        "a sign value duplicated, unclassified" to Row(listOf("BASIC", CART, CART), listOf(BAND to listOf("STANDARD"))),
        "a surplus category beside a duplicate" to Row(listOf("BASIC", "BASIC"),
            listOf(BAND to listOf("STANDARD", "HIGH", "TOP"), SEGMENT to listOf("RETAIL"))),
        "a shortfall, then a surplus" to Row(listOf("BASIC", "PLUS"),
            listOf(BAND to listOf("RETAIL"), SEGMENT to listOf("STANDARD", "HIGH", "TOP"))),
        "surplus, shortfall, surplus" to Row(listOf("BASIC", "PLUS"), listOf(BAND to listOf("STANDARD", "HIGH", "TOP"),
            SEGMENT to listOf("RETAIL"), REGION to listOf("NORTH", "SOUTH", "EAST"))),
        "a mixed sign value unclassified" to Row(listOf("BASIC", "o$CART"), listOf(BAND to listOf("STANDARD"))),
        "a mixed sign value duplicated" to Row(listOf("o$CART", "o$CART"), null),
        "a mixed sign value duplicated under a category" to Row(listOf("o$CART", "o$CART"),
            listOf(BAND to listOf("STANDARD", "HIGH"))),
        "a sign surplus token" to Row(listOf("BASIC"), listOf(BAND to listOf("STANDARD", CART))),
        "a mixed sign surplus token beside a plain one" to Row(listOf("BASIC"), listOf(BAND to listOf("STANDARD", "o$CART", "TOP"))),
        "shortfall, surplus, shortfall" to Row(listOf("BASIC", "PLUS"), listOf(BAND to listOf("RETAIL"),
            SEGMENT to listOf("STANDARD", "HIGH", "TOP"), REGION to listOf("NORTH"))),
    )

    /** Every coded class, in A12's order and with its multiplicity, on every row. */
    @Test
    fun theCodedStreamAgreesInOrderAndMultiplicity() {
        val wrong = rows.mapNotNull { (id, row) ->
            val json = model(row)
            val a12 = kernel(json).filter { it.startsWith("MVK_") }
            val ours = ours(json).filter { it.startsWith("MVK_") }
            if (a12 == ours) null else "$id: A12 $a12, ours $ours"
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n", "rows that disagree:\n"))
    }

    /**
     * The two codeless classes, pinned where they part from the coded stream. The unsupported-signs sentence is
     * published once as `RK_UNSUPPORTED_CHARACTER` wherever A12 raises it, but after the category blocks rather
     * than straight after the invalid characters; the repeated-value sentence, one per repeated value, is not
     * published, and never stands alone, since a repeated value always also draws `INT_DUP` or the sign class.
     */
    @Test
    fun theCodelessClassesArePublishedOrDeliberatelyNot() {
        val wrong = rows.mapNotNull { (id, row) ->
            val json = model(row)
            val a12 = kernel(json)
            val ours = ours(json)
            val signs = a12.count { it == SIGNS }
            val alone = REPEATED in a12 && a12.none { it == INT_DUP || it == SIGNS }
            when {
                signs > 1 || ours.count { it == SIGNS } != signs -> "$id: signs A12 $a12, ours $ours"
                alone -> "$id: a repeated-value sentence stands alone in $a12"
                ours.filterNot { it == SIGNS } != a12.filterNot { it == SIGNS || it == REPEATED } ->
                    "$id: A12 $a12, ours $ours"
                else -> null
            }
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n", "rows that disagree:\n"))
    }

    /** Whole streams on the rows that carry every block, so the comparisons above are not vacuous. */
    @Test
    fun theBlocksAreObservable() {
        assertEquals(listOf(BAD, BAD, BAD, BAD, SIGNS, MISSING, CAT_DUP, INT_DUP, REPEATED),
            kernel(model(rows.getValue("a sign beside restricted arity and a duplicate"))))
        assertEquals(listOf(BAD, BAD, BAD, BAD, MISSING, CAT_DUP, SIGNS, INT_DUP),
            ours(model(rows.getValue("a sign beside restricted arity and a duplicate"))))
        assertEquals(listOf(MISSING, SURPLUS, SURPLUS), kernel(model(rows.getValue("surplus, shortfall, surplus"))))
        assertEquals(listOf(MISSING, SURPLUS, CAT_DUP, CAT_DUP, INT_DUP, REPEATED),
            kernel(model(rows.getValue("a surplus category beside a duplicate"))))
        assertEquals(listOf(SURPLUS), kernel(model(rows.getValue("a repeated surplus token"))))
        assertEquals(listOf(SIGNS), kernel(model(rows.getValue("a mixed sign value unclassified"))))
    }

    private val oracle = KernelConsistencyOracle()
    private val mapper = ObjectMapper()

    private fun kernel(json: String): List<String> = oracle.checkConsistency(json, ReferenceResolvers.selfContained())
        .filter { it.severity() == Severity.ERROR }.map { notification ->
            val message = notification.message().trim()
            Regex("\\[(\\w+)]$").find(message)?.groupValues?.get(1) ?: when {
                "unsupported signs" in message -> SIGNS
                "multiple times" in message -> REPEATED
                else -> "codeless:$message"
            }
        }

    private fun ours(json: String): List<String> =
        check(ModelSource(json)).findings.filter { it.justifiesRejection }.map { it.code }

    private fun model(row: Row): String {
        val draft = DocumentModelDraft.named("store-tiers").locale("en_US")
            .let { if (row.restricted) it.supportedCharacters("A-Z") else it }
            .rootGroup("Store") { s -> s.stringField("Reference") { }
                .enumField("Tier") { t -> t.value("BASIC").category(BAND, "STANDARD") } }
            .build().toJson()
        val root = mapper.readTree(draft) as ObjectNode
        val tier = root.path("content").path("modelRoot").path("rootGroups")[0].path("Group").path("elements")
            .first { it.path("name").asText() == "Tier" }
        val enumeration = tier.path("Field").path("fieldType").path("EnumerationType") as ObjectNode
        val values = enumeration.putArray("values")
        row.values.forEach { value ->
            val entry = values.addObject().put("value", value)
            row.label?.let { entry.putArray("label").addObject().put("locale", "en_US").put("text", it) }
        }
        if (row.categories == null) enumeration.remove("categories")
        else {
            val categories = enumeration.putArray("categories")
            row.categories.forEach { (name, tokens) ->
                val category = categories.addObject().put("name", name)
                val list = category.putArray("values")
                tokens.forEach { list.add(it) }
            }
        }
        return mapper.writeValueAsString(root)
    }

    private companion object {
        const val BAND = "Band"
        const val SEGMENT = "Segment"
        const val REGION = "Region"
        /** U+1F6D2 and U+1F6CD, each a surrogate pair. */
        const val CART = "\uD83D\uDED2"
        const val BAG = "\uD83D\uDECD"
        const val SIGNS = "RK_UNSUPPORTED_CHARACTER"
        const val REPEATED = "codeless:repeated-value"
        const val BAD = "MVK_ENUM_VALUE_WITH_INVALID_CHARACTER"
        const val MISSING = "MVK_MISSING_CATEGORY_VALUE"
        const val SURPLUS = "MVK_MISSING_INTERNAL_VALUE"
        const val CAT_DUP = "MVK_CATEGORY_VALUE_DUPLICATE"
        const val INT_DUP = "MVK_INTERNAL_VALUES_DUPLICATE"
    }
}

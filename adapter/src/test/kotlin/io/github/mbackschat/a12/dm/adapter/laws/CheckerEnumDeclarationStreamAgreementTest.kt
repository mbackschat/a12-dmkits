package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.mbackschat.a12.dm.adapter.KernelConsistencyOracle
import io.github.mbackschat.a12.dm.core.ConsistencyCheckFailedException
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
 * a restricted charset per UTF-16 unit, refusing a supplementary character's high unit even under a range that
 * spans the surrogate block (KF560), and names no value carrying a supplementary-plane character in a coded class
 * other than that charset class: not the forbidden-text classes of a value, a label or a category token, not
 * `MVK_S_VALUE_INVALID` for a special value, and not `MVK_DISPLAY_VALUES_DUPLICATE` for a repeated label, each of
 * which the rows pair with its plain control. Those gates, and every comparison that identifies a value, a label or
 * a token, read the text as Java's `String.trim` leaves it, while the `supportedCharacters` restriction is judged on
 * every unit as written (KF418). The rows re-express a12-kernel-lean's 37-row capture in our own domain and add five more: mixed values
 * (a letter beside a supplementary character) and supplementary surplus tokens, which settle that any such
 * character withholds the entry.
 *
 * Own domain (a retail store's service tier), built by editing the drafted model's JSON, because the typed
 * builder refuses a misaligned category outright.
 */
class CheckerEnumDeclarationStreamAgreementTest {

    private data class Row(val values: List<String>, val categories: List<Pair<String, List<String>>>?,
        val restricted: Boolean = false, val label: String? = null, val charset: List<String>? = null,
        val sValue: String? = null, val labels: List<String>? = null)

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
        "a supplementary character under a spanning range" to Row(listOf("BASIC", CART), null, charset = listOf(SPANNING)),
        "two supplementary values sharing a high unit, spanning" to Row(listOf(CART, BAG), null, charset = listOf(SPANNING)),
        "two supplementary characters in one value, spanning" to Row(listOf("BASIC", CART + BAG), null, charset = listOf(SPANNING)),
        "a letter beside a supplementary character, spanning" to
            Row(listOf("BASIC", "o$CART"), null, charset = listOf("A-Z", "\u3000-\uFFFD")),
        "a supplementary character beside a range missing the block" to
            Row(listOf("BASIC", CART), null, charset = listOf("A-Z", "\u0100-\uD7FF")),
        "a tab in a value" to Row(listOf("BASIC", "o\tk"), null),
        "a trailing pipe in a value" to Row(listOf("BASIC", "o|"), null),
        "an inner tab in a label" to Row(listOf("BASIC"), null, label = "Ba\tsic"),
        "an inner tab in a category token" to Row(listOf("BASIC"), listOf(BAND to listOf("S\tT"))),
        "a tab beside a sign in a value" to Row(listOf("BASIC", "o\tk$CART"), null),
        "a pipe in a label" to Row(listOf("BASIC"), null, label = "Basic|"),
        "a pipe beside a sign in a label" to Row(listOf("BASIC"), null, label = "Basic|$CART"),
        "a pipe in a category token" to Row(listOf("BASIC"), listOf(BAND to listOf("ST|"))),
        "a pipe beside a sign in a category token" to Row(listOf("BASIC"), listOf(BAND to listOf("ST|$CART"))),
        "a foreign special value" to Row(listOf("BASIC"), null, sValue = "GOLD"),
        "a sign special value" to Row(listOf("BASIC"), null, sValue = CART),
        "a mixed sign special value" to Row(listOf("BASIC"), null, sValue = "o$CART"),
        "two values labelled alike" to Row(listOf("BASIC", "PLUS"), null, label = "Tier"),
        "two values labelled alike with a sign" to Row(listOf("BASIC", "PLUS"), null, label = "Tier $CART"),
        // A12 asks its forbidden-character gates, and identifies values, labels and tokens, on the TRIMMED text.
        "a leading tab in a value" to Row(listOf("BASIC", "\to"), null),
        "a trailing tab in a value" to Row(listOf("BASIC", "o\t"), null),
        "a value that is a tab" to Row(listOf("BASIC", "\t"), null),
        "a trailing tab in a label" to Row(listOf("BASIC"), null, label = "Basic\t"),
        "a trailing tab in a category token" to Row(listOf("BASIC"), listOf(BAND to listOf("ST\t"))),
        "a trailing line feed in a value" to Row(listOf("BASIC", "o\n"), null),
        "a trailing carriage return in a label" to Row(listOf("BASIC"), null, label = "Basic\r"),
        "a trailing line feed in a category token" to Row(listOf("BASIC"), listOf(BAND to listOf("ST\n"))),
        "a leading pipe in a value" to Row(listOf("BASIC", "|o"), null),
        "an inner pipe before a trailing tab" to Row(listOf("BASIC", "o|\t"), null),
        "a tab kept inside by a no-break space" to Row(listOf("BASIC", "o\t\u00A0"), null),
        "a trailing space under a restriction" to Row(listOf("BASIC", "PLUS "), null, restricted = true),
        "values equal once trimmed" to Row(listOf("BASIC", "BASIC\t"), null),
        "values equal once a leading space is trimmed" to Row(listOf("BASIC", " BASIC"), null),
        "labels equal once trimmed" to Row(listOf("BASIC", "PLUS"), null, labels = listOf("Tier", "Tier ")),
        "labels equal once a tab is trimmed" to Row(listOf("BASIC", "PLUS"), null, labels = listOf("Tier", "\tTier")),
        "values equal once trimmed, under a category" to Row(listOf("BASIC", "BASIC "), listOf(BAND to listOf("ST", "HI"))),
        "unclassified values equal once trimmed" to Row(listOf("ST", "BASIC", "BASIC "), listOf(BAND to listOf("X"))),
        "surplus tokens equal once trimmed" to Row(listOf("BASIC"), listOf(BAND to listOf("ST", "HI", "HI "))),
        "a special value with a trailing tab" to Row(listOf("BASIC"), null, sValue = "BASIC\t"),
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
     * than straight after the invalid characters; the repeated-value sentence is published as
     * `RK_ENUM_VALUE_DUPLICATE`, one per repeated value and last, where A12's basic check states it (KF626).
     */
    @Test
    fun theCodelessClassesArePublishedOrDeliberatelyNot() {
        val wrong = rows.mapNotNull { (id, row) ->
            val json = model(row)
            val a12 = kernel(json)
            val ours = ours(json)
            val signs = a12.count { it == SIGNS }
            when {
                signs > 1 || ours.count { it == SIGNS } != signs -> "$id: signs A12 $a12, ours $ours"
                ours.filterNot { it == SIGNS } != a12.filterNot { it == SIGNS } ->
                    "$id: A12 $a12, ours $ours"
                else -> null
            }
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n", "rows that disagree:\n"))
    }

    /**
     * A restriction entry holding a surrogate unit, as a lone half, a whole pair, a range endpoint or a supplementary
     * range, leaves A12 unable to build the model, and the checker says so rather than judging values against it
     * (KF560); the declarations it can still read stay analyzable beside that, as CONSISTENCY-API states.
     */
    @Test
    fun aSurrogateInTheRestrictionLeavesTheModelUnbuilt() {
        val wrong = listOf(listOf("A-Z", "\uD83D"), listOf("A-Z", "\uDED2"), listOf("A-Z", CART),
            listOf("A-Z", "\u0100-\uDBFF"), listOf("\uDC00-\uDFFF"), listOf("\uD83D\uDE00-\uD83D\uDE4F")).mapNotNull { charset ->
            val json = model(Row(listOf("BASIC", CART), null, charset = charset))
            val built = runCatching { oracle.checkConsistency(json, ReferenceResolvers.selfContained()) }
            val ours = ours(json)
            when {
                built.exceptionOrNull() !is ConsistencyCheckFailedException -> "$charset: A12 built it"
                UNBUILDABLE !in ours || BAD in ours -> "$charset: ours $ours"
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
        assertEquals(listOf(BAD, BAD, BAD, BAD, MISSING, CAT_DUP, SIGNS, INT_DUP, REPEATED),
            ours(model(rows.getValue("a sign beside restricted arity and a duplicate"))))
        assertEquals(listOf(MISSING, SURPLUS, SURPLUS), kernel(model(rows.getValue("surplus, shortfall, surplus"))))
        assertEquals(listOf(MISSING, SURPLUS, CAT_DUP, CAT_DUP, INT_DUP, REPEATED),
            kernel(model(rows.getValue("a surplus category beside a duplicate"))))
        assertEquals(listOf(SURPLUS), kernel(model(rows.getValue("a repeated surplus token"))))
        assertEquals(listOf(SIGNS), kernel(model(rows.getValue("a mixed sign value unclassified"))))
        assertEquals(listOf(BAD, SIGNS), kernel(model(rows.getValue("a supplementary character under a spanning range"))))
        assertEquals(listOf(BAD, BAD, SIGNS), kernel(model(rows.getValue("two supplementary values sharing a high unit, spanning"))))
        assertEquals(listOf(BAD, SIGNS), kernel(model(rows.getValue("two supplementary characters in one value, spanning"))))
        assertEquals(listOf(BAD, BAD, SIGNS), kernel(model(rows.getValue("a supplementary character beside a range missing the block"))))
        for (carrier in listOf("a tab beside a sign in a value", "a pipe beside a sign in a label",
            "a pipe beside a sign in a category token", "a sign special value", "a mixed sign special value",
            "two values labelled alike with a sign")) {
            assertEquals(listOf(SIGNS), kernel(model(rows.getValue(carrier))), carrier)
        }
        assertEquals(listOf("MVK_INTERNAL_VALUE_INVALID"), kernel(model(rows.getValue("a tab in a value"))))
        assertEquals(listOf("MVK_S_VALUE_INVALID"), kernel(model(rows.getValue("a foreign special value"))))
        assertEquals(listOf("MVK_DISPLAY_VALUES_DUPLICATE"), kernel(model(rows.getValue("two values labelled alike"))))
        for (admitted in listOf("a leading tab in a value", "a trailing tab in a value", "a value that is a tab",
            "a trailing tab in a label", "a trailing tab in a category token", "a trailing line feed in a value",
            "a trailing carriage return in a label", "a trailing line feed in a category token")) {
            assertEquals(emptyList<String>(), kernel(model(rows.getValue(admitted))), admitted)
        }
        assertEquals(listOf("MVK_INTERNAL_VALUE_INVALID"), kernel(model(rows.getValue("a tab kept inside by a no-break space"))))
        assertEquals(listOf(INT_DUP), kernel(model(rows.getValue("values equal once trimmed"))))
        assertEquals(listOf("MVK_DISPLAY_VALUES_DUPLICATE"), kernel(model(rows.getValue("labels equal once trimmed"))))
        assertEquals(listOf(MISSING, CAT_DUP, INT_DUP), kernel(model(rows.getValue("unclassified values equal once trimmed"))))
        assertEquals(listOf(SURPLUS), kernel(model(rows.getValue("surplus tokens equal once trimmed"))))
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
        rejectingCodes(json)

    private fun model(row: Row): String {
        val draft = DocumentModelDraft.named("store-tiers").locale("en_US")
            .let { if (row.restricted) it.supportedCharacters("A-Z") else it }

            .rootGroup("Store") { s -> s.stringField("Reference") { }
                .enumField("Tier") { t -> t.value("BASIC").category(BAND, "STANDARD") } }
            .build().toJson()
        val root = mapper.readTree(draft) as ObjectNode
        row.charset?.let { entries -> val list = (root.path("content") as ObjectNode).withObject("/modelConfig").putArray("supportedCharacters"); entries.forEach { list.add(it) } }
        val tier = root.path("content").path("modelRoot").path("rootGroups")[0].path("Group").path("elements")
            .first { it.path("name").asText() == "Tier" }
        val enumeration = tier.path("Field").path("fieldType").path("EnumerationType") as ObjectNode
        val values = enumeration.putArray("values")
        row.values.forEach { value ->
            val entry = values.addObject().put("value", value)
            (row.labels?.get(row.values.indexOf(value)) ?: row.label)
                ?.let { entry.putArray("label").addObject().put("locale", "en_US").put("text", it) }
        }
        row.sValue?.let { enumeration.put("sValue", it) }
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
        /** A restriction spanning the whole surrogate block, and the class an unbuildable one draws. */
        const val SPANNING = "\u0020-\uFFFD"
        const val UNBUILDABLE = "CK_MODEL_NOT_BUILDABLE"
        const val SIGNS = "RK_UNSUPPORTED_CHARACTER"
        const val REPEATED = "RK_ENUM_VALUE_DUPLICATE"
        const val BAD = "MVK_ENUM_VALUE_WITH_INVALID_CHARACTER"
        const val MISSING = "MVK_MISSING_CATEGORY_VALUE"
        const val SURPLUS = "MVK_MISSING_INTERNAL_VALUE"
        const val CAT_DUP = "MVK_CATEGORY_VALUE_DUPLICATE"
        const val INT_DUP = "MVK_INTERNAL_VALUES_DUPLICATE"
    }
}

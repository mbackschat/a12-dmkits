package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.ast.Tri
import io.github.mbackschat.a12.dm.interpreter.eval.Interpreter
import io.github.mbackschat.a12.dm.interpreter.eval.SingleRowDoc
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.interpreter.parse.parseCondition
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform (JVM+Node) lock for the value-list quantifiers' OPERATOR-SPECIFIC pass structures
 * (SPEC-2026-07-23-16) — the JVM/Node peer twin of the adapter tri-differential
 * `ValueListQuantifierOrderDiffTest` (which anchors every message-level expectation here to BOTH kernel
 * strategies). Two tiers:
 *
 *  1. **Fired polarity** follows the deciding/witness operand (S2–S5): an earlier filtered nonwitness does
 *     not escalate; a values-side `AtLeastOne` filter escalates only when it selected a PRESENT member;
 *     `No` keeps a reached values-side filter's omission potential unconditionally.
 *  2. **Internal verdict (Tri)** — the kernel merges FALSE/UNKNOWN in its rule-level result
 *     (`ValidierungsErgebnis.FALSE_OR_UNKNOWN`), so decided-vs-suppressed is pinned here at the evaluator
 *     seam, against the kernel's mechanism (`RuntimeController` source, canonical §B.3): a fields-side `No`
 *     MATCH decides (FALSE) before a later UNKNOWN; an UNKNOWN reached before the match suppresses
 *     (UNKNOWN); values stay eagerly poison-sensitive for `No`; `NotAll`'s fields-presence prepass decides
 *     (FALSE) before an UNKNOWN values member can poison.
 */
class ValueListQuantifierOrderEvalTest {

    // ── tier 1: fired polarity via the full model/document stack ─────────────────────────────────────

    private val CN = "/Order/CustomerName"
    private val PC = "/Order/ProductCode"
    private val QTY = "/Order/Quantity"
    private val SOH = "/Order/StockOnHand"
    private val SKU = "/Order/Items/Sku"
    private val CNT = "/Order/Items/Count"
    private val SQ = "/Order/Items/StockQty"

    private val fields = listOf(
        FieldDef(CN, Kind.STRING), FieldDef(PC, Kind.STRING),
        FieldDef(QTY, Kind.NUMBER), FieldDef(SOH, Kind.NUMBER),
        FieldDef(SKU, Kind.STRING), FieldDef(CNT, Kind.NUMBER), FieldDef(SQ, Kind.NUMBER),
    )

    private val HSQ = "Having ([Items/StockQty] > 5)"

    /** Firing + polarity of [cond] over item [rows] + plain field values [fv] — "no-fire"/"VALUE"/"OMISSION". */
    private fun outcome(cond: String, rows: List<Map<String, String>>, vararg fv: Pair<String, String>): String {
        val def = ModelDef(
            fields,
            listOf(RuleDef("/Order/VLQ", "/Order", "FieldFilled(CustomerName) And ($cond)", "VLQ", "../CustomerName")),
            repeatableGroups = setOf("/Order/Items"),
            declaredReps = mapOf("/Order/Items" to 50),
        )
        val doc = def.newDocument().group("/Order", 1).field(CN, "Acme", 1, 1)
        for ((f, v) in fv) doc.field(f, v, 1, 1)
        rows.forEachIndexed { i, row ->
            val r = i + 1
            doc.group("/Order/Items", 1, r)
            for ((f, v) in row) doc.field(f, v, 1, r, 1)
        }
        return DmInterpreter(def).validate(doc).messages.firstOrNull { it.code == "VLQ" }
            ?.let { if (it.type == MsgType.OMISSION) "OMISSION" else "VALUE" } ?: "no-fire"
    }

    @Test
    fun s2atLeastOnePolarityFollowsTheUnfilteredDecider() {
        val cond = "AtLeastOneFieldValueIncludedInValueList(Items*/Sku $HSQ, ProductCode In \"A\", \"B\")"
        // earlier filtered operand keeps a present nonmatch — the unfiltered ProductCode decides → VALUE
        assertEquals("VALUE", outcome(cond, listOf(mapOf(SKU to "C", SQ to "10")), PC to "A"))
        // the filter drops its only row — the unfiltered decider still means VALUE
        assertEquals("VALUE", outcome(cond, listOf(mapOf(SKU to "A", SQ to "3")), PC to "A"))
    }

    @Test
    fun s2notAllPolarityFollowsTheUnfilteredWitness() {
        val cond = "NotAllFieldValuesIncludedInValueList(Items*/Sku $HSQ, ProductCode In \"A\", \"B\")"
        // filtered operand keeps an in-set nonwitness; the unfiltered outside witness decides → VALUE
        assertEquals("VALUE", outcome(cond, listOf(mapOf(SKU to "A", SQ to "10")), PC to "Z"))
    }

    @Test
    fun s3filterPolarityRetainedOnTheDecider() {
        assertEquals("OMISSION",
            outcome("AtLeastOneFieldValueIncludedInValueList(ProductCode, Items*/Sku $HSQ In \"A\", \"B\")",
                listOf(mapOf(SKU to "A", SQ to "10")), PC to "Z"))
        assertEquals("OMISSION",
            outcome("NotAllFieldValuesIncludedInValueList(ProductCode, Items*/Sku $HSQ In \"A\", \"B\")",
                listOf(mapOf(SKU to "C", SQ to "10")), PC to "A"))
    }

    @Test
    fun s4valuesSideFilterPolarityOnlyForAPresentSelection() {
        val cond = "AtLeastOneFieldValueIncludedInValueList(Quantity In Items*/Count $HSQ, StockOnHand)"
        // kept row's Count is EMPTY → the filtered values operand selected nothing present → VALUE
        assertEquals("VALUE", outcome(cond, listOf(mapOf(SKU to "A", SQ to "10")), QTY to "7", SOH to "7"))
        // kept PRESENT member (not the matched one) → polarity kept → OMISSION
        assertEquals("OMISSION", outcome(cond, listOf(mapOf(SKU to "A", CNT to "9", SQ to "10")), QTY to "7", SOH to "7"))
        // filter drops its only row → nothing selected → VALUE
        assertEquals("VALUE", outcome(cond, listOf(mapOf(SKU to "A", CNT to "9", SQ to "3")), QTY to "7", SOH to "7"))
    }

    @Test
    fun s5reachedValuesFilterKeepsOmissionPotentialForNo() {
        val cond = "NoFieldValueIncludedInValueList(Quantity In Items*/Count $HSQ, StockOnHand)"
        // the values filter drops everything — reached filter keeps sticky omission → OMISSION
        assertEquals("OMISSION", outcome(cond, listOf(mapOf(SKU to "A", CNT to "9", SQ to "3")), QTY to "7", SOH to "5"))
        // …and a kept-but-EMPTY selection keeps it too → OMISSION (the asymmetry to S4)
        assertEquals("OMISSION", outcome(cond, listOf(mapOf(SKU to "A", SQ to "10")), QTY to "7", SOH to "5"))
    }

    // ── tier 2: the internal verdict (Tri) at the evaluator seam ─────────────────────────────────────

    private val F1 = "/Order/F1"
    private val F2 = "/Order/F2"
    private val V1 = "/Order/V1"
    private val V2 = "/Order/V2"
    private val kinds = mapOf(F1 to Kind.NUMBER, F2 to Kind.NUMBER, V1 to Kind.NUMBER, V2 to Kind.NUMBER)

    /** [cond] over one row of [values]; fields in [invalid] are formally invalid (present-but-UNKNOWN). */
    private fun tri(cond: String, values: Map<String, String>, invalid: Set<String> = emptySet()): Tri =
        Interpreter(SingleRowDoc("/Order", values, kinds), invalid = { it in invalid })
            .eval(parseCondition(cond, "/Order"))

    @Test
    fun s1noFieldsMatchDecidesBeforeALaterUnknown() {
        // operand 1 matches (F1=5 ∈ {V1=5}) — the scan terminates non-firing BEFORE operand 2's UNKNOWN
        assertEquals(Tri.FALSE,
            tri("NoFieldValueIncludedInValueList(F1, F2 In V1)",
                mapOf(F1 to "5", F2 to "9", V1 to "5"), invalid = setOf(F2)),
            "a later fields-side UNKNOWN must not suppress a decided No")
        // reverse operand order: the UNKNOWN is reached before the match → suppressed
        assertEquals(Tri.UNKNOWN,
            tri("NoFieldValueIncludedInValueList(F2, F1 In V1)",
                mapOf(F1 to "5", F2 to "9", V1 to "5"), invalid = setOf(F2)),
            "an UNKNOWN reached before the match suppresses")
    }

    @Test
    fun s1noValuesStayEagerlyPoisonSensitive() {
        // values are consumed COMPLETELY before fields — a values-side UNKNOWN suppresses even though the
        // fields side holds a match (the must-not-change: values do NOT become lazy for No)
        assertEquals(Tri.UNKNOWN,
            tri("NoFieldValueIncludedInValueList(F1 In V1, V2)",
                mapOf(F1 to "5", V1 to "5", V2 to "9"), invalid = setOf(V2)))
    }

    @Test
    fun s6notAllPresencePrepassDecidesBeforeValuesPoison() {
        // no present fields cell → the prepass decides FALSE without consuming the poisoned values side
        assertEquals(Tri.FALSE,
            tri("NotAllFieldValuesIncludedInValueList(F1 In V1)",
                mapOf(V1 to "9"), invalid = setOf(V1)))
        // present-fields control: the values UNKNOWN now poisons → UNKNOWN
        assertEquals(Tri.UNKNOWN,
            tri("NotAllFieldValuesIncludedInValueList(F1 In V1)",
                mapOf(F1 to "7", V1 to "9"), invalid = setOf(V1)))
    }
}

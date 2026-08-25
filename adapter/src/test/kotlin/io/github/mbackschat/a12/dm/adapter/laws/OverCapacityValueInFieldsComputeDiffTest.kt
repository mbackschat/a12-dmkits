package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.FieldKind
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * a12-kernel-lean **SPEC-2026-08-24-04** — the checked-computation result domain of `NumberOfValueInFields` over a
 * starred repeatable GROUP operand when a row sits **beyond the group's declared capacity**.
 *
 * **The load-bearing separator, and why paired rows are the only way to state it.** Changing only one
 * over-capacity row from a non-match to a match, or to malformed content, must not change the computed value or
 * the clear. So: an over-capacity match ALONE leaves count `0`; one in-capacity match plus an over-capacity match
 * leaves `1`; and one in-capacity match plus over-capacity MALFORMED content also leaves `1` rather than clearing.
 * That last pair is the whole point — a malformed cell inside capacity DOES clear the target, and the in-capacity
 * control below proves it, so "malformed content is harmless here" is a statement about the capacity gate rather
 * than about malformed content.
 *
 * **What this does NOT claim.** The rows fix the extensional result domain only. They do not establish whether the
 * kernel drops an over-capacity row before classifying its content, classifies it and suppresses the contribution,
 * or uses some other observationally equivalent order — the peer entry's own mechanism limit, and it is repeated
 * here so a reader cannot mistake these outcomes for evidence of an internal ordering.
 *
 * **The formal channel is independent and stays visible.** The over-repetition finding is reported for the same
 * document whose computed value it does not affect; that is asserted separately, because a fixture whose
 * over-capacity row was silently not over capacity would satisfy every compute row above by accident.
 *
 * The three measured overloads get their own groups because the kernel's kind gate reads the expansion: `True`
 * admits Boolean and Confirm declarations, `False` admits Boolean only, and mixing kinds inside one operand group
 * is a different question. Capacity is two everywhere, so row 3 is always the over-capacity one.
 */
class OverCapacityValueInFieldsComputeDiffTest {

    private val base = DocumentModelDraft.named("order-capacity-value-count").locale("en_US")
        .rootGroup("Order") { order ->
            order.numberField("QtyMatches") { it.fractionDigits(0) }
            order.numberField("SkuMatches") { it.fractionDigits(0) }
            order.numberField("FlagMatches") { it.fractionDigits(0) }
            order.numberField("DenialMatches") { it.fractionDigits(0) }
            order.group("Lines") { g ->
                g.repeatable(2)
                g.numberField("Qty") { it.fractionDigits(0) }
                g.numberField("Spare") { it.fractionDigits(0) }
            }
            order.group("Codes") { g ->
                g.repeatable(2)
                g.stringField("Sku") { it.maxLength(4) }
                g.stringField("AltSku") { it.maxLength(4) }
            }
            order.group("Flags") { g ->
                g.repeatable(2)
                g.field("Expedited", FieldKind.BOOLEAN)
                g.field("TermsAccepted", FieldKind.CONFIRM)
            }
            order.group("Denials") { g ->
                g.repeatable(2)
                g.field("GiftWrap", FieldKind.BOOLEAN)
                g.field("Insurance", FieldKind.BOOLEAN)
            }
        }.build().toJson()

    private val bound = BoundModel.ofJson(base)

    private val modelJson = listOf(
        bound.computation(bound.num(QTY_MATCHES)).name("QtyTally")
            .calc(Dsl.numberOfValueInFields(7L, bound.flattenGroup("$LINES*")).expression())
            .message(Dsl.en("Lines quoting the standard quantity.")).build(),
        bound.computation(bound.num(SKU_MATCHES)).name("SkuTally")
            .calc(Dsl.numberOfValueInFields("XY", bound.flattenGroup("$CODES*")).expression())
            .message(Dsl.en("Codes naming the promoted article.")).build(),
        bound.computation(bound.num(FLAG_MATCHES)).name("FlagTally")
            .calc(Dsl.numberOfValueInFields(true, bound.flattenGroup("$FLAGS*")).expression())
            .message(Dsl.en("Flags set on this order.")).build(),
        bound.computation(bound.num(DENIAL_MATCHES)).name("DenialTally")
            .calc(Dsl.numberOfValueInFields(false, bound.flattenGroup("$DENIALS*")).expression())
            .message(Dsl.en("Options explicitly declined.")).build(),
    ).fold(base) { json, computation -> TypedRules.inject(json, computation) }

    private val definition = ModelLoader.load(modelJson)
    private val javaEngine by lazy { RuntimeLaws.kernelJavaEngine(modelJson) }

    /** One cell placement: the field, its repetition of the operand group, and the raw value. */
    private data class Cell(val path: String, val row: Int, val value: String)

    /** Instantiate rows 1..[rows] of every operand group, seed the four targets, then place [cells]. */
    private fun documents(rows: Int, cells: List<Cell>): Pair<RuntimeLaws.Doc, io.github.mbackschat.a12.dm.interpreter.eval.Document> {
        val k = RuntimeLaws.doc().group(ROOT, 1)
        val i = definition.newDocument().group(ROOT, 1)
        for (target in TARGETS) { k.field(target, SEED, 1, 1); i.field(target, SEED, 1, 1) }
        for (group in OPERAND_GROUPS) for (r in 1..rows) { k.group(group, 1, r); i.group(group, 1, r) }
        for (c in cells) { k.field(c.path, c.value, 1, c.row, 1); i.field(c.path, c.value, 1, c.row, 1) }
        return k to i
    }

    /** The four seeded targets on all three engines, which must agree. */
    private fun computed(rows: Int, vararg cells: Cell): String {
        val (kernelDoc, interpreterDoc) = documents(rows, cells.toList())
        val dynamic = RuntimeLaws.compute(modelJson, kernelDoc).filter { it.path() in TARGETS }
            .map { outcome(it.path(), it.value().orElse(null), it.cleared(), it.errored()) }.sorted()
        val (staticKernelDoc, _) = documents(rows, cells.toList())
        val static = javaEngine.compute(javaEngine.instance(staticKernelDoc)).filter { it.path() in TARGETS }
            .map { outcome(it.path(), it.value().orElse(null), it.cleared(), it.errored()) }.sorted()
        val interpreter = DmInterpreter(definition).compute(interpreterDoc).filter { it.path in TARGETS }
            .map {
                when (it.outcome) {
                    ComputeOutcome.VALUE -> "${short(it.path)}=${it.value}"
                    ComputeOutcome.CLEARED -> "${short(it.path)}=CLEARED"
                    ComputeOutcome.ERRORED -> "${short(it.path)}=ERRORED"
                }
            }.sorted()

        assertEquals(dynamic, static, "rows=$rows cells=${cells.toList()} — dynamic-Groovy vs generated Java")
        assertEquals(dynamic, interpreter, "rows=$rows cells=${cells.toList()} — kernel vs INTERPRETER")
        return dynamic.ifEmpty { listOf("(no outcome)") }.joinToString(" ")
    }

    private fun short(path: String) = path.removePrefix("$ROOT/").removeSuffix("Matches")

    private fun outcome(path: String, value: String?, cleared: Boolean, errored: Boolean): String = when {
        cleared -> "${short(path)}=CLEARED"
        errored -> "${short(path)}=ERRORED"
        else -> "${short(path)}=$value"
    }

    @Test fun overCapacityCellContentDoesNotChangeTheComputedCountOrTheClear() {
        assertEquals(
            EXPECTED,
            listOf(
                "in-cap match only         => ${computed(2, *inCapMatch())}",
                "over-cap match only       => ${computed(3, *overCapMatch())}",
                "in-cap + over-cap match   => ${computed(3, *inCapMatch(), *overCapMatch())}",
                "in-cap + over-cap bad     => ${computed(3, *inCapMatch(), *overCapMalformed())}",
                "in-cap match + in-cap bad => ${computed(2, *inCapMatch(), *inCapMalformed())}",
                "instantiated empty rows   => ${computed(2)}",
                "no rows at all            => ${computed(0)}",
            ),
            "the four seeded targets as BOTH kernel strategies report them",
        )
    }

    /**
     * The independent FORMAL channel, on the same document as the third compute row above. Two things are asserted
     * because either alone is weak: the over-capacity row really is over capacity (otherwise every compute row is
     * vacuous), and its own cells draw no scalar diagnostic — the address gate runs first, so over-repetition is
     * what surfaces rather than a value complaint.
     */
    @Test fun theOverCapacityRowStillReportsItsOwnFormalFindingForTheSameDocument() {
        val (kernelDoc, _) = documents(3, inCapMatch().toList() + overCapMalformed().toList())
        val messages = RuntimeLaws.messages(modelJson, kernelDoc).map { "${it.code()}@${it.field()}" }.sorted()
        assertEquals(
            listOf(
                // The four implicit computation validations: `compute` does not APPLY, so each stale seed still
                // differs from what its computation would store. Their presence is what shows the computations
                // ran at all on this document.
                "DenialTally@/Order[1]/DenialMatches",
                "FlagTally@/Order[1]/FlagMatches",
                "QtyTally@/Order[1]/QtyMatches",
                "SkuTally@/Order[1]/SkuMatches",
                // The over-repetition finding arrives on TWO codes per group: the excess coordinate on the cell
                // and the excess row on the group itself.
                "zuGrosseKontextnummer@/Order[1]/Codes[3]/Sku",
                "zuGrosseKontextnummer@/Order[1]/Denials[3]/GiftWrap",
                "zuGrosseKontextnummer@/Order[1]/Flags[3]/Expedited",
                "zuGrosseKontextnummer@/Order[1]/Lines[3]/Qty",
                "zuGrosseZeile@/Order[1]/Codes",
                "zuGrosseZeile@/Order[1]/Denials",
                "zuGrosseZeile@/Order[1]/Flags",
                "zuGrosseZeile@/Order[1]/Lines",
            ),
            messages,
            "the row-3 placements must draw the over-repetition findings and NOTHING else: no `zahl*`, no " +
                "`feldJaNeinFalsch`, no length code, because the address gate runs before scalar conversion. " +
                "If the over-repetition codes are missing, every compute row above is vacuous — the fixture's " +
                "'over-capacity' row was inside capacity.",
        )
    }

    private fun inCapMatch() = arrayOf(
        Cell(QTY, 1, "7"), Cell(SKU, 1, "XY"), Cell(EXPEDITED, 1, "true"), Cell(GIFT_WRAP, 1, "false"),
    )

    private fun overCapMatch() = arrayOf(
        Cell(QTY, 3, "7"), Cell(SKU, 3, "XY"), Cell(EXPEDITED, 3, "true"), Cell(GIFT_WRAP, 3, "false"),
    )

    private fun overCapMalformed() = arrayOf(
        Cell(QTY, 3, "1.5"), Cell(SKU, 3, "TOOLONG"), Cell(EXPEDITED, 3, "maybe"), Cell(GIFT_WRAP, 3, "maybe"),
    )

    private fun inCapMalformed() = arrayOf(
        Cell(SPARE, 2, "1.5"), Cell(ALT_SKU, 2, "TOOLONG"), Cell(TERMS, 2, "maybe"), Cell(INSURANCE, 2, "maybe"),
    )

    private companion object {
        val EXPECTED = listOf(
            "in-cap match only         => Denial=1 Flag=1 Qty=1 Sku=1",
            "over-cap match only       => Denial=0 Flag=0 Qty=0 Sku=0",
            "in-cap + over-cap match   => Denial=1 Flag=1 Qty=1 Sku=1",
            "in-cap + over-cap bad     => Denial=1 Flag=1 Qty=1 Sku=1",
            "in-cap match + in-cap bad => Denial=CLEARED Flag=CLEARED Qty=CLEARED Sku=CLEARED",
            "instantiated empty rows   => Denial=0 Flag=0 Qty=0 Sku=0",
            "no rows at all            => Denial=0 Flag=0 Qty=0 Sku=0",
        )
        const val ROOT = "/Order"
        const val QTY_MATCHES = "/Order/QtyMatches"
        const val SKU_MATCHES = "/Order/SkuMatches"
        const val FLAG_MATCHES = "/Order/FlagMatches"
        const val DENIAL_MATCHES = "/Order/DenialMatches"
        val TARGETS = listOf(QTY_MATCHES, SKU_MATCHES, FLAG_MATCHES, DENIAL_MATCHES)
        const val LINES = "/Order/Lines"
        const val CODES = "/Order/Codes"
        const val FLAGS = "/Order/Flags"
        const val DENIALS = "/Order/Denials"
        val OPERAND_GROUPS = listOf(LINES, CODES, FLAGS, DENIALS)
        const val QTY = "/Order/Lines/Qty"
        const val SPARE = "/Order/Lines/Spare"
        const val SKU = "/Order/Codes/Sku"
        const val ALT_SKU = "/Order/Codes/AltSku"
        const val EXPEDITED = "/Order/Flags/Expedited"
        const val TERMS = "/Order/Flags/TermsAccepted"
        const val GIFT_WRAP = "/Order/Denials/GiftWrap"
        const val INSURANCE = "/Order/Denials/Insurance"
        const val SEED = "9"
    }
}

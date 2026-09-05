package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Tri-engine lock for what the rule-message `$#…$` GROUP parameter RENDERS — the runtime half of a construct
 * whose admission half is [KF214]. That entry measured exhaustively which arguments the position accepts (the
 * rule's own group plus its ancestors, absolute, containment deciding) and said nothing about the text a user
 * then reads; nothing in this estate did.
 *
 * <p><b>The discriminating row, and why the obvious document cannot serve.</b> The rule fires on rows 2 and 3
 * of a three-row document and NOT on row 1. A repetition-index account predicts `2` and `3`; a firing-ORDINAL
 * account predicts `1` and `2`; a row-count account predicts `3` and `3`. On any document whose rule fires from
 * row 1, or fires once, all three accounts agree — which is why the gap in the fixture matters more than the
 * number of rows in it.
 *
 * <p>The `$Marker$` parameter rides in the same message as a within-message control: it renders the FIELD's
 * name, so the two positions are separated by their output rather than by their grammar, and a run where the
 * group position silently fell back to name-rendering would be visible rather than plausible.
 *
 * <p><b>The second fixture separates the ancestor arm from the leaf arm.</b> On a one-repeatable-level model
 * every admitted argument that is not the root resolves to the same coordinate, so "the named group's own row
 * index" and "the firing row's index whatever is named" are indistinguishable. The nested fixture stacks two
 * repeatable levels and fires at a box/item pair whose indices DIFFER, which is the only shape that tells them
 * apart; it carries the repeatable-ROOT arm and the per-segment quote escape in the same message.
 *
 * <p>Origin: a12-kernel-lean's `SPEC-2026-09-05-02` reports the single-level half against their own fragment.
 * Their rows are their report; every row below is measured here, on our carrier, against all three engines.
 */
class RuleMessageGroupParameterDiffTest {

    private companion object {
        const val CLAIM = "/Claim"
        const val REFERENCE = "/Claim/Reference"
        const val LINES = "/Claim/Lines"
        const val MARKER = "/Claim/Lines/Marker"
        const val AMOUNT = "/Claim/Lines/Amount"
        const val CODE = "OVER_LIMIT"

        const val CONSIGNMENT = "/Consignment"
        const val CARRIER = "/Consignment/Carrier"
        const val BOXES = "/Consignment/Boxes"
        const val ITEMS = "/Consignment/Boxes/Items"
        const val SKU = "/Consignment/Boxes/Items/Sku"
        const val QTY = "/Consignment/Boxes/Items/Qty"
        const val OVERPACKED = "OVERPACKED"
    }

    private val model: String = run {
        val base = DocumentModelDraft.named("claim-lines").locale("en_US")
            .rootGroup("Claim") { claim ->
                claim.stringField("Reference") { }
                    .group("Lines") { lines ->
                        lines.repeatable(20).stringField("Marker") { }.numberField("Amount") { }
                    }
            }
            .build().toJson()
        EditSession.read(base).also { edit ->
            edit.addRule { b ->
                val marker = b.str(MARKER)
                b.rule(b.group(LINES)).name("OverLimit").code(CODE).errorField(marker)
                    .`when`(marker.filled().and(b.num(AMOUNT).gt(100))).severity(Severity.ERROR)
                    .message(
                        Dsl.en(
                            "own=[\$#$LINES\$] root=[\$#$CLAIM\$] " +
                                "rule=[\$#RuleGroup\$] top=[\$#RootGroup\$] marker=[\$Marker\$]",
                        ),
                    ).build()
            }
            edit.validate()
        }.toJson()
    }

    private val definition = ModelLoader.load(model)

    /** Two stacked repeatable levels under a repeatable root; the rule sits at the innermost one. */
    private val nested: String = run {
        val base = DocumentModelDraft.named("consignment-boxes").locale("en_US")
            .rootGroup("Consignment") { consignment ->
                consignment.repeatable(5).stringField("Carrier") { }
                    .group("Boxes") { boxes ->
                        boxes.repeatable(10).group("Items") { items ->
                            items.repeatable(10).stringField("Sku") { }.numberField("Qty") { }
                        }
                    }
            }
            .build().toJson()
        EditSession.read(base).also { edit ->
            edit.addRule { b ->
                val sku = b.str(SKU)
                b.rule(b.group(ITEMS)).name("Overpacked").code(OVERPACKED).errorField(sku)
                    .`when`(sku.filled().and(b.num(QTY).gt(100))).severity(Severity.ERROR)
                    .message(
                        Dsl.en(
                            "item=[\$#$ITEMS\$] box=[\$#$BOXES\$] cons=[\$#$CONSIGNMENT\$] " +
                                "rule=[\$#RuleGroup\$] top=[\$#RootGroup\$] " +
                                "quoted=[\$#/'Consignment'/'Boxes'\$]",
                        ),
                    ).build()
            }
            edit.validate()
        }.toJson()
    }

    private val nestedDefinition = ModelLoader.load(nested)

    /** One document mirrored across the kernel [RuntimeLaws.Doc] and the interpreter document. */
    private inner class Build {
        val kDoc = RuntimeLaws.doc().group(CLAIM, 1)
        val iDoc = definition.newDocument().group(CLAIM, 1)

        fun row(row: Int, amount: String) {
            kDoc.group(LINES, 1, row)
            iDoc.group(LINES, 1, row)
            kDoc.field(MARKER, "M$row", 1, row, 1)
            iDoc.field(MARKER, "M$row", 1, row, 1)
            kDoc.field(AMOUNT, amount, 1, row, 1)
            iDoc.field(AMOUNT, amount, 1, row, 1)
        }

        fun reference() {
            kDoc.field(REFERENCE, "C-1", 1, 1)
            iDoc.field(REFERENCE, "C-1", 1, 1)
        }
    }

    /** The nested mirror; every coordinate is written to both engines' documents from one call. */
    private inner class NestedBuild {
        val kDoc = RuntimeLaws.doc()
        val iDoc = nestedDefinition.newDocument()

        fun consignment(cons: Int, carrier: String) {
            kDoc.group(CONSIGNMENT, cons)
            iDoc.group(CONSIGNMENT, cons)
            kDoc.field(CARRIER, carrier, cons, 1)
            iDoc.field(CARRIER, carrier, cons, 1)
        }

        fun box(cons: Int, box: Int) {
            kDoc.group(BOXES, cons, box)
            iDoc.group(BOXES, cons, box)
        }

        fun item(cons: Int, box: Int, item: Int, qty: String) {
            kDoc.group(ITEMS, cons, box, item)
            iDoc.group(ITEMS, cons, box, item)
            kDoc.field(SKU, "S$item", cons, box, item, 1)
            iDoc.field(SKU, "S$item", cons, box, item, 1)
            kDoc.field(QTY, qty, cons, box, item, 1)
            iDoc.field(QTY, qty, cons, box, item, 1)
        }
    }

    /** Three rows, gated so the rule is silent on row 1 and fires on rows 2 and 3. */
    private fun threeRows() = Build().apply {
        reference()
        row(1, "50")
        row(2, "200")
        row(3, "300")
    }

    @Test
    fun theGroupParameterRendersTheFiringRowsOwnRepetitionIndexAndOneForANonRepeatableGroup() {
        val build = threeRows()
        val dynamic = RuntimeLaws.errorTextsOf(model, CODE, build.kDoc, Locale.US)
        val java = RuntimeLaws.kernelJavaEngine(model)
        val generated = java.errorTextsOf(java.instance(build.kDoc), CODE, Locale.US)
        val interpreted = DmInterpreter(definition).validateFull(build.iDoc, "en_US")
            .filter { it.code == CODE }.map { it.text }

        val expected = listOf(
            "own=[2] root=[1] rule=[2] top=[1] marker=[Marker]",
            "own=[3] root=[1] rule=[3] top=[1] marker=[Marker]",
        )
        assertEquals(expected, dynamic, "normative Groovy-dynamic")
        assertEquals(dynamic, generated, "generated Java  kernel=$dynamic java=$generated")
        assertEquals(dynamic, interpreted, "interpreter  kernel=$dynamic interp=$interpreted")
    }

    /**
     * Each admitted argument reads the coordinate at its OWN level. The firing pair is consignment 2, box 3,
     * items 2 and 4 — four distinct integers in one message, so an implementation reading the leaf coordinate
     * for every argument, or the outermost for every argument, is red on the first row rather than on none.
     * `RootGroup` sits on a REPEATABLE root here, which is the arm the single-level fixture cannot reach: its
     * root is non-repeatable, where "the chain root's index" and the constant `1` agree.
     */
    @Test
    fun eachAdmittedArgumentReadsItsOwnLevelsCoordinateAndTheQuoteEscapeIsPerSegment() {
        val build = NestedBuild().apply {
            consignment(1, "north")
            box(1, 1)
            item(1, 1, 1, "500")
            consignment(2, "south")
            box(2, 1)
            item(2, 1, 1, "50")
            box(2, 3)
            item(2, 3, 1, "50")
            item(2, 3, 2, "200")
            item(2, 3, 4, "300")
        }
        val dynamic = RuntimeLaws.errorTextsOf(nested, OVERPACKED, build.kDoc, Locale.US)
        val java = RuntimeLaws.kernelJavaEngine(nested)
        val generated = java.errorTextsOf(java.instance(build.kDoc), OVERPACKED, Locale.US)
        val interpreted = DmInterpreter(nestedDefinition).validateFull(build.iDoc, "en_US")
            .filter { it.code == OVERPACKED }.map { it.text }

        val expected = listOf(
            "item=[1] box=[1] cons=[1] rule=[1] top=[1] quoted=[1]",
            "item=[2] box=[3] cons=[2] rule=[2] top=[2] quoted=[3]",
            "item=[4] box=[3] cons=[2] rule=[4] top=[2] quoted=[3]",
        )
        assertEquals(expected, dynamic, "normative Groovy-dynamic")
        assertEquals(dynamic, generated, "generated Java  kernel=$dynamic java=$generated")
        assertEquals(dynamic, interpreted, "interpreter  kernel=$dynamic interp=$interpreted")
    }
}

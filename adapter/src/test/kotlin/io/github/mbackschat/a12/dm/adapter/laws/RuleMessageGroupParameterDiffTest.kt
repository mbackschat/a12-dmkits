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
 * <p>Origin: a12-kernel-lean's `SPEC-2026-09-05-02` reports this against their own fragment. Their rows are
 * their report; every row below is measured here, on our carrier, against all three engines.
 */
class RuleMessageGroupParameterDiffTest {

    private companion object {
        const val CLAIM = "/Claim"
        const val REFERENCE = "/Claim/Reference"
        const val LINES = "/Claim/Lines"
        const val MARKER = "/Claim/Lines/Marker"
        const val AMOUNT = "/Claim/Lines/Amount"
        const val CODE = "OVER_LIMIT"
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

        // IG148: the interpreter has no `$#…$` alternative in its message token pattern, so the parameter
        // survives into the rendered text verbatim. Asserted rather than skipped, so the day it is implemented
        // this line fails and the divergence is retired deliberately instead of drifting into a silent pass.
        val untouched = listOf(
            "own=[\$#$LINES\$] root=[\$#$CLAIM\$] rule=[\$#RuleGroup\$] top=[\$#RootGroup\$] marker=[Marker]",
            "own=[\$#$LINES\$] root=[\$#$CLAIM\$] rule=[\$#RuleGroup\$] top=[\$#RootGroup\$] marker=[Marker]",
        )
        assertEquals(untouched, interpreted, "interpreter — the characterized IG148 divergence")
    }
}

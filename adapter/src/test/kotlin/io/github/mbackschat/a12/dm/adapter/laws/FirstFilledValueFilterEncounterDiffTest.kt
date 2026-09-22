package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential + tri-check (groovy-dynamic == java-static == interpreter): a multi-operand
 * `FirstFilledValue` encounters each operand's `Having` immediately before scanning that operand.
 * Once an earlier operand supplies the terminal value, a later filtered operand is unreachable and
 * cannot escalate the fired comparison from VALUE to OMISSION.
 */
class FirstFilledValueFilterEncounterDiffTest {

    private companion object {
        const val CLAIM = "/Claim"
        const val ANCHOR = "/Claim/Anchor"
        const val ROWS = "/Claim/Rows"
        const val PRIMARY = "/Claim/Rows/Primary"
        const val SECONDARY = "/Claim/Rows/Secondary"
        const val ELIGIBLE = "/Claim/Rows/Eligible"
    }

    private val base = DocumentModelDraft.named("claim-priority").locale("en_US")
        .rootGroup("Claim") { claim ->
            claim.stringField("Anchor") { }
            claim.group("Rows") { rows ->
                rows.repeatable(5)
                    .numberField("Primary") { it.fractionDigits(0, 0) }
                    .numberField("Secondary") { it.fractionDigits(0, 0) }
                    .stringField("Eligible") { }
            }
        }.build().toJson()

    private val model = BoundModel.ofJson(base)
    private val primary = model.flatten("/Claim/Rows*/Primary")
    private val secondary = model.flatten("/Claim/Rows*/Secondary")
    private val eligible = model.str(ELIGIBLE).filled()

    private fun render(condition: Condition): String = RuntimeLaws.render(condition)

    private val kernelModel = RuntimeLaws.withRule(
        RuntimeLaws.withRule(
            base,
            CLAIM,
            "LaterFilter",
            "../Anchor",
            "LATER_FILTER",
            render(model.str(ANCHOR).filled().and(
                Dsl.firstFilledNumber(primary, secondary.having(eligible)).gt(5),
            )),
        ),
        CLAIM,
        "ReachedFilter",
        "../Anchor",
        "REACHED_FILTER",
        render(model.str(ANCHOR).filled().and(
            Dsl.firstFilledNumber(primary.having(eligible), secondary).gt(5),
        )),
    )

    private fun populate(kernel: RuntimeLaws.Doc, interpreter: Document) {
        kernel.group(CLAIM, 1)
            .field(ANCHOR, "review", 1, 1)
            .group(ROWS, 1, 1)
            .field(PRIMARY, "9", 1, 1, 1)
            .field(SECONDARY, "11", 1, 1, 1)
            .field(ELIGIBLE, "yes", 1, 1, 1)
        interpreter.group(CLAIM, 1)
            .field(ANCHOR, "review", 1, 1)
            .group(ROWS, 1, 1)
            .field(PRIMARY, "9", 1, 1, 1)
            .field(SECONDARY, "11", 1, 1, 1)
            .field(ELIGIBLE, "yes", 1, 1, 1)
    }

    @Test
    fun onlyAnEncounteredFilterEscalatesPolarity() {
        val definition = ModelLoader.load(kernelModel)
        val kernelDocument = RuntimeLaws.doc()
        val interpreterDocument = definition.newDocument()
        populate(kernelDocument, interpreterDocument)

        val groovy = RuntimeLaws.messages(kernelModel, kernelDocument)
            .map { "${it.code()}|${it.type().name}" }
            .sorted()
        val javaEngine = RuntimeLaws.kernelJavaEngine(kernelModel)
        val javaStatic = javaEngine.messages(javaEngine.instance(kernelDocument))
            .map { "${it.code()}|${it.type().name}" }
            .sorted()
        val interpreter = DmInterpreter(definition).validateFull(interpreterDocument)
            .map {
                val type = if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR"
                "${it.code}|$type"
            }
            .sorted()

        assertEquals(
            listOf("LATER_FILTER|VALUE_ERROR", "REACHED_FILTER|OMISSION_ERROR"),
            groovy,
            "the later filter is unreachable after Primary=9; the first-slot filter is encountered",
        )
        assertEquals(groovy, javaStatic, "groovy-dynamic vs generated static-Java")
        assertEquals(groovy, interpreter, "dynamic-Groovy anchor vs interpreter")
    }
}

package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.ComputationAlternative
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.CustomFieldType
import io.github.mbackschat.a12.dm.interpreter.model.CustomFieldTypeDeclaration
import io.github.mbackschat.a12.dm.interpreter.model.CustomTypeRejection
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.LegalCharset
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A compute-mode fill scan over a `Having`-filtered star retains each kept cell's identity through the complete
 * formal oracle. A custom-type rejection is intentionally invisible to the document's base format/constraint
 * state, so these cases catch any path that reduces a candidate to a state-only event before the formal check.
 */
class FilteredStarComputeFormalInvalidityTest {

    private val model = ModelDef(
        fields = listOf(
            FieldDef(FLAG, Kind.NUMBER, scale = 0),
            FieldDef(TAG, Kind.STRING),
            FieldDef(CODE, Kind.STRING, customType = CustomFieldTypeDeclaration("ProjectCode")),
        ),
        rules = emptyList(),
        computations = listOf(
            ComputationDef(
                FLAG,
                ROOT,
                listOf(
                    ComputationAlternative(
                        """AtLeastOneFieldFilled($CODE_STAR Having [$TAG] == "keep")""",
                        "1",
                    ),
                ),
            ),
        ),
        repeatableGroups = setOf(ITEMS),
    )

    private val interpreter = DmInterpreter(
        model,
        customFieldTypes = mapOf(
            "ProjectCode" to CustomFieldType { value, _ ->
                if (value == "bad") CustomTypeRejection("PROJECT_CODE_INVALID") else null
            },
        ),
    )

    @Test
    fun keptCustomInvalidCellPoisonsTheComputation() {
        assertEquals("CLEARED", outcome(document(row("keep", "bad"))))
    }

    @Test
    fun keptCharsetInvalidCellPoisonsThroughTheSameFormalOracle() {
        val asciiInterpreter = DmInterpreter(
            model,
            customFieldTypes = mapOf("ProjectCode" to CustomFieldType { _, _ -> null }),
            legalCharset = LegalCharset.ofRanges(0x20..0x7e),
        )

        assertEquals("CLEARED", outcome(document(row("keep", "§")), asciiInterpreter))
    }

    @Test
    fun filterDropsCustomInvalidCellBeforeTheFormalRead() {
        assertEquals("VALUE|1", outcome(document(row("drop", "bad"), row("keep", "ok"))))
    }

    @Test
    fun firstKeptValidCellStopsBeforeALaterKeptInvalidCell() {
        assertEquals("VALUE|1", outcome(document(row("keep", "ok"), row("keep", "bad"))))
    }

    private fun document(vararg rows: Pair<String, String>): Document {
        val doc = model.newDocument().field(FLAG, "9", 1, 1)
        rows.forEachIndexed { index, (tag, code) ->
            doc.group(ITEMS, 1, index + 1)
                .field(TAG, tag, 1, index + 1, 1)
                .field(CODE, code, 1, index + 1, 1)
        }
        return doc
    }

    private fun row(tag: String, code: String) = tag to code

    private fun outcome(doc: Document, evaluator: DmInterpreter = interpreter): String {
        val result = evaluator.compute(doc).single { it.path == FLAG }
        return when (result.outcome) {
            ComputeOutcome.CLEARED -> "CLEARED"
            ComputeOutcome.ERRORED -> "ERRORED"
            ComputeOutcome.VALUE -> "VALUE|${result.value}"
        }
    }

    private companion object {
        const val ROOT = "/Survey"
        const val ITEMS = "/Survey/Items"
        const val FLAG = "/Survey/Flag"
        const val TAG = "/Survey/Items/Tag"
        const val CODE = "/Survey/Items/Code"
        const val CODE_STAR = "/Survey/Items*/Code"
    }
}

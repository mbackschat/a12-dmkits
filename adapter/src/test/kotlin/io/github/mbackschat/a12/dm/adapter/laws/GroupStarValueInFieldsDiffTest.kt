package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Differential + tri-check — `NumberOfValueInFields` over a **starred GROUP** operand.
 *
 * **Two questions, in order, and the first governs whether the second exists.** `Dsl.numberOfValueInFields` takes
 * `FieldOperand`s and has no `GroupListRef` overload, so a group operand is **type-level unrepresentable** in our
 * DSL — the quiet form of a refusal that CLAUDE.md's "a client-side refusal needs a measured kernel basis" rule
 * names, and exactly the shape DG33 turned out to be for `FieldValuesNotUnique` (where the kernel *admitted* what
 * we had erased). So this file asks admission FIRST: if the kernel refuses the operand, the erasure is grounded
 * and there is no runtime question; if it admits, we have both an authoring gap and a runtime one.
 *
 * The 2026-08-14 audit ranked the runtime half fifth in the group-expansion class ([IF287]): neither arm of the
 * interpreter's implementation expands a group — the starred arm takes the group's *parent* rows and reads a
 * value at the group path, the plain arm asks `filled` of a path that holds no value — so both would contribute
 * **0** silently.
 *
 * The condition is a literal, which is legitimate exactly here: the shape cannot be reached through the typed
 * surface, since reaching it is what a fix would have to add.
 */
class GroupStarValueInFieldsDiffTest {

    private companion object {
        const val REF = "/Order/Ref"
        const val LINES = "/Order/Lines"
        const val A = "/Order/Lines/A"
        const val B = "/Order/Lines/B"
        const val CODE = "NVIF"
    }

    private val base = DocumentModelDraft.named("order").locale("en_US")
        .rootGroup("Order") { g ->
            g.stringField("Ref") { }
            g.group("Lines") { l ->
                l.repeatable(10).stringField("A") { }
                l.stringField("B") { }
            }
        }.build().toJson()

    /** `"X"` appears once in row 1 and once in row 2, so only a count spanning both rows reaches 2. */
    private fun oneMatchPerRow(b: Both) {
        b.group("/Order", 1).field(REF, "R-1", 1, 1)
            .group(LINES, 1, 1).field(A, "X", 1, 1, 1).field(B, "other", 1, 1, 1)
            .group(LINES, 1, 2).field(A, "other", 1, 2, 1).field(B, "X", 1, 2, 1)
    }

    private fun rejectionCodes(condition: String): List<String> {
        val kModel = RuntimeLaws.withRule(base, "/Order", "R", "../Ref", CODE, condition)
        val doc = Both(RuntimeLaws.doc(), ModelLoader.load(kModel).newDocument()).also(::oneMatchPerRow)
        return RuntimeLaws.messages(kModel, doc.kDoc).map { it.code() }.filter { it.startsWith("MVK_") }
    }

    /**
     * **The admission measurement, and it is the deliverable of this file whichever way it lands.** A rejection
     * grounds the type-level erasure; an acceptance makes the erasure an unbased refusal and opens the runtime
     * question below. Stated as its own test so the answer is recorded rather than inferred from whether some
     * other assertion happened to pass.
     */
    @Test
    fun theKernelsVerdictOnAGroupStarOperandIsRecorded() {
        val codes = rejectionCodes("FieldFilled($REF) And NumberOfValueInFields(\"X\" In $LINES*) >= 2")
        assertEquals(
            emptyList<String>(), codes,
            "MEASURED: the kernel REJECTS a starred group operand for NumberOfValueInFields with $codes. That " +
                "grounds the DSL's type-level erasure (no GroupListRef overload) and CLOSES the runtime question " +
                "— there is nothing to evaluate. If this assertion is what you are reading in a failure report, " +
                "the finding is the code above, not a defect: record it and delete the runtime rows below.",
        )
    }

    /** The control: the same two fields as an explicit starred field list, which the typed surface does reach. */
    @Test
    fun theExplicitStarredFieldListIsAdmittedAndCounts() =
        agreeFires("FieldFilled($REF) And NumberOfValueInFields(\"X\" In $LINES*/A, $LINES*/B) >= 2", true,
            "two X's across the two starred field lists")

    /** The runtime row, reached only if the operand is admitted at all. */
    @Test
    fun theGroupStarCountsMatchesAcrossEveryRow() =
        agreeFires("FieldFilled($REF) And NumberOfValueInFields(\"X\" In $LINES*) >= 2", true,
            "one X in row 1 and one in row 2 → a group-scope count reaches 2")

    private fun agreeFires(condition: String, expected: Boolean, msg: String) {
        val kModel = RuntimeLaws.withRule(base, "/Order", "R", "../Ref", CODE, condition)
        val def = ModelLoader.load(kModel)

        val dynamic = Both(RuntimeLaws.doc(), def.newDocument()).also(::oneMatchPerRow)
        val messages = RuntimeLaws.messages(kModel, dynamic.kDoc)
        assertTrue(messages.none { it.code().startsWith("MVK_") }) {
            "$msg — the kernel rejected the shape; see theKernelsVerdictOnAGroupStarOperandIsRecorded"
        }
        val groovy = messages.any { it.code() == CODE }

        val staticDoc = Both(RuntimeLaws.doc(), def.newDocument()).also(::oneMatchPerRow).kDoc
        val javaStatic = RuntimeLaws.kernelJavaEngine(kModel)
            .messagesModern(staticDoc, null, java.util.Locale.US).any { it.code() == CODE }

        val interpreter = DmInterpreter(def).validateFull(dynamic.iDoc).any { it.code == CODE }

        assertEquals(expected, groovy, "$msg — the kernel groovy-dynamic verdict")
        assertEquals(groovy, javaStatic, "$msg — groovy vs java-static (tri-check)")
        assertEquals(groovy, interpreter, "$msg — kernel vs INTERPRETER")
    }
}

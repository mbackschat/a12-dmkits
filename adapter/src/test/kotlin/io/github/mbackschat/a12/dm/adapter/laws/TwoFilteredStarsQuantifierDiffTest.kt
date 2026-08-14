package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Differential + tri-check — a field-fill quantifier whose operands are **TWO FILTERED STARS**, with no
 * unfiltered sibling. [IG136]'s capability half.
 *
 * **What is being settled, and why it is a capability question rather than a defect.** The kernel ADMITS this
 * shape; the interpreter refuses to evaluate it, reporting `unsupported operand Star in a field-reference
 * position`. That is the SAFE direction — it degrades rather than answering wrongly — but the kernel produces a
 * verdict where this evaluator produces nothing, which is a hole in the parity contract rather than a divergence.
 *
 * **The mechanism.** `quantCounts` takes `args.singleOrNull()`; with one starred operand that operand expands to
 * its filtered rows, and with two the `singleOrNull` is null, so the list falls to `quantTally`, which reads each
 * operand as a plain field reference and throws on the first `Star`.
 *
 * **The semantic question this file answers FIRST, before any fix.** Two filtered stars could compose two ways,
 * and nothing in the single-star behaviour predicts which: each operand contributes its own filter-kept cells
 * INDEPENDENTLY (the tally is over the union), or the filters interact. The fixture separates them — each star
 * keeps a different row, so an independent reading tallies one cell from each side while any correlated reading
 * tallies something else.
 */
class TwoFilteredStarsQuantifierDiffTest {

    private companion object {
        const val REF = "/Order/Reference"
        const val LEFT = "/Order/Left"
        const val LEFT_GRADE = "/Order/Left/LeftGrade"
        const val LEFT_VALUE = "/Order/Left/LeftValue"
        const val RIGHT = "/Order/Right"
        const val RIGHT_GRADE = "/Order/Right/RightGrade"
        const val RIGHT_VALUE = "/Order/Right/RightValue"
        const val LEFT_VALUE_STAR = "/Order/Left*/LeftValue"
        const val RIGHT_VALUE_STAR = "/Order/Right*/RightValue"
        const val CODE = "TFS"

        /** Each star keeps exactly ONE row, and a different one — so the two sides cannot be confused. */
        const val CONDITION =
            "FieldFilled($REF) And AllFieldsFilled($LEFT_VALUE_STAR Having [LeftGrade] == \"K\", " +
                "$RIGHT_VALUE_STAR Having [RightGrade] == \"K\")"
    }

    private val base = DocumentModelDraft.named("order").locale("en_US")
        .rootGroup("Order") { g ->
            g.stringField("Reference") { }
            g.group("Left") { l -> l.repeatable(5).stringField("LeftGrade") { }.stringField("LeftValue") { } }
            g.group("Right") { r -> r.repeatable(5).stringField("RightGrade") { }.stringField("RightValue") { } }
        }.build().toJson()

    private class Both(val kDoc: RuntimeLaws.Doc, val iDoc: Document) {
        fun group(path: String, vararg reps: Int): Both {
            kDoc.group(path, *reps); iDoc.group(path, *reps); return this
        }

        fun field(path: String, v: String, vararg reps: Int): Both {
            kDoc.field(path, v, *reps); iDoc.field(path, v, *reps); return this
        }
    }

    /**
     * Row 1 of each side is KEPT by the filter, row 2 is dropped. On the left the kept row's `Value` is FILLED;
     * on the right the kept row's `Value` is EMPTY — so `AllFieldsFilled` over the union is FALSE, while the
     * dropped rows are arranged to say the opposite if the filter were ignored.
     */
    private fun oneKeptRowPerSide(b: Both) {
        b.group("/Order", 1).field(REF, "O-1", 1, 1)
            .group(LEFT, 1, 1).field(LEFT_GRADE, "K", 1, 1, 1).field(LEFT_VALUE, "present", 1, 1, 1)
            .group(LEFT, 1, 2).field(LEFT_GRADE, "X", 1, 2, 1)
            .group(RIGHT, 1, 1).field(RIGHT_GRADE, "K", 1, 1, 1)
            .group(RIGHT, 1, 2).field(RIGHT_GRADE, "X", 1, 2, 1).field(RIGHT_VALUE, "present", 1, 2, 1)
    }

    /**
     * **THE MEASUREMENT.** Reports what each engine does; the kernel arms must agree with each other, and the
     * interpreter arm is recorded rather than asserted equal — the point of this file is to establish the
     * semantics the fix must then reproduce.
     */
    @Test
    fun twoFilteredStarsAreAdmittedAndThisIsWhatTheyMean() {
        val kModel = RuntimeLaws.withRule(base, "/Order", "R", "../Reference", CODE, CONDITION)
        val def = ModelLoader.load(kModel)

        val dynamic = Both(RuntimeLaws.doc(), def.newDocument()).also(::oneKeptRowPerSide)
        val messages = RuntimeLaws.messages(kModel, dynamic.kDoc)
        assertTrue(messages.map { it.code() }.none { it.startsWith("MVK_") }) {
            "the kernel must ADMIT two filtered stars for this file to have a subject — it reported " +
                messages.map { it.code() }.filter { it.startsWith("MVK_") }
        }
        val groovy = messages.filter { it.code() == CODE }.map { "${it.field()}|${it.type().name}" }.sorted()

        val staticDoc = Both(RuntimeLaws.doc(), def.newDocument()).also(::oneKeptRowPerSide).kDoc
        val javaStatic = RuntimeLaws.kernelJavaEngine(kModel)
            .messagesModern(staticDoc, null, java.util.Locale.US)
            .filter { it.code() == CODE }.map { "${it.field()}|${it.type().name}" }.sorted()
        assertEquals(groovy, javaStatic, "the two kernel strategies must agree before either is normative")

        // The right side's kept row has an EMPTY Value, so AllFieldsFilled over the union is FALSE and the rule
        // — which fires on the quantifier being TRUE — must NOT fire. If the filters were ignored, every row's
        // Value would be considered and the answer would still be FALSE (Left[2] and Right[1] are empty), so the
        // discriminating half is the CONTROL below, where ignoring the filter flips the answer.
        assertEquals(listOf<String>(), groovy,
            "one kept cell per side, and the right one is empty — the quantifier is false")

        val interpreter = runCatching {
            DmInterpreter(def).validateFull(dynamic.iDoc).filter { it.code == CODE }
                .map { "${it.errorPath}|${if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR"}" }
                .sorted()
        }
        assertEquals(groovy, interpreter.getOrNull(),
            "the interpreter must answer this shape, not degrade on it — the kernel produces a verdict here " +
                "(failure was: ${interpreter.exceptionOrNull()?.message})")
    }

    /**
     * **THE CONTROL that makes the filters load-bearing.** Both kept rows are filled, and both DROPPED rows are
     * empty. An engine honouring the filters answers TRUE and fires; one ignoring them sees the empty dropped
     * rows and answers FALSE. So this row separates "filtered" from "unfiltered" in the direction the row above
     * cannot.
     */
    @Test
    fun theFiltersDecideTheAnswerRatherThanMerelyBeingParsed() {
        val kModel = RuntimeLaws.withRule(base, "/Order", "R", "../Reference", CODE, CONDITION)
        val def = ModelLoader.load(kModel)
        val build: (Both) -> Unit = { b ->
            b.group("/Order", 1).field(REF, "O-1", 1, 1)
                .group(LEFT, 1, 1).field(LEFT_GRADE, "K", 1, 1, 1).field(LEFT_VALUE, "present", 1, 1, 1)
                .group(LEFT, 1, 2).field(LEFT_GRADE, "X", 1, 2, 1)
                .group(RIGHT, 1, 1).field(RIGHT_GRADE, "K", 1, 1, 1).field(RIGHT_VALUE, "present", 1, 1, 1)
                .group(RIGHT, 1, 2).field(RIGHT_GRADE, "X", 1, 2, 1)
        }

        val dynamic = Both(RuntimeLaws.doc(), def.newDocument()).also(build)
        val groovy = RuntimeLaws.messages(kModel, dynamic.kDoc)
            .filter { it.code() == CODE }.map { "${it.field()}|${it.type().name}" }.sorted()
        assertEquals(listOf("/Order[1]/Reference|OMISSION_ERROR"), groovy,
            "both kept cells are filled, so the quantifier is TRUE and the rule fires — an engine that " +
                "ignored the filters would see the empty dropped rows and stay silent. The polarity is " +
                "OMISSION and was MEASURED rather than predicted: a fill could still change the answer, " +
                "because the DROPPED rows' empty cells could become filter-kept")

        val interpreter = runCatching {
            DmInterpreter(def).validateFull(dynamic.iDoc).filter { it.code == CODE }
                .map { "${it.errorPath}|${if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR"}" }
                .sorted()
        }
        assertEquals(groovy, interpreter.getOrNull(),
            "the interpreter must reproduce the kernel's filtered answer " +
                "(failure was: ${interpreter.exceptionOrNull()?.message})")
    }
}

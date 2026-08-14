package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential + tri-check — the **filled-field count over a GROUP STAR**, `NumberOfFilledFields(G*)`.
 *
 * **Why this exists.** A source audit of the group-expansion defect class ([IF287], four confirmed row-1 pins in
 * `FieldValuesNotUnique`) named this as the next most likely site, and the reasoning is structural rather than
 * suspicious: the `Star` branch of the interpreter's filled-field count has **no group check**, while the `else`
 * branch of the very same function does dispatch on one. A function that disagrees with itself about whether a
 * group needs expanding is the signature the whole class shares.
 *
 * The predicted failure is a silent **0**: for a group path the star resolves through the group's *parent* rows
 * and then reads a value at the group path itself, where no field is declared. A count of zero is a perfectly
 * well-formed answer, so nothing crashes and the rule simply does not fire.
 *
 * **The star is not an exotic spelling here.** A repeatable group operand MUST carry its `*` — the unstarred form
 * is rejected `MVK_NO_WILDCARD` (KERNEL-FINDINGS [`kf173`]) — so for a repeatable group this is the *only* legal
 * way to write the operand, which is what makes an unmeasured star branch worth a row rather than a note.
 *
 * Authored through the typed surface (`Dsl.numberOfFilledFields(GroupListRef)`), not as a literal: unlike
 * `FieldValuesNotUnique`'s group operand, this shape is reachable there, so the dogfooding rule applies.
 */
class GroupStarFilledFieldsDiffTest {

    private companion object {
        const val REF = "/Order/Ref"
        const val LINES = "/Order/Lines"
        const val A = "/Order/Lines/A"
        const val B = "/Order/Lines/B"
        const val CODE = "NFF"
    }

    /** Two Strings per repeatable row, so a two-row document holds four fillable cells. */
    private val base = DocumentModelDraft.named("order").locale("en_US")
        .rootGroup("Order") { g ->
            g.stringField("Ref") { }
            g.group("Lines") { l ->
                l.repeatable(10).stringField("A") { }
                l.stringField("B") { }
            }
        }.build().toJson()

    private val m = BoundModel.ofJson(base)

    /** `FieldFilled(Ref) And NumberOfFilledFields(/Order/Lines*) >= [atLeast]`. */
    private fun condition(atLeast: Int): String = DslRenderer().render(
        m.field(REF).filled().and(Dsl.numberOfFilledFields(m.flattenGroup("$LINES*")).ge(atLeast.toLong())),
    )

    private class Both(val kDoc: RuntimeLaws.Doc, val iDoc: Document) {
        fun group(path: String, vararg reps: Int): Both {
            kDoc.group(path, *reps); iDoc.group(path, *reps); return this
        }

        fun field(path: String, v: String, vararg reps: Int): Both {
            kDoc.field(path, v, *reps); iDoc.field(path, v, *reps); return this
        }
    }

    private fun agreeFires(atLeast: Int, expected: Boolean, msg: String, build: (Both) -> Unit) {
        val kModel = RuntimeLaws.withRule(base, "/Order", "R", "../Ref", CODE, condition(atLeast))
        val def = ModelLoader.load(kModel)

        val dynamic = Both(RuntimeLaws.doc(), def.newDocument()).also(build)
        val groovy = RuntimeLaws.messages(kModel, dynamic.kDoc).any { it.code() == CODE }

        val staticDoc = Both(RuntimeLaws.doc(), def.newDocument()).also(build).kDoc
        val javaStatic = RuntimeLaws.kernelJavaEngine(kModel)
            .messagesModern(staticDoc, null, java.util.Locale.US).any { it.code() == CODE }

        val interpreter = DmInterpreter(def).validateFull(dynamic.iDoc).any { it.code == CODE }

        assertEquals(expected, groovy, "$msg — the kernel groovy-dynamic verdict")
        assertEquals(groovy, javaStatic, "$msg — groovy vs java-static (tri-check)")
        assertEquals(groovy, interpreter, "$msg — kernel vs INTERPRETER")
    }

    /** Two rows, all four cells filled. A count that expands the group reaches 4; one that does not reads 0. */
    private fun twoFullRows(b: Both) {
        b.group("/Order", 1).field(REF, "R-1", 1, 1)
            .group(LINES, 1, 1).field(A, "a1", 1, 1, 1).field(B, "b1", 1, 1, 1)
            .group(LINES, 1, 2).field(A, "a2", 1, 2, 1).field(B, "b2", 1, 2, 1)
    }

    /**
     * **The discriminator.** `>= 3` is reachable only by counting cells across BOTH rows: one row holds two, so a
     * per-row count tops out at 2 and an unexpanded count at 0. Both wrong readings predict "does not fire" and
     * only the correct one predicts "fires".
     */
    @Test
    fun theCountSpansEveryRowOfTheStarredGroup() =
        agreeFires(atLeast = 3, expected = true, msg = "4 filled cells across 2 rows satisfies >= 3", build = ::twoFullRows)

    /** The negative control on the same document: 4 filled cells do not satisfy `>= 5`. */
    @Test
    fun theCountDoesNotOvercount() =
        agreeFires(atLeast = 5, expected = false, msg = "4 filled cells do not satisfy >= 5", build = ::twoFullRows)

    /**
     * The empty control. Without it, a `false` above could mean "the rule never evaluated" rather than a statement
     * about the count, and the `>= 1` bar makes any non-zero count fire — so only a genuinely empty read passes.
     */
    @Test
    fun anEmptyStarredGroupCountsNothing() =
        agreeFires(atLeast = 1, expected = false, msg = "no Lines rows at all → nothing to count") { b ->
            b.group("/Order", 1).field(REF, "R-1", 1, 1)
        }

    /** One filled cell in the second row only — the row-1 pin's own discriminator, at the count. */
    @Test
    fun theCountSeesACellThatExistsOnlyInTheSecondRow() =
        agreeFires(atLeast = 1, expected = true, msg = "only Lines[2]/A is filled; a row-1 read misses it") { b ->
            b.group("/Order", 1).field(REF, "R-1", 1, 1)
                .group(LINES, 1, 1)
                .group(LINES, 1, 2).field(A, "a2", 1, 2, 1)
        }
}

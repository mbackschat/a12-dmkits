package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Differential + tri-check — `MinValue`/`MaxValue` over a **group of DATE fields**, the shape [IG136] item (e)
 * names and the 2026-08-14 admission sweep measured as ADMITTED in both repetition shapes
 * ([KF187](../../../../../../../../../docs/KERNEL-FINDINGS.md)).
 *
 * **Why this exists, and what is actually at stake.** `dateAggregate` decides whether a min/max rides the
 * kernel's date machinery, and it asks `declaredKind(a.path)` of each operand — **null for a group path**, so a
 * group of DATE fields takes the NUMERIC branch. Its sibling `firstFilledEntityKind` in the same file already
 * implements the correct group fallback (`declaredKind(path) ?: containedFields(path).firstNotNullOfOrNull(…)`),
 * which is the "a site disagrees with a sibling that got it right" tell this whole class shares — now for the
 * fifth time.
 *
 * **The blindness has exactly TWO observable consequences, and reading the code is what bounds them to two.**
 * `Aggregates.min`/`max` use the empty identity only as a fallback (`values.minWithOrNull(ORDER) ?: empty`),
 * never as a seed, so a non-empty date selection folds correctly whatever `dateAggregate` answers. What is left:
 *
 * 1. **The empty identity.** `minMaxEmpty` yields `Val.Num(0)` on the numeric branch where the date branch
 *    yields `Val.Unknown` — so an operand whose every cell is empty resolves to numeric zero instead of "no
 *    value". [theEmptyDateGroupFoldsToNoValue] is that case.
 * 2. **The date-LITERAL coercion.** `producesDate` routes through the same `dateAggregate`, and it is what tells
 *    a counterpart string literal to parse as a German date rather than as plain text. So
 *    `MinValue(<date group>) < "01.07.2026"` compares a date against an uncoerced string on the blind branch.
 *    [theDateLiteralBesideAGroupExtremumIsADateLiteral] is that case.
 *
 * A third case carries the **expansion** itself rather than the kind dispatch: the group must reach BOTH its
 * date fields, so [theGroupExtremumReachesBothOfItsDateFields] arranges the two fields' values to make the
 * group's answer and the single-field control's answer fall on OPPOSITE sides of the compared date. Without
 * that, a group operand that silently read only its first field would agree with the control.
 *
 * **The group operand is authored as a literal, and only the group operand.** `Dsl.earliestOf`/`latestOf` accept
 * a `FieldListRef` and no `GroupListRef`, so the admitted group shape is type-level unrepresentable there
 * ([DG35](../../../../../../../../../docs/DEV-API-GAPS.md)) — reaching a shape the typed surface refuses is the
 * one legitimate reason to hand-write a condition in this estate. Every control IS authored through the typed
 * surface, and each group condition is derived from its control by substituting the operand alone, so the two
 * cannot differ in anything else. [assertOperandSubstituted] fails if that substitution silently misses, which
 * would otherwise make every row compare a condition to itself and pass.
 */
class DateGroupExtremaDiffTest {

    private companion object {
        const val REF = "/Claim/Reference"
        const val CLAIMED_ON = "/Claim/ClaimedOn"
        const val INSPECTIONS = "/Claim/Inspections"
        const val VISITED = "/Claim/Inspections/VisitedOn"
        const val CLOSED = "/Claim/Inspections/ClosedOn"

        /** The starred spellings: the `*` marks the REPEATABLE LEVEL, so it rides `Inspections`, not the leaf. */
        const val VISITED_STAR = "/Claim/Inspections*/VisitedOn"
        const val INSPECTIONS_STAR = "/Claim/Inspections*"
        const val CODE = "DGX"
    }

    /**
     * A motor-claim model whose repeatable `Inspections` group holds ONLY date fields of one declared format —
     * homogeneous, so no kind gate can be the answer, and every declared row is instantiated by each document
     * below so no omitted-repetition tail can supply the "fillable" reading that a date aggregate otherwise
     * carries (`AggregateUncertaintyLawsTest.aDateAggregateIsFillableUntilTheRepetitionIsExhausted`).
     */
    private val base = DocumentModelDraft.named("motor-claim").locale("en_US")
        .rootGroup("Claim") { g ->
            g.stringField("Reference") { }
            g.dateField("ClaimedOn") { it.format("dd.MM.yyyy") }
            g.group("Inspections") { i ->
                i.repeatable(2)
                    .dateField("VisitedOn") { it.format("dd.MM.yyyy") }
                    .dateField("ClosedOn") { it.format("dd.MM.yyyy") }
            }
        }.build().toJson()

    private val m = BoundModel.ofJson(base)

    /** `FieldFilled(Reference) And MinValue(<single date field list>) > [ClaimedOn]` — authored, the control. */
    private val earliestVisitAfterClaim: String = DslRenderer().render(
        m.field(REF).filled().and(Dsl.earliestOf(m.flatten(VISITED_STAR)).after(m.date(CLAIMED_ON))),
    )

    /** The same condition with the operand replaced by the GROUP it lives in — the only difference. */
    private val earliestInGroupAfterClaim: String =
        earliestVisitAfterClaim.replace(VISITED_STAR, INSPECTIONS_STAR)

    /**
     * Guard against a vacuous row: if the substitution above missed, both conditions would be the same string
     * and every comparison below would compare the control to itself while reading as agreement.
     */
    private fun assertOperandSubstituted() {
        assertNotEquals(earliestVisitAfterClaim, earliestInGroupAfterClaim,
            "the group operand substitution did not apply — every row would compare the control to itself")
        assertTrue(earliestInGroupAfterClaim.contains(INSPECTIONS_STAR)) {
            "the derived condition does not name the group operand: $earliestInGroupAfterClaim"
        }
    }

    private class Both(val kDoc: RuntimeLaws.Doc, val iDoc: Document) {
        fun group(path: String, vararg reps: Int): Both {
            kDoc.group(path, *reps); iDoc.group(path, *reps); return this
        }

        fun field(path: String, v: String, vararg reps: Int): Both {
            kDoc.field(path, v, *reps); iDoc.field(path, v, *reps); return this
        }
    }

    /** A rejected rule silences both engines, so the rejection must be louder than the comparison. */
    private fun assertAdmitted(codes: List<String>, msg: String) =
        assertTrue(codes.none { it.startsWith("MVK_") }) {
            "$msg — the kernel REJECTED the shape (${codes.filter { it.startsWith("MVK_") }}); that would be a " +
                "measurement about admission, not about the runtime, and every row below would compare silences"
        }

    private fun agreeFires(condition: String, expected: Boolean, msg: String, build: (Both) -> Unit) {
        val kModel = RuntimeLaws.withRule(base, "/Claim", "R", "../Reference", CODE, condition)
        val def = ModelLoader.load(kModel)

        val dynamic = Both(RuntimeLaws.doc(), def.newDocument()).also(build)
        val kernelMessages = RuntimeLaws.messages(kModel, dynamic.kDoc)
        assertAdmitted(kernelMessages.map { it.code() }, msg)
        val groovy = kernelMessages.any { it.code() == CODE }

        val staticDoc = Both(RuntimeLaws.doc(), def.newDocument()).also(build).kDoc
        val javaStatic = RuntimeLaws.kernelJavaEngine(kModel)
            .messagesModern(staticDoc, null, java.util.Locale.US).any { it.code() == CODE }

        val interpreter = DmInterpreter(def).validateFull(dynamic.iDoc).any { it.code == CODE }

        assertEquals(expected, groovy, "$msg — the kernel groovy-dynamic verdict")
        assertEquals(groovy, javaStatic, "$msg — groovy vs java-static (tri-check)")
        assertEquals(groovy, interpreter, "$msg — kernel vs INTERPRETER")
    }

    /** Both declared rows instantiated with every date cell EMPTY; only the compared date is filled. */
    private fun noDatesAtAll(b: Both) {
        b.group("/Claim", 1).field(REF, "C-1", 1, 1).field(CLAIMED_ON, "15.06.2026", 1, 1)
            .group(INSPECTIONS, 1, 1)
            .group(INSPECTIONS, 1, 2)
    }

    /**
     * `VisitedOn` in July, `ClosedOn` in June, the claim date between them. So the group's earliest date is
     * BEFORE the claim and the single field's earliest is AFTER it — the two spellings must disagree.
     */
    private fun visitsAfterButClosuresBefore(b: Both) {
        b.group("/Claim", 1).field(REF, "C-1", 1, 1).field(CLAIMED_ON, "15.06.2026", 1, 1)
            .group(INSPECTIONS, 1, 1)
            .field(VISITED, "10.07.2026", 1, 1, 1).field(CLOSED, "01.06.2026", 1, 1, 1)
            .group(INSPECTIONS, 1, 2)
            .field(VISITED, "12.07.2026", 1, 2, 1).field(CLOSED, "05.06.2026", 1, 2, 1)
    }

    /**
     * **CONSEQUENCE 1 — the empty identity.** Every date cell is empty, so the aggregate folds to its identity:
     * `Val.Unknown` on the date branch (no value — the comparison cannot fire) versus `Val.Num(0)` on the
     * numeric branch the group path currently takes. The control shares the document, so a divergence cannot be
     * about the fixture having no dates.
     */
    @Test
    fun theEmptyDateGroupFoldsToNoValue() {
        assertOperandSubstituted()
        agreeFires(earliestVisitAfterClaim, expected = false,
            msg = "control: no VisitedOn at all → the extremum has no value", build = ::noDatesAtAll)
        agreeFires(earliestInGroupAfterClaim, expected = false,
            msg = "GROUP: no date at all below the group → the extremum has no value", build = ::noDatesAtAll)
    }

    /**
     * **THE EXPANSION discriminator.** The group's earliest date (`ClosedOn` 01.06.2026) is before the claim
     * date and the control field's earliest (`VisitedOn` 10.07.2026) is after it, so the two spellings must
     * give OPPOSITE verdicts. A group operand that reached only its first declared field would instead agree
     * with the control, which is the reading this row exists to falsify.
     */
    @Test
    fun theGroupExtremumReachesBothOfItsDateFields() {
        assertOperandSubstituted()
        agreeFires(earliestVisitAfterClaim, expected = true,
            msg = "control: earliest VisitedOn 10.07.2026 is after the 15.06.2026 claim",
            build = ::visitsAfterButClosuresBefore)
        agreeFires(earliestInGroupAfterClaim, expected = false,
            msg = "GROUP: earliest date below the group is ClosedOn 01.06.2026, before the claim",
            build = ::visitsAfterButClosuresBefore)
    }

    /**
     * **CONSEQUENCE 2 — the date-literal coercion.** A string literal beside a date-producing expression is a
     * German date literal, and `producesDate` decides that through the same group-blind `dateAggregate`. The
     * control proves the coercion works for the field-list spelling, so a divergence isolates the group operand.
     */
    @Test
    fun theDateLiteralBesideAGroupExtremumIsADateLiteral() {
        val control = "FieldFilled(/Claim/Reference) And MinValue($VISITED_STAR) < \"01.07.2026\""
        val group = "FieldFilled(/Claim/Reference) And MinValue($INSPECTIONS_STAR) < \"01.07.2026\""
        agreeFires(control, expected = false,
            msg = "control: earliest VisitedOn 10.07.2026 is not before 01.07.2026",
            build = ::visitsAfterButClosuresBefore)
        agreeFires(group, expected = true,
            msg = "GROUP: earliest date below the group is 01.06.2026, before 01.07.2026",
            build = ::visitsAfterButClosuresBefore)
    }

    /**
     * The POLARITY of the firing, which the boolean rows above cannot see. A wrong message TYPE reads as
     * agreement under `any { code }` — the exact blindness that shipped a fresh defect green in this class's
     * ninth instance, in the same commit as the lock that was supposed to catch it.
     */
    @Test
    fun theGroupExtremumFiresWithTheKernelsPolarity() {
        assertOperandSubstituted()
        val kModel = RuntimeLaws.withRule(base, "/Claim", "R", "../Reference", CODE, earliestVisitAfterClaim)
        val def = ModelLoader.load(kModel)

        val dynamic = Both(RuntimeLaws.doc(), def.newDocument()).also(::visitsAfterButClosuresBefore)
        val groovy = RuntimeLaws.messages(kModel, dynamic.kDoc).filter { it.code() == CODE }
            .map { "${it.field()}|${it.type().name}" }.sorted()

        val staticDoc = Both(RuntimeLaws.doc(), def.newDocument()).also(::visitsAfterButClosuresBefore).kDoc
        val javaStatic = RuntimeLaws.kernelJavaEngine(kModel)
            .messagesModern(staticDoc, null, java.util.Locale.US).filter { it.code() == CODE }
            .map { "${it.field()}|${it.type().name}" }.sorted()

        val interpreter = DmInterpreter(def).validateFull(dynamic.iDoc).filter { it.code == CODE }
            .map { "${it.errorPath}|${if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR"}" }
            .sorted()

        assertEquals(groovy, javaStatic, "groovy vs java-static (tri-check)")
        assertEquals(groovy, interpreter, "kernel vs INTERPRETER — the polarity of a date extremum")
    }
}

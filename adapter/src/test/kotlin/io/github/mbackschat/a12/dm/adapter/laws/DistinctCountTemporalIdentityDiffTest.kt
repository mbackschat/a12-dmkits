package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.core.ModelConsistencyException
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — **which identity `NumberOfDifferentValues` compares two temporal operands by**: the decoded
 * instant, or the stored text.
 *
 * <p>Asked by a12-kernel-lean's `SG22` `route-state`, whose own note said no local witness could separate the
 * two readings, "under a mandatory single format the decoded and stored accounts agree on every currently
 * constructible input". That premise is what this file refutes, and the refutation is what makes the
 * measurement possible at all.
 *
 * <p><b>The format is not mandatory-single for this operator.</b>
 * [`TemporalAggregateComponentSetLawsTest`][TemporalAggregateComponentSetLawsTest] measures — on this operator
 * rather than carried from `Min`/`Max` — that the gate reads the declared component SET
 * (`DateFormat.hasSameComponents`) and not the format string, so `yyyy-MM-dd` beside `dd.MM.yyyy` is
 * ADMITTED while `yyyy-MM-dd` beside `yyyy-MM` is refused `MVK_DATEFORMATS_NOT_COMPATIBLE`. The neighbouring
 * `FieldValuesNotUnique` is the operator whose gate really is one identical declared format string, and it
 * refuses the very pair this one admits. Because the admitted pair exists, two operands can hold **different
 * stored texts denoting one date** — and there the two readings give different counts, 1 against 2.
 *
 * <p><b>Measured answer: the kernel compares the DECODED value.</b> `2024-03-05` beside `05.03.2024` counts as
 * **one** distinct value. So the distinct count is a chronological identity, not a textual one, and an author
 * cannot make two spellings of one date count twice by declaring them differently.
 *
 * <p><b>Two controls carry this, because the separator alone cannot.</b> A cell that counts 1 is consistent
 * with the decoded reading *and* with an engine that silently fails to evaluate a cross-spelling pair and
 * folds it to a single value — so `differingDatesInDifferentSpellingsStillCountTwice` holds the spelling
 * difference fixed and varies only whether the dates agree. It counts 2, which the collapse reading cannot
 * produce. The same-spelling row is the third leg: it counts 1 for a reason neither reading disputes, so a
 * count of 1 is demonstrably reachable through the ordinary path.
 *
 * <p>Own domain (motor-policy administration).
 */
class DistinctCountTemporalIdentityDiffTest {

    private companion object {
        const val GROUP = "/Policy"
        const val REFERENCE = "/Policy/Reference"
        const val ISO = "/Policy/EffectiveFrom"        // yyyy-MM-dd
        const val GERMAN = "/Policy/RecordedFrom"      // dd.MM.yyyy — equal component set, different spelling
        const val ISO_ALT = "/Policy/SupersededFrom"   // yyyy-MM-dd — the same-spelling partner
        const val CODE = "DC"
    }

    /**
     * Three DATE fields over one component set {YEAR, MONTH, DAY}, two of them spelled differently. No Base
     * Year: every declaration already carries a year, so supplementation plays no part and cannot be a second
     * variable in the rows below.
     */
    private val policy: String =
        DocumentModelDraft.named("motor-policy").locale("en_US")
            .rootGroup("Policy") { g ->
                g.stringField("Reference") { }
                g.dateField("EffectiveFrom") { d -> d.format("yyyy-MM-dd") }
                g.dateField("RecordedFrom") { d -> d.format("dd.MM.yyyy") }
                g.dateField("SupersededFrom") { d -> d.format("yyyy-MM-dd") }
            }
            .build().toJson()

    /**
     * The rule reads its own error field, which `MVK_ERROR_FIELD_NOT_REFERENCED` requires; `Reference` is
     * filled in every document below so the gate is true throughout and never becomes a second variable.
     */
    private fun withCount(left: String, right: String, expected: Int): String =
        RuntimeLaws.withRule(
            policy, GROUP, "R", "../Reference", CODE,
            "FieldFilled(Reference) And NumberOfDifferentValues(${left.removePrefix("$GROUP/")}, " +
                "${right.removePrefix("$GROUP/")}) == $expected",
        )

    /**
     * The distinct count each engine computes for one pair of stored values, read off by asking which of
     * `== 1` / `== 2` fires. `null` means neither did — a cell that is non-evaluable rather than counted,
     * which a bare `== 1` assertion would have reported as a legitimate 2.
     */
    private fun kernelCount(left: String, right: String, leftValue: String, rightValue: String): Int? =
        (1..2).firstOrNull { candidate ->
            val doc = RuntimeLaws.doc().group(GROUP, 1)
            doc.field(REFERENCE, "POL-1", 1, 1)
            doc.field(left, leftValue, 1, 1)
            doc.field(right, rightValue, 1, 1)
            CODE in RuntimeLaws.firedCodes(withCount(left, right, candidate), doc)
        }

    private fun interpreterCount(left: String, right: String, leftValue: String, rightValue: String): Int? =
        (1..2).firstOrNull { candidate ->
            val def = ModelLoader.load(withCount(left, right, candidate))
            val doc = def.newDocument().group(GROUP, 1)
            doc.field(REFERENCE, "POL-1", 1, 1)
            doc.field(left, leftValue, 1, 1)
            doc.field(right, rightValue, 1, 1)
            CODE in DmInterpreter(def).validate(doc).messages.map { it.code }.toSet()
        }

    private fun bothCount(left: String, right: String, leftValue: String, rightValue: String): Int? {
        val kernel = kernelCount(left, right, leftValue, rightValue)
        val interpreter = interpreterCount(left, right, leftValue, rightValue)
        assertEquals(kernel, interpreter, "engines disagree on the distinct count of $leftValue / $rightValue")
        return kernel
    }

    /**
     * The cross-spelling pair is admitted at all, which every row below depends on. Asserted rather than
     * assumed: were it refused, the counts would be measurements of a model that does not exist.
     */
    @Test
    fun theCrossSpellingPairIsAdmitted() {
        val rejections = try {
            BoundModel.ofJson(withCount(ISO, GERMAN, 1)); ""
        } catch (rejection: ModelConsistencyException) {
            rejection.message.orEmpty()
        }
        assertEquals("", rejections, "a differently-spelled pair of one component set must reach runtime")
    }

    /**
     * The separator: one date, two spellings, two engines.
     *
     * <p>A stored-text identity counts these as 2 and a decoded identity as 1. Both engines say **1**, so the
     * comparison is chronological. This is the row a12-kernel-lean's SG22 recorded as having no constructible
     * witness.
     */
    @Test
    fun oneDateInTwoSpellingsCountsOnce() {
        assertEquals(1, bothCount(ISO, GERMAN, "2024-03-05", "05.03.2024"),
            "decoded identity — the stored texts differ while the dates do not")
    }

    /**
     * The control that rules out a collapse, and the reason the row above is evidence rather than a
     * coincidence: the spelling difference stays, only the DATES change. An engine that cannot evaluate a
     * cross-spelling pair — folding it to one value, or to none — cannot produce this 2.
     */
    @Test
    fun differingDatesInDifferentSpellingsStillCountTwice() {
        assertEquals(2, bothCount(ISO, GERMAN, "2024-03-05", "06.03.2024"),
            "the cross-spelling comparison is live, so the count of 1 above is a decoded match")
    }

    /**
     * The third leg: same spelling, same date. Both readings agree on 1 here, so it demonstrates that a count
     * of 1 is reachable by the ordinary path and pins the operator's own behaviour independent of spelling.
     */
    @Test
    fun oneDateInOneSpellingCountsOnce() {
        assertEquals(1, bothCount(ISO, ISO_ALT, "2024-03-05", "2024-03-05"),
            "the uncontroversial leg — neither reading disputes this cell")
    }

    /** And its partner, so the same-spelling arm is not itself a constant. */
    @Test
    fun twoDatesInOneSpellingCountTwice() {
        assertEquals(2, bothCount(ISO, ISO_ALT, "2024-03-05", "2024-03-06"),
            "the same-spelling arm varies with the dates, as the cross-spelling arm does")
    }
}

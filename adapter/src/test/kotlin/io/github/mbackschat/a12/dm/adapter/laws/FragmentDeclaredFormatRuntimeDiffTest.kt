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
 * Differential — what a `DateFragmentType` does at RUNTIME when its declared format is one the four
 * conventional fragment spellings never covered (IG149).
 *
 * [KF252](docs/KERNEL-FINDINGS.md) measured that the fragment kind admits all twelve members of the shared
 * temporal vocabulary, not the `MM` / `yyyy` / `yyyy-MM` / `MM-dd` every surface in this estate named. That is
 * an ADMISSION result; §6's completion law — missing month/day to `01`, a missing year from the Base Year —
 * was measured on those four alone, so eight declarations had no measured behaviour on either engine.
 *
 * **The interpreter's exposure was structural rather than suspected.** `DateMath.completeFragmentToIso` exists
 * to supply the components a format omits and is driven by `dateFragment(format)`, which walks `y`/`M`/`d`
 * runs and treats every other character as a literal. Two shapes fall outside that: a format carrying NO date
 * component (`HH:mm:ss`), and the instant `yyyy-MM-dd'T'HH:mm:ss`, whose clock half becomes a literal so a
 * real value can never match the pattern the walker builds. Both return null, which the eval reads as an
 * unresolvable value — whether that is what the kernel does was the open question.
 *
 * **The answer is that the two engines agree everywhere, because neither takes the fragment path for those
 * declarations: `temporalClassifier` keys on the DECLARED FORMAT rather than on the kind, so a `HH:mm:ss`
 * fragment is handled as a clock value and the instant as a datetime.** That is a measured mechanism, not an
 * inference from agreement — `HH:mm:ss` holding `10:30:00` is formally clean while the same field holding a
 * non-date fires, and had the fragment completion been on the route the well-formed clock value would itself
 * have been malformed.
 *
 * **Reachability had to be attacked rather than concluded, and doing so overturned a reading.** A comparison
 * against a full DATE is refused for both shapes (`MVK_INVALID_COMPARE_TO_DATE`), and from that alone this
 * class first concluded the completion was unreachable — which is false. A comparison between two fragments
 * of the SAME declaration is admitted for every format, clock ones included, so the route is live and the
 * parity below is a real measurement rather than a vacuous one. Three routes are therefore covered: comparison
 * against a full date, paired comparison, and the formal check that runs on a stored value with no rule
 * reading it at all.
 *
 * Own domain (e-commerce warranty registration).
 */
class FragmentDeclaredFormatRuntimeDiffTest {

    private companion object {
        const val GROUP = "/Registration"
        const val REFERENCE = "/Registration/PurchasedOn"
        const val FRAGMENT = "/Registration/CoverFrom"
        const val PARTNER = "/Registration/CoverTo"
        const val CODE = "RR"
        const val ADMITTED = "ADMITTED"

        /**
         * The eight declarations KF252 made legal and §6 never measured, plus `yyyy-MM` as the conventional
         * control — without it, "these eight behave like this" and "every fragment behaves like this" are the
         * same observation.
         */
        val FORMATS = listOf(
            "yyyy-MM",                  // the control: a conventional fragment
            "yyyy-MM-dd", "dd.MM.yyyy", "yyyyMMdd",   // complete dates wearing the fragment kind
            "yyyyMM", "MMdd", "ddMM",                 // separator-free partials
            "HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss",      // no date component / a clock half
        )

        /** A value that is well-formed FOR ITS FORMAT, denoting 2024-06-01 wherever the format can say so. */
        val VALUE = mapOf(
            "yyyy-MM" to "2024-06",
            "yyyy-MM-dd" to "2024-06-01",
            "dd.MM.yyyy" to "01.06.2024",
            "yyyyMMdd" to "20240601",
            "yyyyMM" to "202406",
            "MMdd" to "0601",
            "ddMM" to "0106",
            "HH:mm:ss" to "10:30:00",
            "yyyy-MM-dd'T'HH:mm:ss" to "2024-06-01T10:30:00",
        )

        /** The subset the kernel lets a comparison against a full DATE reach — measured, not predicted. */
        val COMPARABLE = FORMATS.filter { it != "HH:mm:ss" && it != "yyyy-MM-dd'T'HH:mm:ss" }
    }

    /**
     * A one-fragment model carrying a Base Year, so a year-less declaration is comparable to a full date
     * rather than refused `MVK_NO_BASE_YEAR` — the gate that would otherwise decide every year-less row and
     * hide the completion this measures.
     */
    private fun model(fragmentFormat: String): String =
        DocumentModelDraft.named("warranty").locale("en_US").baseYear(2024)
            .rootGroup("Registration") { g ->
                g.dateField("PurchasedOn") { d -> d.format("yyyy-MM-dd") }
                g.dateFragmentField("CoverFrom") { f -> f.fragmentFormat(fragmentFormat) }
            }
            .build().toJson()

    private fun withComparison(fragmentFormat: String): String =
        RuntimeLaws.withRule(
            model(fragmentFormat), GROUP, "R", "../PurchasedOn", CODE,
            "[$FRAGMENT] == [$REFERENCE]",
        )

    /** [ADMITTED], or the distinct MVK codes the kernel's consistency gate reported for the rule. */
    private fun verdict(modelJson: String): String = try {
        BoundModel.ofJson(modelJson)
        ADMITTED
    } catch (rejection: ModelConsistencyException) {
        Regex("MVK_[A-Z_0-9]+").findAll(rejection.message.orEmpty())
            .map { it.value }.toSortedSet().joinToString(", ")
            .ifEmpty { rejection.message.orEmpty() }
    }

    /**
     * Which of the nine declarations can carry a comparison against a full DATE. Pinned first because a
     * refused cell has no comparison runtime, and reading the table below without this one would leave "the
     * engines agree" and "the case is unreachable" indistinguishable.
     *
     * <p><b>This says nothing about reachability in general, and reading it that way was the mistake this
     * class caught in itself.</b> The two clock-bearing declarations are refused HERE, against a full date —
     * and a first draft concluded from exactly this table that the completion was unreachable. It is not:
     * {@link #twoFragmentsOfTheSameDeclarationAreAlwaysComparable} shows a paired comparison is admitted for
     * every one of them. A refusal on one operand shape is a fact about that shape, never about the route.
     */
    @Test
    fun aFragmentWithNoDateComponentCannotBeComparedToAFullDate() {
        val actual = FORMATS.associateWith { verdict(withComparison(it)) }
        val expected = FORMATS.associateWith {
            if (it in COMPARABLE) ADMITTED else "MVK_INVALID_COMPARE_TO_DATE"
        }
        assertEquals(expected, actual, "pinned as measured; the refused cells are excluded from the runtime table")
    }

    /**
     * The completion each declaration performs, read off against a full DATE of a known value: the rule fires
     * exactly when the fragment completes to the reference date. Kernel and interpreter must agree per cell,
     * and the whole table is pinned so a change in either engine is visible rather than merely equal.
     */
    @Test
    fun bothEnginesCompleteEveryFragmentDeclarationIdentically() {
        val kernel = linkedMapOf<String, Boolean>()
        val interpreter = linkedMapOf<String, Boolean>()
        for (format in COMPARABLE) {
            val withRule = withComparison(format)
            val value = VALUE.getValue(format)

            val kDoc = RuntimeLaws.doc().group(GROUP, 1)
            kDoc.field(FRAGMENT, value, 1, 1)
            kDoc.field(REFERENCE, "2024-06-01", 1, 1)
            kernel[format] = CODE in RuntimeLaws.firedCodes(withRule, kDoc)

            val def = ModelLoader.load(withRule)
            val iDoc = def.newDocument().group(GROUP, 1)
            iDoc.field(FRAGMENT, value, 1, 1)
            iDoc.field(REFERENCE, "2024-06-01", 1, 1)
            interpreter[format] = CODE in DmInterpreter(def).validate(iDoc).messages.map { it.code }.toSet()
        }
        assertEquals(kernel, interpreter, "the interpreter must complete every legal fragment format as the kernel does")
        assertEquals(EXPECTED_FIRING, kernel, "pinned as measured — a moved cell is a finding, not a refresh")
    }

    /**
     * Stated as a literal table rather than derived from "does the format carry all three components",
     * deliberately: that predicate is what an implementation would use, and a test that shares it agrees with
     * the code instead of with the kernel.
     */
    private val EXPECTED_FIRING = linkedMapOf(
        "yyyy-MM" to true,
        "yyyy-MM-dd" to true,
        "dd.MM.yyyy" to true,
        "yyyyMMdd" to true,
        "yyyyMM" to true,
        "MMdd" to true,
        "ddMM" to true,
    )

    /**
     * The route the comparison refusal does NOT close: a fragment compared against ANOTHER fragment of the
     * same declaration rather than against a full date.
     *
     * <p>The refusal above is about comparing a date with a non-FITTING date, so two same-shaped operands are
     * exactly where it need not apply — and it does not. Every declaration, the two clock-bearing shapes
     * included, carries a paired comparison, which puts the completion the interpreter cannot perform back on
     * a live route. Measured rather than assumed, and the measurement overturned the reading the previous test
     * alone would have supported.
     */
    @Test
    fun twoFragmentsOfTheSameDeclarationAreAlwaysComparable() {
        val actual = PAIRED.associateWith { verdict(withPairedComparison(it)) }
        assertEquals(PAIRED.associateWith { ADMITTED }, actual,
            "a paired comparison is admitted for every declaration, so unreachability cannot close this route")
    }

    /**
     * What the two engines then DO on that route, which is what IG149 was actually about.
     *
     * <p>Each declaration is given two equal values and two different ones, so a cell that never fires is
     * distinguishable from one that always does. The equal/different pair is the control here: an engine that
     * silently treats every clock-declared fragment as unresolvable fires on neither, and would otherwise be
     * indistinguishable from one that compares them correctly and finds them unequal.
     */
    @Test
    fun bothEnginesComparePairedFragmentsIdentically() {
        val kernel = linkedMapOf<String, Boolean>()
        val interpreter = linkedMapOf<String, Boolean>()
        for (format in PAIRED) {
            for ((label, other) in listOf("equal" to VALUE.getValue(format), "different" to OTHER.getValue(format))) {
                val cell = "$format · $label"
                val withRule = withPairedComparison(format)

                val kDoc = RuntimeLaws.doc().group(GROUP, 1)
                kDoc.field(FRAGMENT, VALUE.getValue(format), 1, 1)
                kDoc.field(PARTNER, other, 1, 1)
                kernel[cell] = CODE in RuntimeLaws.firedCodes(withRule, kDoc)

                val def = ModelLoader.load(withRule)
                val iDoc = def.newDocument().group(GROUP, 1)
                iDoc.field(FRAGMENT, VALUE.getValue(format), 1, 1)
                iDoc.field(PARTNER, other, 1, 1)
                interpreter[cell] = CODE in DmInterpreter(def).validate(iDoc).messages.map { it.code }.toSet()
            }
        }
        assertEquals(kernel, interpreter, "the paired route reaches the fragment completion on both engines")
        assertEquals(EXPECTED_PAIRED, kernel, "pinned as measured — a moved cell is a finding, not a refresh")
    }

    private fun withPairedComparison(fragmentFormat: String): String {
        val twoFragments = DocumentModelDraft.named("warranty").locale("en_US").baseYear(2024)
            .rootGroup("Registration") { g ->
                g.dateFragmentField("CoverFrom") { f -> f.fragmentFormat(fragmentFormat) }
                g.dateFragmentField("CoverTo") { f -> f.fragmentFormat(fragmentFormat) }
            }
            .build().toJson()
        return RuntimeLaws.withRule(
            twoFragments, GROUP, "R", "../CoverTo", CODE, "[$FRAGMENT] == [$PARTNER]",
        )
    }

    /** A second value differing from [VALUE]'s, well-formed for the same declaration. */
    private val OTHER = mapOf(
        "yyyy-MM" to "2024-07",
        "yyyyMMdd" to "20240702",
        "HH:mm:ss" to "11:45:00",
        "yyyy-MM-dd'T'HH:mm:ss" to "2024-07-02T11:45:00",
    )

    /** Pinned as measured. The `different` row is what makes each `equal` row a comparison rather than a
     *  constant, and it is the cell an engine treating the value as unresolvable would get wrong. */
    private val EXPECTED_PAIRED = linkedMapOf(
        "yyyy-MM · equal" to true, "yyyy-MM · different" to false,
        "yyyyMMdd · equal" to true, "yyyyMMdd · different" to false,
        "HH:mm:ss · equal" to true, "HH:mm:ss · different" to false,
        "yyyy-MM-dd'T'HH:mm:ss · equal" to true, "yyyy-MM-dd'T'HH:mm:ss · different" to false,
    )

    /** One conventional control, one complete-format row, and the two shapes under test. */
    private val PAIRED = listOf("yyyy-MM", "yyyyMMdd", "HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss")

    /**
     * The path the comparison refusal does NOT close, and the one that decides IG149.
     *
     * <p>A formal check runs on a stored value whether or not any rule reads it, so a fragment declared
     * `HH:mm:ss` or with the instant format is reachable with a document alone — the refusal above only says
     * it cannot be COMPARED. Here the rule names a different field entirely, so every message is the fragment
     * value's own formal verdict, and the two engines are compared over all nine declarations.
     *
     * <p><b>The last row is the positive control, and the reason it is not optional.</b> Every other row here
     * could pass with an instrument that reports no formal errors at all — an empty set equals an empty set —
     * so a value that is malformed for a conventional fragment format is put through the same path and must
     * produce a verdict on both engines. Without it "they agree" and "nothing was measured" are the same
     * observation, which is the failure this estate has recorded on its own instruments.
     */
    @Test
    fun bothEnginesFormalCheckEveryFragmentDeclarationIdentically() {
        val kernel = linkedMapOf<String, Set<String>>()
        val interpreter = linkedMapOf<String, Set<String>>()
        for ((label, format, value) in FORMAL_ROWS) {
            val withRule = RuntimeLaws.withRule(
                model(format), GROUP, "R", "../PurchasedOn", CODE, "FieldFilled($REFERENCE)",
            )

            val kDoc = RuntimeLaws.doc().group(GROUP, 1)
            kDoc.field(FRAGMENT, value, 1, 1)
            kernel[label] = RuntimeLaws.firedCodes(withRule, kDoc)

            val def = ModelLoader.load(withRule)
            val iDoc = def.newDocument().group(GROUP, 1)
            iDoc.field(FRAGMENT, value, 1, 1)
            interpreter[label] = DmInterpreter(def).validate(iDoc).messages.map { it.code }.toSet()
        }
        assertEquals(kernel, interpreter, "a stored fragment value is formal-checked with no rule reading it")
        assertEquals(EXPECTED_FORMAL, kernel, "pinned as measured — a moved cell is a finding, not a refresh")
    }

    /**
     * `(label, declared format, stored value)` — every declaration twice: holding a value well-formed for it,
     * and holding one that is well-formed for none.
     *
     * <p><b>The malformed row is per-declaration, not one shared control, and that is the whole design.</b> A
     * single control on `yyyy-MM` proves the formal path is live for THAT declaration; it says nothing about a
     * declaration whose format the checker cannot parse, where "the value passed" and "the check never ran"
     * produce the same empty set. Since the two suspect declarations are exactly the ones the interpreter's
     * completion cannot express, a shared control would have been the confound rather than the guard.
     */
    private val FORMAL_ROWS: List<Triple<String, String, String>> =
        FORMATS.flatMap {
            listOf(
                Triple("$it holding a value well-formed for it", it, VALUE.getValue(it)),
                Triple("$it holding a non-date", it, "not-a-date"),
            )
        }

    /**
     * Pinned as measured. Every declaration's malformed row fires, so the formal check is demonstrably LIVE
     * for all nine — including the two whose format the fragment completion cannot parse, which is what turns
     * their clean well-formed row from an absence of evidence into a result.
     */
    private val EXPECTED_FORMAL = linkedMapOf(
        "yyyy-MM holding a value well-formed for it" to emptySet<String>(),
        "yyyy-MM holding a non-date" to setOf("datumFormatFalsch"),
        "yyyy-MM-dd holding a value well-formed for it" to emptySet<String>(),
        "yyyy-MM-dd holding a non-date" to setOf("datumFormatFalsch"),
        "dd.MM.yyyy holding a value well-formed for it" to emptySet<String>(),
        "dd.MM.yyyy holding a non-date" to setOf("datumFormatFalsch"),
        "yyyyMMdd holding a value well-formed for it" to emptySet<String>(),
        "yyyyMMdd holding a non-date" to setOf("datumFormatFalsch"),
        "yyyyMM holding a value well-formed for it" to emptySet<String>(),
        "yyyyMM holding a non-date" to setOf("datumFormatFalsch"),
        "MMdd holding a value well-formed for it" to emptySet<String>(),
        "MMdd holding a non-date" to setOf("datumFormatFalsch"),
        "ddMM holding a value well-formed for it" to emptySet<String>(),
        "ddMM holding a non-date" to setOf("datumFormatFalsch"),
        "HH:mm:ss holding a value well-formed for it" to emptySet<String>(),
        "HH:mm:ss holding a non-date" to setOf("datumFormatFalsch"),
        "yyyy-MM-dd'T'HH:mm:ss holding a value well-formed for it" to emptySet<String>(),
        "yyyy-MM-dd'T'HH:mm:ss holding a non-date" to setOf("datumFormatFalsch"),
    )
}

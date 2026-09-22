package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DateFieldBuilder
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.function.Consumer

/**
 * KERNEL-FINDINGS [KF176] — a `DateType` whose declared **format** omits calendar components (`yyyy`, `yyyy-MM`,
 * `yyyyMM`, `MM`) holds a value that is complete FOR ITS FORMAT, and the kernel resolves it to a concrete date by
 * completing the rest. The interpreter reconciliation is IF250 (graduated from IG110), where it resolved a `yyyy`
 * cell to Unknown — silently suppressing every referencing rule — and emitted a false `datumFormatFalsch` on the
 * other three.
 *
 * <p>The admission side (which formats are legal, and which extractor each admits) is kernel-only and lives in
 * `validate.laws.IncompleteDateFormatAdmissionLawsTest`. This file is the runtime half.
 *
 * <p><b>Why the completion target needs a comparison to be observed at all.</b> An extractor for a component the
 * format omits is refused at codegen, so `MonthFromDate`/`DayFromDate` cannot read what a completion supplies on a
 * `yyyy` field. [theCompletionFillsMonthAndDayToOneAndAMissingYearFromTheModelBaseYear] therefore compares the
 * incomplete field against a full-format sibling holding a candidate date, and reads the completion off which
 * candidate matches. That also makes the year source **discriminated** rather than assumed: the model declares a
 * non-default base year 2011, and the `MM` field matches `2011-03-01` while 2000, 1970 and the current year all
 * fail — a hardcoded constant gets that backwards.
 */
class IncompleteDateFormatDiffTest {

    private fun render(c: Condition): String = RuntimeLaws.render(c)

    /** A model with one date field at [format] and a full-format sibling to compare it against. */
    private fun model(format: String, baseYear: Int?): String {
        var draft = DocumentModelDraft.named("visit").locale("en_US")
        if (baseYear != null) draft = draft.baseYear(baseYear)
        return draft.rootGroup("Visit", Consumer { g ->
            g.dateField("SeenOn", Consumer<DateFieldBuilder> { it.format(format) })
            g.dateField("Full", Consumer<DateFieldBuilder> { it.format("yyyy-MM-dd") })
        }).build().toJson()
    }

    /**
     * Evaluate [condition] over a document setting each (path → value) cell, and return the `code@field` message
     * list as the dynamic kernel and the interpreter each report it.
     */
    private fun readings(
        json: String,
        errorRel: String,
        condition: String,
        cells: List<Pair<String, String>>,
    ): Pair<List<String>, List<String>> {
        val kModel = RuntimeLaws.withRule(json, "/Visit", "FU", errorRel, "FU", condition)
        val def = ModelLoader.load(kModel)
        val kDoc = RuntimeLaws.doc().group("/Visit", 1)
        val iDoc = def.newDocument().group("/Visit", 1)
        for ((path, value) in cells) {
            kDoc.field(path, value, 1, 1); iDoc.field(path, value, 1, 1)
        }
        fun short(field: String) = field.substringAfterLast('/')
        return RuntimeLaws.messages(kModel, kDoc).map { "${it.code()}@${short(it.field())}" } to
            DmInterpreter(def).validateFull(iDoc).map { "${it.code}@${short(it.errorPath)}" }
    }

    /** Assert both engines agree, and pin what the kernel itself said — agreement alone cannot catch two engines
     *  that are equally silent, which is exactly the shape of the defect this file locks. */
    private fun agree(label: String, json: String, errorRel: String, condition: String,
                      cells: List<Pair<String, String>>, expected: List<String>) {
        val (kernel, interpreter) = readings(json, errorRel, condition, cells)
        assertEquals(kernel, interpreter, "$label — kernel/interpreter messages")
        assertEquals(expected, kernel, "$label — what the kernel itself reports")
    }

    @Test fun theCompletionFillsMonthAndDayToOneAndAMissingYearFromTheModelBaseYear() {
        val rows = mutableListOf<String>()
        val expected = mutableListOf<String>()
        for ((declared, candidates) in listOf(
            Triple("yyyy", "2020", listOf("2020-01-01" to true, "2020-12-31" to false, "2020-06-15" to false)),
            Triple("yyyy-MM", "2020-03", listOf("2020-03-01" to true, "2020-03-31" to false)),
            Triple("yyyyMM", "202003", listOf("2020-03-01" to true, "2020-03-31" to false)),
            // The year-source discriminator — base year 2011, so a hardcoded 2000/1970 completion fails here.
            Triple("MM", "03", listOf(
                "2011-03-01" to true, "2000-03-01" to false, "1970-03-01" to false, "2026-03-01" to false)),
        ).map { (format, value, candidates) -> (format to value) to candidates }) {
            val (format, value) = declared
            val json = model(format, baseYear = 2011)
            val bound = BoundModel.ofJson(json)
            val condition = render(bound.date(SEEN).eq(bound.date(FULL)))
            for ((candidate, mustFire) in candidates) {
                val (kernel, interpreter) = readings(
                    json, "../SeenOn", condition, listOf(SEEN to value, FULL to candidate))
                assertEquals(kernel, interpreter, "[$format]='$value' vs $candidate — kernel/interpreter")
                rows += "[$format]='$value' vs $candidate => ${kernel.ifEmpty { listOf("(silent)") }}"
                expected += "[$format]='$value' vs $candidate => " +
                    if (mustFire) "[FU@SeenOn]" else "[(silent)]"
            }
        }
        assertEquals(expected, rows, "the completion target as the dynamic kernel reports it")
    }

    @Test fun anAdmittedExtractorReadsTheStoredComponent() {
        // The one component each incomplete format DOES carry stays readable — so the completion did not displace
        // the stored value. `MM` has no admitted year extractor, hence its month row only.
        for ((format, value, condition) in listOf(
            Triple("yyyy", "2020", "year"),
            Triple("yyyy-MM", "2020-03", "month"),
            Triple("yyyyMM", "202003", "month"),
            Triple("MM", "03", "month"),
        )) {
            val json = model(format, baseYear = 2011)
            val bound = BoundModel.ofJson(json)
            val seen = bound.date(SEEN)
            val dsl = render(
                if (condition == "year") Dsl.yearFromDate(seen).eq(2020L) else Dsl.monthFromDate(seen).eq(3L),
            )
            agree("[$format] $condition extractor", json, "../SeenOn", dsl,
                listOf(SEEN to value), listOf("FU@SeenOn"))
        }
    }

    @Test fun anIncompleteFormatCellIsFilledAndOrdersAsItsCompletedDate() {
        val json = model("yyyy", baseYear = 2011)
        val bound = BoundModel.ofJson(json)
        val seen = bound.date(SEEN)
        val full = bound.date(FULL)
        // Ordering: the completed 2020-01-01 is BEFORE 2020-06-15 and AFTER 2019-12-31 — two directions, so the
        // result cannot be explained by a cell that simply never compares.
        agree("yyyy orders before a later full date", json, "../SeenOn", render(seen.before(full)),
            listOf(SEEN to "2020", FULL to "2020-06-15"), listOf("FU@SeenOn"))
        agree("yyyy does not order before an earlier full date", json, "../SeenOn", render(seen.before(full)),
            listOf(SEEN to "2020", FULL to "2019-12-31"), emptyList())
        // Presence: the cell is FILLED, not formally invalid — the defect made it neither.
        agree("yyyy is filled", json, "../SeenOn", render(Dsl.fieldFilled(bound.field(SEEN))),
            listOf(SEEN to "2020"), listOf("FU@SeenOn"))
    }

    @Test fun aValueMalformedForItsOwnIncompleteFormatIsStillAFormatError() {
        // The regression control on the fix's shape: completing must not become a licence to accept anything. Each
        // value below is refused for its declared format — wrong width, an out-of-range component, or a stray
        // separator — and must still draw the kernel's date-format error rather than being completed.
        for ((format, bad) in listOf(
            "yyyy" to "202", "yyyy" to "20200", "yyyy-MM" to "2020-13",
            "yyyy-MM" to "2020-3", "yyyyMM" to "2020-03", "MM" to "13", "MM" to "3",
        )) {
            val json = model(format, baseYear = 2011)
            val bound = BoundModel.ofJson(json)
            // The FU rule references SeenOn, so an invalid cell also SUPPRESSES it — the expected list carries
            // the format error alone, which is the second half of the claim.
            agree("[$format] rejects '$bad'", json, "../SeenOn",
                render(Dsl.fieldFilled(bound.field(SEEN))), listOf(SEEN to bad),
                listOf("datumFormatFalsch@SeenOn"))
        }
    }

    private companion object {
        const val SEEN = "/Visit/SeenOn"
        const val FULL = "/Visit/Full"
    }
}

package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * KERNEL-FINDINGS §1 — <b>which value kinds may share one {@code FirstFilledValue} operand list</b>.
 *
 * <p><b>Why this file exists.</b> Our operator catalog published the partition as a {@code constraints} line —
 * "all expanded values must share one owner family (STRING/CUSTOM/ENUMERATION or DATE/DATE_FRAGMENT are
 * compatible families)" — and `dmtool operators FirstFilledValue` serves it to any agent that asks. It was
 * prose, not a measured row: a12-kernel-lean correctly pointed out that our claim of having closed this operator
 * covered its *admission* only, never its expansion kind class. Measuring it found the published line wrong on the date half — though
 * not in the way this file first concluded: the temporal gate keys on the <b>declared format</b>, so DATE and
 * DATE_FRAGMENT DO interchange at one format, and a format mismatch is refused within a single kind.
 *
 * <p>Every row is a complete emitted code list, and each refusal is paired with the control that attributes it
 * to the operand list rather than to the comparison. {@link #theTemporalGateKeysOnTheDeclaredFormatNotTheKind}
 * carries the correction, and its own first version is the cautionary case: one row that varied kind and format
 * together looked decisive and pointed the wrong way.
 */
class FirstFilledValueFamilyLawsTest {

    /**
     * One group, one field per kind, so any pair can be put in an operand list without a second variable
     * moving. The date family needs its own builders ({@code dateField}/{@code dateFragmentField}) because a
     * kernel date-fragment carries a required fragment format — {@code FieldKind} covers only the five simple
     * kinds and is not the whole authoring surface.
     */
    private static final String MODEL = DocumentModelDraft.named("catalogue").locale("en_US")
            .rootGroup("Item", i -> i
                    .field("Title", FieldKind.STRING)
                    .field("Subtitle", FieldKind.STRING)
                    .field("Weight", FieldKind.NUMBER)
                    .field("Height", FieldKind.NUMBER)
                    .field("InStock", FieldKind.CONFIRM)
                    .enumField("Grade", e -> e.value("NEW").value("USED"))
                    .customField("StockCode", f -> f.type("SelectionCode"))
                    .dateField("ListedOn", d -> d.format("yyyy-MM-dd"))
                    .dateField("SoldOn", d -> d.format("yyyy-MM-dd"))
                    .dateFragmentField("SeasonPeriod", f -> f.fragmentFormat("yyyy-MM"))
                    .dateFragmentField("SeasonMonth", f -> f.fragmentFormat("MM"))
                    .dateField("StocktakeYear", d -> d.format("yyyy"))
                    .dateFragmentField("ReportingYear", f -> f.fragmentFormat("yyyy"))
                    .dateField("AuditedOn", d -> d.format("dd.MM.yyyy"))
                    // The rest of the temporal family, each at its conventional format; every date-bearing kind
                    // admits the whole vocabulary — see theLegalFormatSetPerTemporalKindIsAWhitelist.
                    .timeField("OpensAt", t -> t.format("HH:mm:ss"))
                    .timeField("ClosesAt", t -> t.format("HH:mm:ss"))
                    .timeField("ShiftEnd", t -> t.format("HH:mm:ss"))
                    .dateTimeField("ReceivedAt", d -> d.format("yyyy-MM-dd'T'HH:mm:ss"))
                    .dateTimeField("ShippedAt", d -> d.format("yyyy-MM-dd'T'HH:mm:ss"))
                    // A DATE_TIME may ALSO be declared time-only (KERNEL-FINDINGS §6, probe-enumerated
                    // 2026-06-26). That second legal format is what makes the discrimination authorable off
                    // the DATE/DATE_FRAGMENT pair — and it shares its format string with a TIME field.
                    .dateTimeField("ClockedAt", d -> d.format("HH:mm:ss"))
                    // DATE_RANGE also carries TWO legal declarations — the (format, separator) PAIR is
                    // allowlisted (KERNEL-FINDINGS §6), so both discriminating rows are authorable here too.
                    .dateRangeField("PromoWindow", r -> r.format("dd.MM.yyyy").separator("-"))
                    .dateRangeField("SaleWindow", r -> r.format("dd.MM.yyyy").separator("-"))
                    .dateRangeField("AuditWindow", r -> r.format("yyyy-MM-dd").separator("/"))
                    .dateRangeField("RenewalWindow", r -> r.format("dd.MM.yyyy").separator("-"))
                    .dateRangeField("SeasonYears", r -> r.format("yyyy").separator("/"))
                    .dateRangeField("SeasonPeriods", r -> r.format("yyyy-MM").separator("/"))
                    .dateRangeField("PriorSeasonYears", r -> r.format("yyyy").separator("/")))
            .build().toJson();

    // ── the temporal gate: declared FORMAT, not kind ───────────────────────────────────────────────

    /**
     * THE measurement, and it corrects this file's own first version.
     *
     * <p>That version asserted "DATE and DATE_FRAGMENT are not a family" from a single row comparing a
     * {@code yyyy-MM-dd} Date against a {@code yyyy-MM} DateFragment — which varies the KIND and the DECLARED
     * FORMAT together, so it cannot say which one the kernel refused. Separating them inverts the conclusion:
     * a Date declared {@code yyyy} beside a DateFragment declared {@code yyyy} is <b>admitted across two kinds</b>,
     * while two Dates differing only in declared format are <b>refused</b>. The gate reads the declared format.
     *
     * <p>This is the same rule {@link FieldValuesNotUniqueAdmissionLawsTest} already locked for its own carrier,
     * measured there against the same discriminating pair — so the two carriers agree, and a "compatible families"
     * table phrased in KINDS is wrong on both.
     */
    @Test
    void theTemporalGateKeysOnTheDeclaredFormatNotTheKind() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("same FORMAT, different KIND (DATE yyyy + FRAGMENT yyyy)", verdict("StocktakeYear",
                "FirstFilledValue(StocktakeYear, ReportingYear) == Today"));
        actual.put("same KIND, different FORMAT (DATE yyyy-MM-dd + DATE dd.MM.yyyy)", verdict("ListedOn",
                "FirstFilledValue(ListedOn, AuditedOn) == Today"));
        actual.put("same kind, same format — control", verdict("ListedOn",
                "FirstFilledValue(ListedOn, SoldOn) == Today"));
        actual.put("different kind AND different format — the confounded row", verdict("ListedOn",
                "FirstFilledValue(ListedOn, SeasonPeriod) == Today"));
        actual.put("[DATE yyyy] == Today — operand control", verdict("StocktakeYear", "[StocktakeYear] == Today"));
        actual.put("[FRAGMENT yyyy] == Today — operand control", verdict("ReportingYear",
                "[ReportingYear] == Today"));
        // A fragment must carry a YEAR to compare with a full date at all. That is the comparability
        // partition, a different gate from the one under test, pinned so the controls above cannot be
        // read as "any fragment compares to Today".
        actual.put("a YEARLESS fragment cannot compare to a full date at all", verdict("SeasonMonth",
                "[SeasonMonth] == Today"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("same FORMAT, different KIND (DATE yyyy + FRAGMENT yyyy)", "admitted");
        expected.put("same KIND, different FORMAT (DATE yyyy-MM-dd + DATE dd.MM.yyyy)",
                "MVK_VARYING_TYPES_NOT_ALLOWED");
        expected.put("same kind, same format — control", "admitted");
        expected.put("different kind AND different format — the confounded row",
                "MVK_VARYING_TYPES_NOT_ALLOWED");
        expected.put("[DATE yyyy] == Today — operand control", "admitted");
        expected.put("[FRAGMENT yyyy] == Today — operand control", "admitted");
        expected.put("a YEARLESS fragment cannot compare to a full date at all",
                "MVK_INVALID_COMPARE_TO_DATE");
        assertEquals(expected, actual,
                "the temporal gate reads the DECLARED FORMAT, not the kind — row 1 admits two kinds at one "
                        + "format, row 2 refuses one kind at two formats, and row 4 is why a single row proves "
                        + "neither");
    }

    /**
     * The legal declared-format set per temporal kind — the enumeration that says WHERE the format-vs-kind
     * discrimination can even be run.
     *
     * <p>Re-measured here rather than assumed, because the first version of this file assumed it and got it
     * wrong in the direction that matters: it probed six {@code TIME} and five {@code DATE_TIME} candidates,
     * saw one acceptance each, and concluded "exactly one format per kind, so the discrimination is
     * unauthorable outside DATE/DATE_FRAGMENT". That is a completeness claim drawn from a sample, and the
     * sample simply missed a legal value — {@code DATE_TIME} also accepts the degenerate <b>time-only</b>
     * {@code HH:mm:ss}, which this repository had already probe-enumerated in KERNEL-FINDINGS §6 on
     * 2026-06-26. The second format is exactly what makes
     * {@link #theFormatKeyingHoldsOnASecondKindPairSharingOneFormat} authorable.
     *
     * <p><i>Corrected 2026-09-22:</i> the corrected bound — one {@code TIME} format, two {@code DATE_TIME} — was
     * the same sample-based claim one level down. These candidates held no date format at all, and the model
     * admits every vocabulary format on every date-bearing kind ({@code docs/TEMPORAL-FORMAT-VOCABULARY.md}),
     * so a {@code TIME} declared {@code yyyy-MM-dd} is legal too. The last two rows show it; the vocabulary, not
     * this list, is the enumeration. What it made askable is wider than this test says:
     * {@code FirstFilledValueCrossKindFormatLawsTest} shares one list between any two date-bearing kinds.
     */
    @Test
    void theLegalFormatSetPerTemporalKindIsAWhitelist() {
        Map<String, String> actual = new LinkedHashMap<>();
        for (String format : List.of("HH:mm:ss", "HH:mm", "HH", "H:mm:ss", "hh:mm:ss a", "HH:mm:ss.SSS")) {
            actual.put("TIME " + format, modelVerdict(g -> g.timeField("T", t -> t.format(format))));
        }
        for (String format : List.of("yyyy-MM-dd'T'HH:mm:ss", "HH:mm:ss", "yyyy-MM-dd HH:mm:ss",
                "dd.MM.yyyy'T'HH:mm:ss", "MM/dd/yyyy'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm",
                "yyyy-MM-dd'T'HH:mm:ss.SSS")) {
            actual.put("DATE_TIME " + format, modelVerdict(g -> g.dateTimeField("D", d -> d.format(format))));
        }
        actual.put("TIME yyyy-MM-dd", modelVerdict(g -> g.timeField("T", t -> t.format("yyyy-MM-dd"))));
        actual.put("DATE_TIME yyyy-MM-dd", modelVerdict(g -> g.dateTimeField("D", d -> d.format("yyyy-MM-dd"))));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("TIME HH:mm:ss", "model accepted");
        expected.put("TIME HH:mm", INVALID_FORMAT);
        expected.put("TIME HH", INVALID_FORMAT);
        expected.put("TIME H:mm:ss", INVALID_FORMAT);
        expected.put("TIME hh:mm:ss a", INVALID_FORMAT);
        expected.put("TIME HH:mm:ss.SSS", INVALID_FORMAT);
        // TWO legal DATE_TIME formats, not one — the correction this test exists to pin.
        expected.put("DATE_TIME yyyy-MM-dd'T'HH:mm:ss", "model accepted");
        expected.put("DATE_TIME HH:mm:ss", "model accepted");
        expected.put("DATE_TIME yyyy-MM-dd HH:mm:ss", INVALID_FORMAT);
        expected.put("DATE_TIME dd.MM.yyyy'T'HH:mm:ss", INVALID_FORMAT);
        expected.put("DATE_TIME MM/dd/yyyy'T'HH:mm:ss", INVALID_FORMAT);
        expected.put("DATE_TIME yyyy-MM-dd'T'HH:mm", INVALID_FORMAT);
        expected.put("DATE_TIME yyyy-MM-dd'T'HH:mm:ss.SSS", INVALID_FORMAT);
        // a date format on a clock kind — the candidates above never asked, and it is admitted
        expected.put("TIME yyyy-MM-dd", "model accepted");
        expected.put("DATE_TIME yyyy-MM-dd", "model accepted");
        assertEquals(expected, actual,
                "a clock kind refuses the other clock spellings yet admits a date format, so no clock-only "
                        + "candidate list bounds its set — the derived vocabulary does");
    }

    /**
     * THE independent witness: format-keying, measured on a second kind pair that is nothing like the first.
     *
     * <p>{@link #theTemporalGateKeysOnTheDeclaredFormatNotTheKind} establishes the rule on {@code DATE} +
     * {@code DATE_FRAGMENT} — the closest pair in the family, since a fragment IS a partial date, so a sceptic
     * can fairly ask whether the kernel simply treats those two as one type. {@code TIME} and {@code DATE_TIME}
     * are not plausibly one type, and because {@code DATE_TIME} admits the time-only {@code HH:mm:ss} they can
     * be made to <b>share a declared format string exactly</b>. That gives both discriminating rows on a fresh
     * pair:
     *
     * <ul>
     *   <li><b>Same kind, two formats</b> — two {@code DATE_TIME} fields, one ISO and one time-only.</li>
     *   <li><b>Two kinds, one format</b> — a {@code TIME} and a {@code DATE_TIME}, both {@code HH:mm:ss}.</li>
     * </ul>
     *
     * <p>Each operand also appears alone against the same comparison, so a refusal cannot be charged to the
     * comparison rather than to the operand list.
     */
    @Test
    void theFormatKeyingHoldsOnASecondKindPairSharingOneFormat() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("TWO KINDS, one shared format (TIME HH:mm:ss + DATE_TIME HH:mm:ss)", verdict("OpensAt",
                "FirstFilledValue(OpensAt, ClockedAt) == [ShiftEnd]"));
        actual.put("ONE KIND, two formats (DATE_TIME ISO + DATE_TIME HH:mm:ss)", verdict("ReceivedAt",
                "FirstFilledValue(ReceivedAt, ClockedAt) == Now"));
        actual.put("TIME + TIME, one format — control", verdict("OpensAt",
                "FirstFilledValue(OpensAt, ClosesAt) == [ShiftEnd]"));
        actual.put("DATE_TIME + DATE_TIME, one format — control", verdict("ReceivedAt",
                "FirstFilledValue(ReceivedAt, ShippedAt) == Now"));
        actual.put("TIME + DATE_TIME ISO — two kinds AND two formats, confounded", verdict("OpensAt",
                "FirstFilledValue(OpensAt, ReceivedAt) == [ShiftEnd]"));
        actual.put("[TIME] == [TIME] — operand control", verdict("OpensAt", "[OpensAt] == [ShiftEnd]"));
        actual.put("[DATE_TIME HH:mm:ss] == [TIME] — operand control", verdict("ClockedAt",
                "[ClockedAt] == [ShiftEnd]"));
        actual.put("[DATE_TIME ISO] == Now — operand control", verdict("ReceivedAt", "[ReceivedAt] == Now"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("TWO KINDS, one shared format (TIME HH:mm:ss + DATE_TIME HH:mm:ss)", "admitted");
        expected.put("ONE KIND, two formats (DATE_TIME ISO + DATE_TIME HH:mm:ss)",
                "MVK_VARYING_TYPES_NOT_ALLOWED");
        expected.put("TIME + TIME, one format — control", "admitted");
        expected.put("DATE_TIME + DATE_TIME, one format — control", "admitted");
        expected.put("TIME + DATE_TIME ISO — two kinds AND two formats, confounded",
                "MVK_VARYING_TYPES_NOT_ALLOWED");
        expected.put("[TIME] == [TIME] — operand control", "admitted");
        expected.put("[DATE_TIME HH:mm:ss] == [TIME] — operand control", "admitted");
        expected.put("[DATE_TIME ISO] == Now — operand control", "admitted");
        assertEquals(expected, actual,
                "on a pair no one would call one type, two kinds at one format admit and one kind at two "
                        + "formats refuses — the rule is the declared format, and it is not an artifact of "
                        + "DATE and DATE_FRAGMENT being neighbours");
    }

    /**
     * The last member of the temporal family, and the one the corrected entry admitted it had skipped.
     *
     * <p>The rule was published as "the temporal gate reads the declared format" while `DATE_RANGE` had never
     * been put under it — an omission inside a claim phrased over the whole family, which is the same shape of
     * error as the sample-based bound this file already carries. It is askable because a range declares a
     * <b>(format, separator) PAIR</b>, and several are legal: this file's two exact ones plus the four
     * component subsets {@code yyyy}, {@code yyyy-MM}, {@code MM}, {@code MM-dd}, all measured under
     * SPEC-2026-08-19-03. (An earlier version of this comment said "two legal pairs exist", which was a count
     * of the fields in this fixture rather than of the profiles the kernel admits.)
     */
    @Test
    void theFormatKeyingCoversDateRangeToo() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("RANGE + RANGE, same (format, separator) — control", verdict("PromoWindow",
                "FirstFilledValue(PromoWindow, SaleWindow) == [PromoWindow]"));
        actual.put("RANGE + RANGE, two legal pairs", verdict("PromoWindow",
                "FirstFilledValue(PromoWindow, AuditWindow) == [PromoWindow]"));
        actual.put("RANGE + DATE — two kinds", verdict("PromoWindow",
                "FirstFilledValue(PromoWindow, ListedOn) == [PromoWindow]"));
        // …and the same pair with the DATE declared at the RANGE's own format, which is what tells the range
        // apart from a differently-spelled date: this row moves ONLY the kind, where the one above moves the
        // format too. Added 2026-09-21 — the range is refused at its own format, so its separateness is a
        // result-family fact rather than a format one, and the family claim above needed this to be sound.
        actual.put("RANGE + DATE at the RANGE's own format — kind alone", verdict("PromoWindow",
                "FirstFilledValue(PromoWindow, AuditedOn) == [PromoWindow]"));
        actual.put("[RANGE] == [RANGE] — operand control", verdict("PromoWindow",
                "[PromoWindow] == [SaleWindow]"));
        actual.put("[DATE dd.MM.yyyy] == [DATE dd.MM.yyyy] — operand control", verdict("AuditedOn",
                "[AuditedOn] == [AuditedOn]"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("RANGE + RANGE, same (format, separator) — control", "admitted");
        expected.put("RANGE + RANGE, two legal pairs", "MVK_VARYING_TYPES_NOT_ALLOWED");
        expected.put("RANGE + DATE — two kinds", "MVK_VARYING_TYPES_NOT_ALLOWED");
        expected.put("RANGE + DATE at the RANGE's own format — kind alone", "MVK_VARYING_TYPES_NOT_ALLOWED");
        expected.put("[RANGE] == [RANGE] — operand control", "admitted");
        expected.put("[DATE dd.MM.yyyy] == [DATE dd.MM.yyyy] — operand control", "admitted");
        assertEquals(expected, actual,
                "the declared-format rule holds on DATE_RANGE as well, so the family claim covers every temporal "
                        + "kind rather than the four that happened to be measured — and the range stays refused "
                        + "beside a date of its OWN format, so it is a separate family and not a spelling");
    }

    // ── the string-side family, which the catalog had right ─────────────────────────────────────────

    @Test
    void stringCustomAndEnumerationDoShareOneFamily() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("STRING + STRING — control", verdict("Title",
                "FirstFilledValue(Title, Subtitle) == \"x\""));
        actual.put("STRING + ENUMERATION", verdict("Title", "FirstFilledValue(Title, Grade) == \"x\""));
        actual.put("STRING + CUSTOM", verdict("Title", "FirstFilledValue(Title, StockCode) == \"x\""));
        actual.put("ENUMERATION + CUSTOM", verdict("Grade", "FirstFilledValue(Grade, StockCode) == \"x\""));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("STRING + STRING — control", "admitted");
        expected.put("STRING + ENUMERATION", "admitted");
        expected.put("STRING + CUSTOM", "admitted");
        expected.put("ENUMERATION + CUSTOM", "admitted");
        assertEquals(expected, actual, "the string-side family holds as the catalog states it");
    }

    // ── the incompatible pairs, so "family" has a boundary rather than being vacuous ─────────────────

    @Test
    void crossFamilyPairsAreRefusedAndConfirmHasItsOwnClass() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("NUMBER + NUMBER — control", verdict("Weight",
                "FirstFilledValue(Weight, Height) > 0"));
        actual.put("STRING + NUMBER", verdict("Title", "FirstFilledValue(Title, Weight) == \"x\""));
        actual.put("DATE + STRING", verdict("ListedOn", "FirstFilledValue(ListedOn, Title) == Today"));
        actual.put("CONFIRM + STRING", verdict("Title", "FirstFilledValue(Title, InStock) == \"x\""));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("NUMBER + NUMBER — control", "admitted");
        expected.put("STRING + NUMBER", "MVK_VARYING_TYPES_NOT_ALLOWED");
        expected.put("DATE + STRING", "MVK_VARYING_TYPES_NOT_ALLOWED");
        expected.put("CONFIRM + STRING", "MVK_NO_BOOLY_ALLOWED");
        assertEquals(expected, actual,
                "a Confirm operand draws its own class, so the varying-types code is not a catch-all");
    }

    /** The kernel's model-level refusal of a declared temporal format, which carries no {@code MVK_} code. */
    private static final String INVALID_FORMAT = "The date format is invalid.";

    /**
     * Whether a MODEL stands at all, measured with a trivially valid rule so the verdict is about the field's
     * declared format and nothing else. A format refusal fires here, before any operand list is considered.
     */
    private static String modelVerdict(Consumer<DocumentModelDraft.GroupBuilder> field) {
        String model = DocumentModelDraft.named("probe").locale("en_US")
                .rootGroup("Probe", g -> {
                    field.accept(g);
                    g.field("Marker", FieldKind.STRING);
                })
                .build().toJson();
        ValidationResult r = KernelLaws.checkModel(model, "/Probe", "../Marker", "[Marker] == \"x\"");
        return r.ok() ? "model accepted"
                : r.diagnostics().get(0).summary().replaceFirst("^Field with path '[^']*': ", "");
    }

    /**
     * The list is not a pair, and the gate is not positional: the same declared-format rule is applied to every
     * slot, so a cross planted in the LAST of three is refused exactly as one in the second is
     * (SPEC-2026-08-19-04). The yearless cross is measured here too, because a fragment pair is the shape the
     * pairwise rows never reached.
     */
    @Test
    void aThreeSourceRangeListKeysOnEverySlotNotJustTheFirstPair() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("three sources, one (format, separator) — control", verdict("PromoWindow",
                "FirstFilledValue(PromoWindow, SaleWindow, RenewalWindow) == [PromoWindow]"));
        actual.put("cross planted in the LAST of three", verdict("PromoWindow",
                "FirstFilledValue(PromoWindow, SaleWindow, AuditWindow) == [PromoWindow]"));
        actual.put("yyyy + yyyy-MM — two fragment profiles", verdict("SeasonYears",
                "FirstFilledValue(SeasonYears, SeasonPeriods) == [SeasonYears]"));
        actual.put("yyyy + yyyy — control", verdict("SeasonYears",
                "FirstFilledValue(SeasonYears, PriorSeasonYears) == [SeasonYears]"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("three sources, one (format, separator) — control", "admitted");
        expected.put("cross planted in the LAST of three", "MVK_VARYING_TYPES_NOT_ALLOWED");
        expected.put("yyyy + yyyy-MM — two fragment profiles", "MVK_VARYING_TYPES_NOT_ALLOWED");
        expected.put("yyyy + yyyy — control", "admitted");
        assertEquals(expected, actual,
                "the entity-list format gate reads every slot, and it reads fragment profiles the same way");
    }

    /** {@code admitted}, or the COMPLETE emitted code list — a partial list would hide a co-firing gate. */
    private static String verdict(String errorField, String condition) {
        ValidationResult r = KernelLaws.checkModel(MODEL, "/Item", "../" + errorField, condition);
        return r.ok() ? "admitted" : String.join(" + ", r.diagnostics().stream()
                .map(d -> d.code().orElse("(no code: " + d.summary() + ")")).map(Object::toString).toList());
    }
}

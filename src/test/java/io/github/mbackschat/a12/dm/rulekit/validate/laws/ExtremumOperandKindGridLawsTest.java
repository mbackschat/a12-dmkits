package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * KERNEL-FINDINGS §9 — <b>the extrema admit two sortable classes, NUMBER and the temporal COMPONENT SET, and
 * require every operand to share the leader's class</b>. [KF249] measured the position law on the NUMBER/DATE
 * pair; this completes the declared-kind axis for static admission and identifies what the temporal half is
 * actually keyed on.
 *
 * <p><b>The declared KIND is not the axis — the component set is.</b> DATE and DATE_FRAGMENT mix freely when
 * both carry year+month+day, and two DATE_FRAGMENT fields are refused when one carries year+month and the
 * other year alone. Two DATE fields with different format STRINGS over the same components are admitted, so
 * {@code MVK_DATEFORMATS_NOT_COMPATIBLE} is misnamed: it reports an incompatible component set, not an unequal
 * format string. Same mechanism as the format-not-kind law at the runtime parser ([KF211], [KF250]); this is
 * one more carrier for it.
 *
 * <p><b>The leader slot selects which GATE runs, which is why the two refusal codes differ.</b> A NUMBER
 * leader refuses every other class flatly as {@code MVK_NOT_SORTABLE}; a temporal leader distinguishes, giving
 * {@code MVK_DATE_AND_NONDATE} for a non-temporal follower and {@code MVK_DATEFORMATS_NOT_COMPATIBLE} for a
 * temporal one whose components differ. ENUM, BOOLEAN, CONFIRM, DATE_RANGE, CUSTOM and STRING never lead.
 *
 * <p><b>Each row's comparand is matched to its leader's class</b>, because the extremum's result feeds a
 * comparison whose own gate would otherwise fire first: under a bare {@code == 0} every temporal diagonal
 * reports {@code MVK_INVALID_COMPARE_TO_DATE} and the operand gate is never reached. That confound is what
 * produced an earlier reading of this grid, which recorded the leader set as NUMBER and DATE alone.
 *
 * <p><b>A starred single-field operand takes the same verdict and the same code as the plain field of that
 * kind</b>, in both positions — so the gate reads the declaration and not the operand form.
 *
 * <p><b>CUSTOM is the cell that separates the extrema's kind domain from the distinct count's</b>, and it is
 * the only kind that does: refused at both extrema in either position — {@code MVK_NOT_SORTABLE} when it leads
 * or follows a NUMBER, {@code MVK_DATE_AND_NONDATE} after a temporal leader — yet admitted by
 * {@code NumberOfDifferentValues} and {@code FieldValuesNotUnique}, where DATE_RANGE is refused by all four
 * under two DIFFERENT codes. A consumer deriving either domain from the other is wrong in exactly that cell.
 *
 * <p><b>A GROUP operand's expansion supplies the leading kind, and the discriminator is a reorder.</b> Three
 * groups carry the same three kinds in three declaration orders; the reported class follows the FIRST declared
 * member at both families, which a "a group is simply not sortable" reading cannot produce. That is the
 * question a12-kernel-lean left open on {@code SPEC-2026-09-06-06}.
 *
 * <p>Own domain (an e-commerce product catalog). The CUSTOM declaration carries an unregistered type name,
 * which the static model gate accepts.
 */
class ExtremumOperandKindGridLawsTest {

    private static final String GROUP = "/Catalog";
    private static final String ERROR_REL = "../Sku";

    private static final String NOT_SORTABLE = "MVK_NOT_SORTABLE";
    private static final String DATE_LEADER = "MVK_DATE_AND_NONDATE";
    private static final String COMPONENTS = "MVK_DATEFORMATS_NOT_COMPATIBLE";
    private static final String STRING_ENUM_LEADER = "MVK_STRING_ENUM_AND_NON_STRING_ENUM";
    private static final String NUMBER_LEADER = "MVK_NUMBER_AND_NON_NUMBER";
    private static final String DISTINCT_COUNT_DOMAIN = "MVK_ONLY_STRING_ENUM_NUMBER_CMP_DATE_ALLOWED";
    private static final String UNIQUENESS_DOMAIN = "MVK_ONLY_STRING_ENUM_NUMBER_DATE_ALLOWED";

    private static final String CATALOG = DocumentModelDraft.named("product-line").locale("en_US")
            .rootGroup("Catalog", c -> c
                    .stringField("Sku", s -> { })
                    .stringField("Label", s -> { })
                    .numberField("Weight", n -> n.fractionDigits(0))
                    .numberField("Weight2", n -> n.fractionDigits(0))
                    .dateField("ReleasedOn", d -> d.format("yyyy-MM-dd"))
                    .dateField("RetiredOn", d -> d.format("yyyy-MM-dd"))
                    // same KIND and same components as ReleasedOn, different format STRING
                    .dateField("AltDate", d -> d.format("dd.MM.yyyy"))
                    .timeField("ShiftStart", t -> t.format("HH:mm:ss"))
                    .timeField("ShiftEnd", t -> t.format("HH:mm:ss"))
                    .dateTimeField("SealedAt", t -> t.format("yyyy-MM-dd'T'HH:mm:ss"))
                    .dateTimeField("OpenedAt", t -> t.format("yyyy-MM-dd'T'HH:mm:ss"))
                    .dateFragmentField("SeasonA", f -> f.fragmentFormat("yyyy-MM"))
                    .dateFragmentField("SeasonB", f -> f.fragmentFormat("yyyy-MM"))
                    // same KIND as SeasonA, one component fewer
                    .dateFragmentField("SeasonYear", f -> f.fragmentFormat("yyyy"))
                    // a different KIND carrying exactly DATE's components, and exactly TIME's
                    .dateFragmentField("FragFull", f -> f.fragmentFormat("yyyy-MM-dd"))
                    .dateFragmentField("FragClock", f -> f.fragmentFormat("HH:mm:ss"))
                    .enumField("Grade", e -> e.value("A").value("B"))
                    .field("Discontinued", FieldKind.BOOLEAN)
                    .field("Approved", FieldKind.CONFIRM)
                    .dateRangeField("SaleWindow", r -> r.format("dd.MM.yyyy").separator("-"))
                    .dateRangeField("SaleWindow2", r -> r.format("dd.MM.yyyy").separator("-"))
                    .customField("Serial", t -> t.type("WarehouseSerial"))
                    .customField("Serial2", t -> t.type("WarehouseSerial"))
                    // one repeatable group per DECLARATION ORDER — the reorder is the discriminator
                    .group("Lines", g -> g.repeatable(20)
                            .enumField("LineGrade", e -> e.value("A").value("B"))
                            .numberField("LineQty", n -> n.fractionDigits(0))
                            .dateField("LineDate", d -> d.format("yyyy-MM-dd")))
                    .group("Bins", g -> g.repeatable(20)
                            .numberField("BinQty", n -> n.fractionDigits(0))
                            .enumField("BinGrade", e -> e.value("A").value("B"))
                            .dateField("BinDate", d -> d.format("yyyy-MM-dd")))
                    .group("Slots", g -> g.repeatable(20)
                            .dateField("SlotDate", d -> d.format("yyyy-MM-dd"))
                            .numberField("SlotQty", n -> n.fractionDigits(0)))
                    .group("Cells", g -> g.repeatable(20)
                            .numberField("CellQty", n -> n.fractionDigits(0))
                            .numberField("CellQty2", n -> n.fractionDigits(0))))
            .build().toJson();

    private static String verdict(String condition) {
        ValidationResult r = KernelLaws.checkModel(CATALOG, GROUP, ERROR_REL, "FieldFilled(Sku) And " + condition);
        return r.ok() ? KernelLaws.ADMITTED : r.diagnostics().stream()
                .map(d -> d.code().orElse("(no code: " + d.summary() + ")"))
                .collect(Collectors.joining(","));
    }

    /** A declared kind, two fields of that exact class, and a comparand the class accepts. */
    private record Kind(String label, String first, String second, String comparand) { }

    private static Kind kind(String label, String first, String second) {
        return new Kind(label, first, second, "[" + first + "]");
    }

    private static final Kind NUMBER = new Kind("NUMBER", "Weight", "Weight2", "0");
    private static final Kind DATE = kind("DATE", "ReleasedOn", "RetiredOn");
    private static final Kind TIME = kind("TIME", "ShiftStart", "ShiftEnd");
    private static final Kind DATETIME = kind("DATETIME", "SealedAt", "OpenedAt");
    private static final Kind FRAGMENT = kind("DATE_FRAGMENT", "SeasonA", "SeasonB");

    private static final List<Kind> LEADERS = List.of(NUMBER, DATE, TIME, DATETIME, FRAGMENT);

    private static final List<Kind> NON_SORTABLE = List.of(
            kind("ENUM", "Grade", "Grade"),
            kind("BOOLEAN", "Discontinued", "Discontinued"),
            kind("CONFIRM", "Approved", "Approved"),
            kind("DATE_RANGE", "SaleWindow", "SaleWindow2"),
            kind("CUSTOM", "Serial", "Serial2"),
            kind("STRING", "Label", "Label"));

    /**
     * The declared-kind axis, complete for static admission, at both extrema. Every operand must share the
     * leader's class, and the four temporal classes are distinct from one another, so the diagonal is the only
     * admitted cell in the temporal block.
     */
    @Test
    void theSortableClassesAreNumberAndTheTemporalComponentSetAndTheLeaderFixesTheClass() {
        Map<String, String> actual = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();
        for (String op : List.of("MinValue", "MaxValue")) {
            for (Kind leader : LEADERS) {
                for (Kind follower : LEADERS) {
                    String cell = op + ": " + leader.label() + " then " + follower.label();
                    String second = leader == follower ? leader.second() : follower.first();
                    actual.put(cell,
                            verdict(op + "(" + leader.first() + ", " + second + ") == " + leader.comparand()));
                    expected.put(cell, leader == follower ? KernelLaws.ADMITTED
                            : leader == NUMBER || follower == NUMBER ? mismatch(leader) : COMPONENTS);
                }
                for (Kind follower : NON_SORTABLE) {
                    String cell = op + ": " + leader.label() + " then " + follower.label();
                    actual.put(cell, verdict(
                            op + "(" + leader.first() + ", " + follower.first() + ") == " + leader.comparand()));
                    expected.put(cell, mismatch(leader));
                }
            }
            for (Kind leader : NON_SORTABLE) {
                String cell = op + ": " + leader.label() + " leads";
                actual.put(cell, verdict(op + "(" + leader.first() + ", Weight) == " + leader.comparand()));
                expected.put(cell, NOT_SORTABLE);
            }
        }
        assertEquals(expected, actual,
                "NUMBER and each temporal component set are the sortable classes; the leader fixes the class");
    }

    /** A NUMBER leader reports a class mismatch flatly; a temporal leader names the non-temporal follower. */
    private static String mismatch(Kind leader) {
        return leader == NUMBER ? NOT_SORTABLE : DATE_LEADER;
    }

    /**
     * The within-model 2×2 that identifies the temporal axis: declared KIND against COMPONENT SET, one model,
     * one operator family, one comparand shape. Only the component set moves the verdict — the same components
     * under two different kinds are admitted, and the same kind under two different component sets is refused.
     *
     * <p>The "same components, different kind" cell is what makes this a control rather than a pair of
     * observations: without it, every refused cell is equally well explained by the declared kind, which is the
     * reading a consumer reaches for first.
     */
    @Test
    void theTemporalGateReadsTheComponentSetAndNotTheDeclaredKind() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("same kind, same components, DIFFERENT format string",
                verdict("MinValue(ReleasedOn, AltDate) == [ReleasedOn]"));
        actual.put("same kind, DIFFERENT components", verdict("MinValue(SeasonA, SeasonYear) == [SeasonA]"));
        actual.put("DIFFERENT kind, same components (date)",
                verdict("MinValue(ReleasedOn, FragFull) == [ReleasedOn]"));
        actual.put("DIFFERENT kind, same components (clock)",
                verdict("MinValue(ShiftStart, FragClock) == [ShiftStart]"));
        actual.put("DIFFERENT kind, DIFFERENT components",
                verdict("MinValue(ReleasedOn, ShiftStart) == [ReleasedOn]"));
        actual.put("MaxValue: DIFFERENT kind, same components",
                verdict("MaxValue(ReleasedOn, FragFull) == [ReleasedOn]"));
        actual.put("MaxValue: same kind, DIFFERENT components",
                verdict("MaxValue(SeasonA, SeasonYear) == [SeasonA]"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("same kind, same components, DIFFERENT format string", KernelLaws.ADMITTED);
        expected.put("same kind, DIFFERENT components", COMPONENTS);
        expected.put("DIFFERENT kind, same components (date)", KernelLaws.ADMITTED);
        expected.put("DIFFERENT kind, same components (clock)", KernelLaws.ADMITTED);
        expected.put("DIFFERENT kind, DIFFERENT components", COMPONENTS);
        expected.put("MaxValue: DIFFERENT kind, same components", KernelLaws.ADMITTED);
        expected.put("MaxValue: same kind, DIFFERENT components", COMPONENTS);
        assertEquals(expected, actual, "only the component set moves the verdict; the declared kind never does");
    }

    /**
     * The comparand cannot be what refuses a non-sortable leader — the same pair takes
     * {@code MVK_NOT_SORTABLE} under a NUMBER comparand and under a self-referencing one. This is the control
     * for the confound that produced the earlier reading of this grid.
     */
    @Test
    void aNonSortableLeaderIsRefusedIndependentlyOfTheComparand() {
        Map<String, String> actual = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();
        for (Kind leader : NON_SORTABLE) {
            String pair = "MinValue(" + leader.first() + ", Weight)";
            actual.put(leader.label() + " with a NUMBER comparand", verdict(pair + " == 0"));
            actual.put(leader.label() + " with a self comparand", verdict(pair + " == " + leader.comparand()));
            expected.put(leader.label() + " with a NUMBER comparand", NOT_SORTABLE);
            expected.put(leader.label() + " with a self comparand", NOT_SORTABLE);
        }
        assertEquals(expected, actual, "the operand gate decides, whatever the extremum is compared against");
    }

    /** A star is not a distinct gate — the declaration is what is read. */
    @Test
    void aStarredSingleFieldTakesThePlainFieldsVerdictInBothPositions() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("starred ENUM leading", verdict("MinValue(Lines*/LineGrade, Weight) == 0"));
        actual.put("starred ENUM after NUMBER", verdict("MinValue(Weight, Lines*/LineGrade) == 0"));
        actual.put("starred NUMBER leading", verdict("MinValue(Lines*/LineQty, Weight) == 0"));
        actual.put("starred DATE leading", verdict("MinValue(Lines*/LineDate, ReleasedOn) == [ReleasedOn]"));
        actual.put("starred DATE after NUMBER", verdict("MinValue(Weight, Lines*/LineDate) == 0"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("starred ENUM leading", NOT_SORTABLE);
        expected.put("starred ENUM after NUMBER", NOT_SORTABLE);
        expected.put("starred NUMBER leading", KernelLaws.ADMITTED);
        expected.put("starred DATE leading", KernelLaws.ADMITTED);
        expected.put("starred DATE after NUMBER", NOT_SORTABLE);
        assertEquals(expected, actual, "the starred operand takes the plain field's verdict and code");
    }

    /** CUSTOM separates the two domains; DATE_RANGE is refused by both distinct-count members, under two codes. */
    @Test
    void theExtremaKindDomainIsNotTheDistinctCountsAndCustomIsWhereTheyPartWays() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("NumberOfDifferentValues over DATE_RANGE",
                verdict("NumberOfDifferentValues(SaleWindow, SaleWindow2) == 1"));
        actual.put("FieldValuesNotUnique over DATE_RANGE", verdict("FieldValuesNotUnique(SaleWindow, SaleWindow2)"));
        actual.put("NumberOfDifferentValues over CUSTOM", verdict("NumberOfDifferentValues(Serial, Serial2) == 1"));
        actual.put("FieldValuesNotUnique over CUSTOM", verdict("FieldValuesNotUnique(Serial, Serial2)"));
        actual.put("MinValue leading CUSTOM", verdict("MinValue(Serial, Weight) == 0"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("NumberOfDifferentValues over DATE_RANGE", DISTINCT_COUNT_DOMAIN);
        expected.put("FieldValuesNotUnique over DATE_RANGE", UNIQUENESS_DOMAIN);
        expected.put("NumberOfDifferentValues over CUSTOM", KernelLaws.ADMITTED);
        expected.put("FieldValuesNotUnique over CUSTOM", KernelLaws.ADMITTED);
        expected.put("MinValue leading CUSTOM", NOT_SORTABLE);
        assertEquals(expected, actual, "the two gates hold genuinely different kind domains");
    }

    /**
     * The reorder discriminator: the same three kinds in three declaration orders. A rival reading — "a group
     * expansion is simply not a sortable/homogeneous list" — predicts one constant class per family; the
     * measured class tracks the FIRST declared member.
     */
    @Test
    void aGroupExpansionSuppliesTheLeadingKindAtBothFamilies() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("MinValue over an ENUM-first expansion", verdict("MinValue(Lines*) == 0"));
        actual.put("MinValue over a NUMBER-first expansion", verdict("MinValue(Bins*) == 0"));
        actual.put("MinValue over a DATE-first expansion", verdict("MinValue(Slots*) == 0"));
        actual.put("MinValue over an all-NUMBER expansion", verdict("MinValue(Cells*) == 0"));
        actual.put("NumberOfDifferentValues over an ENUM-first expansion",
                verdict("NumberOfDifferentValues(Lines*) == 1"));
        actual.put("NumberOfDifferentValues over a NUMBER-first expansion",
                verdict("NumberOfDifferentValues(Bins*) == 1"));
        actual.put("NumberOfDifferentValues over a DATE-first expansion",
                verdict("NumberOfDifferentValues(Slots*) == 1"));
        actual.put("NumberOfDifferentValues over an all-NUMBER expansion",
                verdict("NumberOfDifferentValues(Cells*) == 1"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("MinValue over an ENUM-first expansion", NOT_SORTABLE);
        expected.put("MinValue over a NUMBER-first expansion", NOT_SORTABLE);
        expected.put("MinValue over a DATE-first expansion", DATE_LEADER);
        expected.put("MinValue over an all-NUMBER expansion", KernelLaws.ADMITTED);
        expected.put("NumberOfDifferentValues over an ENUM-first expansion", STRING_ENUM_LEADER);
        expected.put("NumberOfDifferentValues over a NUMBER-first expansion", NUMBER_LEADER);
        expected.put("NumberOfDifferentValues over a DATE-first expansion", DATE_LEADER);
        expected.put("NumberOfDifferentValues over an all-NUMBER expansion", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "the expansion's FIRST declared member fixes the class");
    }
}

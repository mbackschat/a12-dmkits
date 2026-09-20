package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * The typed peer of {@code validate.laws.TemporalAggregateComponentSetLawsTest}: the entity-list aggregates
 * admit temporal operands whose declared COMPONENT SETS are equal, after YEAR is added to every set when the
 * model declares a base year ([KF193]).
 *
 * <p>This gate was {@code kernel-delegated} on a recorded reason — a pre-check would have had to compare
 * format STRINGS, which refuses the equal-set/different-spelling pairs the kernel admits. Comparing sets is
 * what makes it statable, so the rows that carry this file are those same pairs: {@code yyyy-MM-dd} beside
 * {@code dd.MM.yyyy}, and {@code yyyy-MM} beside {@code yyyyMM}. Without them a format-string gate and a
 * component-set gate would be indistinguishable here.
 *
 * <p>Two operand shapes stay DELEGATED, and neither is an oversight. A <b>group scope</b> carries no format of
 * its own — the rule applies to its expansion, whose extent {@code Dsl} already declines to re-derive because
 * an over-strict copy would refuse a legal model. An <b>indexed</b> operand's format is plausibly the selected
 * field's and is unmeasured (DEV-API-GAPS A9's subject).
 */
class AggregateComponentSetGateTest {

    private static BoundModel model(Integer baseYear) {
        DocumentModelDraft.Builder b = DocumentModelDraft.named("aggregate-components").locale("en_US");
        if (baseYear != null) {
            b.baseYear(baseYear);
        }
        return BoundModel.ofJson(b
                .rootGroup("Doc", d -> d
                        .field("Error", FieldKind.STRING)
                        .dateField("IsoDate", f -> f.format("yyyy-MM-dd"))
                        .dateField("GermanDate", f -> f.format("dd.MM.yyyy"))
                        .dateField("IsoMonth", f -> f.format("yyyy-MM"))
                        .dateField("CompactMonth", f -> f.format("yyyyMM"))
                        .dateField("MonthOnly", f -> f.format("MM"))
                        .dateField("MonthDay", f -> f.format("MM-dd"))
                        .dateField("YearOnly", f -> f.format("yyyy"))
                        .dateField("SameIsoDate", f -> f.format("yyyy-MM-dd"))
                        .group("Rows", r -> r.repeatable(9).indexField("Key")
                                .field("Key", FieldKind.STRING)
                                .dateField("RowDate", f -> f.format("yyyy-MM-dd"))))
                .build().toJson());
    }

    private static final BoundModel NO_BASE_YEAR = model(null);
    private static final BoundModel WITH_BASE_YEAR = model(2000);

    private static boolean refuses(BoundModel m, String a, String b) {
        try {
            m.latestOf(m.field("/Doc/" + a), m.field("/Doc/" + b));
            return false;
        } catch (IllegalArgumentException refusal) {
            assertTrue(refusal.getMessage().contains("MVK_DATEFORMATS_NOT_COMPATIBLE"), refusal::getMessage);
            return true;
        }
    }

    /** Equal sets are admitted however they are spelled; a different set is refused. */
    @Test
    void theGateComparesComponentSetsAndNotFormatStrings() {
        Map<String, Boolean> expected = new LinkedHashMap<>();
        Map<String, Boolean> actual = new LinkedHashMap<>();
        record Row(String a, String b, boolean refused) { }
        for (Row row : new Row[] {
                new Row("IsoDate", "GermanDate", false),      // {y,M,d} both, different spelling
                new Row("IsoMonth", "CompactMonth", false),   // {y,M} both, different spelling
                new Row("IsoDate", "IsoMonth", true),         // {y,M,d} vs {y,M}
                new Row("MonthOnly", "IsoMonth", true),       // {M} vs {y,M}, no base year to supplement
                new Row("YearOnly", "IsoMonth", true),        // {y} vs {y,M}
                new Row("IsoDate", "SameIsoDate", false),     // two fields, identical declaration
        }) {
            String key = row.a() + " vs " + row.b();
            expected.put(key, row.refused());
            actual.put(key, refuses(NO_BASE_YEAR, row.a(), row.b()));
        }
        assertEquals(expected, actual);
    }

    /**
     * A declared base year adds YEAR to EVERY set before comparing, so a yearless operand is admitted exactly
     * when the rest already agrees — and the year-only rows are the control showing it supplements the gate
     * rather than disabling it.
     */
    @Test
    void aDeclaredBaseYearSupplementsEverySetBeforeComparing() {
        Map<String, Boolean> expected = new LinkedHashMap<>();
        Map<String, Boolean> actual = new LinkedHashMap<>();
        record Row(String a, String b, boolean refused) { }
        for (Row row : new Row[] {
                new Row("MonthOnly", "IsoMonth", false),      // {M}+y == {y,M}
                new Row("MonthDay", "IsoDate", false),        // {M,d}+y == {y,M,d}
                new Row("MonthOnly", "YearOnly", true),       // {y,M} vs {y} — still refused
                new Row("MonthDay", "IsoMonth", true),        // {y,M,d} vs {y,M} — still refused
        }) {
            String key = row.a() + " vs " + row.b();
            expected.put(key, row.refused());
            actual.put(key, refuses(WITH_BASE_YEAR, row.a(), row.b()));
        }
        assertEquals(expected, actual);
    }

    /** The same pair flips with the declaration, which is what shows the gate reads it. */
    @Test
    void theBaseYearDeclarationIsWhatMovesTheVerdict() {
        assertTrue(refuses(NO_BASE_YEAR, "MonthOnly", "IsoMonth"));
        assertTrue(!refuses(WITH_BASE_YEAR, "MonthOnly", "IsoMonth"));
    }

    /** Every carrier the kernel gates under this code states it, not just the one the rows above use. */
    @Test
    void allThreeCarriersStateTheGate() {
        FieldRef iso = NO_BASE_YEAR.field("/Doc/IsoDate");
        FieldRef month = NO_BASE_YEAR.field("/Doc/IsoMonth");
        assertThrows(IllegalArgumentException.class, () -> NO_BASE_YEAR.latestOf(iso, month));
        assertThrows(IllegalArgumentException.class, () -> NO_BASE_YEAR.earliestOf(iso, month));
        assertThrows(IllegalArgumentException.class,
                () -> NO_BASE_YEAR.numberOfDifferentValues(iso, month));
    }

    /** An index-selected operand is gated by the selected field's declaration, like any direct reference. */
    @Test
    void anIndexSelectedOperandIsGated() {
        FieldRef rowDate = NO_BASE_YEAR.field("/Doc/Rows/RowDate");
        assertThrows(IllegalArgumentException.class, () -> NO_BASE_YEAR.latestOf(new SemanticIndexValue(rowDate, "K1"), NO_BASE_YEAR.field("/Doc/IsoMonth")));
        assertDoesNotThrow(() -> NO_BASE_YEAR.latestOf(new SemanticIndexValue(rowDate, "K1"), NO_BASE_YEAR.field("/Doc/GermanDate")));
    }

    /**
     * A group scope is delegated: the rule applies to its EXPANSION, and re-deriving that extent here is what
     * {@code Dsl} already declines to do for the same aggregates' base-kind rule.
     */
    @Test
    void aGroupScopeOperandIsDelegated() {
        assertDoesNotThrow(() -> NO_BASE_YEAR.latestOf(NO_BASE_YEAR.field("/Doc/IsoMonth"), NO_BASE_YEAR.flattenGroup("/Doc/Rows*")));
    }

/**
     * Three existing checks run BEFORE this gate and it must not restate any of them: the arity check, the
     * kind check the kind-named factory carries, and the duplicate-operand check. Their messages are asserted
     * so a later edit cannot quietly move the format gate ahead of one and change which code a caller sees.
     */
    @Test
    void theGateSitsBehindTheChecksThatAlreadyGuardTheseCarriers() {
        FieldRef month = NO_BASE_YEAR.field("/Doc/IsoMonth");
        FieldRef iso = NO_BASE_YEAR.field("/Doc/IsoDate");

        assertTrue(assertThrows(IllegalArgumentException.class, () -> NO_BASE_YEAR.latestOf(month))
                .getMessage().contains("MVK_PARAMSIZE_INVALIDN"), "one operand is an arity refusal");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> NO_BASE_YEAR.latestOf(iso, NO_BASE_YEAR.field("/Doc/Error")))
                .getMessage().contains("is STRING"), "a non-temporal operand is the kind refusal");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> NO_BASE_YEAR.latestOf(iso, iso))
                .getMessage().contains("MVK_DUPLICATE_PARAM1"), "the same operand twice is the duplicate refusal");
    }

    /** A starred repetition list carries the declared format of the field it repeats. */
    @Test
    void aStarredListIsGatedByItsFieldsDeclaration() {
        assertThrows(IllegalArgumentException.class, () -> NO_BASE_YEAR.latestOf(NO_BASE_YEAR.flatten("/Doc/Rows*/RowDate"), NO_BASE_YEAR.field("/Doc/IsoMonth")));
        assertDoesNotThrow(() -> NO_BASE_YEAR.latestOf(NO_BASE_YEAR.flatten("/Doc/Rows*/RowDate"), NO_BASE_YEAR.field("/Doc/GermanDate")));
    }

}

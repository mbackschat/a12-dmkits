package io.github.mbackschat.a12.dm.adapter;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * F10 read of a <b>date-fragment field comparison</b>. The kernel collapses a date fragment into a plain
 * {@code Date} for parsing/checking/rendering/comparison (KERNEL-FINDINGS §6): it renders as a bare
 * {@code [field]}, all six operators are legal, and the right operand may be a date field, another
 * fragment, {@code Date(...)}, {@code Today}/{@code Now}, {@code Base Year}, or a date expression — the
 * only extra rule is a check-time year-class partition (both sides carry a year or neither). So a
 * fragment is a first-class {@link io.github.mbackschat.a12.dm.rulekit.dsl.DateOperand}, and read must
 * convert it via the same date-comparison machinery.
 *
 * <p>Own domain: a warranty whose coverage start is a year-month fragment and whose coverage span is a
 * year-only fragment, compared against the full purchase date. Previously the converter left a fragment
 * comparison Opaque ("does not yet handle comparison on a DATE_FRAGMENT field") and crashed when a
 * fragment appeared as the right operand ("is not a date field (kind=DATE_FRAGMENT)").
 */
class DateFragmentComparisonReadTest {

    private static final DslRenderer RENDER = new DslRenderer(ConditionLanguage.EN);

    private static String warrantyJson() {
        return DocumentModelDraft.named("warranty").locale("en_US")
                .rootGroup("Warranty", w -> w
                        .dateFragmentField("StartYearMonth", f -> f.fragmentFormat("yyyy-MM"))
                        .dateFragmentField("CoverageYear", f -> f.fragmentFormat("yyyy"))
                        .dateField("PurchaseDate", d -> d.format("yyyy-MM-dd")))
                .build().toJson();
    }

    @Test
    void dateFragmentComparisonsAreKernelValid() {
        String model = warrantyJson();
        var cases = new String[] {
            "FieldFilled(StartYearMonth) And [StartYearMonth] < [PurchaseDate]",   // fragment vs date field
            "FieldFilled(StartYearMonth) And [PurchaseDate] > [StartYearMonth]",   // date field vs fragment (RHS)
            "FieldFilled(StartYearMonth) And [StartYearMonth] == [CoverageYear]",  // fragment vs fragment (both year)
        };
        for (String dsl : cases) {
            ValidationResult r = new RuleValidator().check(model,
                    new DraftRule("/Warranty", "R", "../StartYearMonth", dsl, "C", "ERROR"));
            assertTrue(r.ok(), () -> "expected kernel acceptance for: " + dsl + " — got: " + r.diagnostics());
        }
    }

    @Test
    void roundTripsDateFragmentComparisons() {
        BoundModel m = BoundModel.ofJson(warrantyJson());
        Condition fragLtDate = m.dateFragment("/Warranty/StartYearMonth").filled()
                .and(m.dateFragment("/Warranty/StartYearMonth").before(m.date("/Warranty/PurchaseDate")));
        Condition dateGtFrag = m.dateFragment("/Warranty/StartYearMonth").filled()
                .and(m.date("/Warranty/PurchaseDate").after(m.dateFragment("/Warranty/StartYearMonth")));
        Condition fragEqFrag = m.dateFragment("/Warranty/StartYearMonth").filled()
                .and(m.dateFragment("/Warranty/StartYearMonth").eq(m.dateFragment("/Warranty/CoverageYear")));

        KernelAdapter adapter = KernelAdapter.of(m
                .add(rule(m, "FragLtDate", "/Warranty/StartYearMonth", fragLtDate))
                .add(rule(m, "DateGtFrag", "/Warranty/StartYearMonth", dateGtFrag))
                .add(rule(m, "FragEqFrag", "/Warranty/StartYearMonth", fragEqFrag)));

        assertEquals(RENDER.render(fragLtDate), RENDER.render(converted(adapter.read("/Warranty/FragLtDate"))));
        assertEquals(RENDER.render(dateGtFrag), RENDER.render(converted(adapter.read("/Warranty/DateGtFrag"))));
        assertEquals(RENDER.render(fragEqFrag), RENDER.render(converted(adapter.read("/Warranty/FragEqFrag"))));
    }

    private static Rule rule(BoundModel m, String name, String errorField, Condition cond) {
        return m.rule(m.group("/Warranty")).name(name).errorField(m.field(errorField)).when(cond)
                .code("C").severity(Severity.ERROR).message(en("x")).build();
    }

    private static Condition converted(ReadResult result) {
        return assertInstanceOf(ReadResult.Converted.class, result,
                () -> "expected a converted read, got: " + result).condition();
    }
}

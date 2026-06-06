package io.github.mbackschat.a12.dm.adapter;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * The N8 backstop for {@code $} outer-iteration correlation ingest (D13). Number and string
 * correlations are threaded (see {@code OuterCorrelationIngestTest}), but a kind whose handle does
 * <em>not</em> yet expose {@code outer()} — here a <b>date</b> comparison inside the filter — would,
 * if converted naively, drop the {@code $} and become a different rule. The converter's date
 * comparison <em>does</em> convert (so this is not caught by the unsupported-construct path); the
 * {@code verifyOuterPreserved} count-check is what catches the dropped marker and degrades the rule to
 * {@link Ingest.Opaque}, preserving it verbatim. This is the guarantee that lets number/string be
 * threaded incrementally without risking silent corruption of the not-yet-threaded kinds.
 */
class OuterCorrelationBackstopTest {

    // A real, kernel-valid date $ correlation: keep only the shifts whose date equals the (outer) period
    // end, then take the latest — compared back to the period end. The date comparison in the filter is
    // the un-threaded kind.
    private static final String DATE_OUTER_CONDITION =
            "FieldFilled(PeriodEnd) And [/Timesheet/PeriodEnd] != "
                    + "MaxValue(Shifts*/ShiftDate Having [$/Timesheet/PeriodEnd] == [Shifts/ShiftDate])";

    private static String timesheetJson() {
        return DocumentModelDraft.named("timesheet").locale("en_US")
                .rootGroup("Timesheet", t -> t
                        .dateField("PeriodEnd", d -> d.format("yyyy-MM-dd"))
                        .group("Shifts", g -> g
                                .repeatable(50)
                                .dateField("ShiftDate", d -> d.format("yyyy-MM-dd"))))
                .build().toJson();
    }

    @Test
    void theDateCorrelationIsAGenuinelyKernelValidRule() {
        // Establishes the premise: this is a valid rule the converter *chooses* to keep Opaque (a kind it
        // doesn't thread yet), not an invalid rule the kernel would reject anyway.
        ValidationResult r = new RuleValidator().check(timesheetJson(),
                new DraftRule("/Timesheet", "DateOuter", "../PeriodEnd", DATE_OUTER_CONDITION, "C", "ERROR"));
        assertTrue(r.ok(), () -> "the date $ correlation must be kernel-valid, got: " + r.diagnostics());
    }

    @Test
    void unthreadedDateCorrelationStaysOpaqueAndPreservesTheDollarVerbatim() {
        Ingest result = KernelAdapter.ofJson(timesheetWith(DATE_OUTER_CONDITION)).ingest("/Timesheet/DateOuter");

        Ingest.Opaque opaque = assertInstanceOf(Ingest.Opaque.class, result,
                () -> "an un-threaded $ kind must degrade to Opaque (N8), not silently convert, got: " + result);
        assertTrue(opaque.canonicalText().contains("$"),
                () -> "the $ marker must be preserved verbatim: " + opaque.canonicalText());
        assertTrue(opaque.canonicalText().contains("Having"),
                () -> "the whole correlation must be preserved, not a truncated form: " + opaque.canonicalText());
    }

    private static String timesheetWith(String rawCondition) {
        String base = timesheetJson();
        String escaped = rawCondition.replace("\\", "\\\\").replace("\"", "\\\"");
        String rule = "{ \"type\": \"Rule\", \"id\": \"/Timesheet/DateOuter\", \"name\": \"DateOuter\","
                + " \"Rule\": { \"errorEntityRelPath\": \"../PeriodEnd\", \"errorCode\": \"DO\","
                + " \"errorCondition\": \"" + escaped + "\", \"severity\": \"ERROR\","
                + " \"errorMessage\": [ { \"locale\": \"en_US\", \"text\": \"x\" } ] } },\n";
        int idx = base.indexOf("\"elements\"");
        int br = base.indexOf('[', idx);
        return base.substring(0, br + 1) + "\n" + rule + base.substring(br + 1);
    }
}

package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.ModelDrafts;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * <b>What a diagnostic list does and does not count</b> — the consumer-facing question behind G55.
 *
 * <p>The measured answer, and it cuts both ways: the list is <b>neither a complete enumeration of the live
 * defects nor one entry per defect</b>. A rule carrying two independent defects can come back with one code
 * (an objection suppressed), and a rule carrying one defect can come back with two (the same defect reported
 * under two code names). So a count of diagnostics is not a count of problems in either direction, and a lone
 * diagnostic is <em>an</em> objection rather than <em>the</em> objection.
 *
 * <p>This matters for measurement, not for authoring: an author fixes defects one at a time and never sees
 * the difference, while a campaign that attributes a refusal to the reported gate must re-run with the other
 * defect removed before the attribution holds.
 *
 * <p><b>The suppression is the kernel's, not ours</b>, and {@link #aMissingErrorFieldReportsTwoCodesForOneDefect}
 * is what settles that rather than an appeal to our source: it shows two kernel codes travelling through the
 * same path intact, so the single code in {@link #twoIndependentDefectsReportOnlyOneOfThem} cannot be our
 * plumbing dropping one. ({@code RuleValidator.checkInjected} does loop every {@code Notification} — but that
 * is a source reading, and this class is the executable check.)
 *
 * <p><b>Denominator, stated plainly:</b> two multi-defect pairs on one fixture. Enough to establish that
 * suppression and code-duplication both occur, which is what a consumer needs in order to stop counting; not
 * a general law about which gate wins, and no wider sweep was run.
 */
class DiagnosticMultiplicityLawsTest {

    private static final String FRAGMENTS = ModelDrafts.dateFragments().toJson();

    private static final String YEAR_CLASS_MIX =
            "AllFieldsFilled(MonthOnly, YearOnly) And [MonthOnly] == [YearOnly]";

    private static List<String> codesFor(String errorField, String condition) {
        ValidationResult result = KernelLaws.checkModel(FRAGMENTS, "/Doc", errorField, condition);
        return result.diagnostics().stream().map(d -> d.code().orElse("<uncoded>")).sorted().toList();
    }

    /** Control A: the year-class defect alone, with an error field the condition does reference. */
    @Test
    void theComparisonDefectAloneReportsItsOwnCode() {
        assertEquals(List.of("MVK_INVALID_COMPARE_TO_DATE"), codesFor("../MonthOnly", YEAR_CLASS_MIX));
    }

    /** Control B: the unreferenced-error-field defect alone, on a comparison the kernel accepts. */
    @Test
    void theUnreferencedErrorFieldAloneReportsItsOwnCode() {
        assertEquals(List.of("MVK_ERROR_FIELD_NOT_REFERENCED"),
                codesFor("../Ref", "AllFieldsFilled(YearOnly, FullDate) And [YearOnly] == [FullDate]"));
    }

    /**
     * Both defects live at once, and only the comparison is reported. Each control above fires its own code in
     * isolation, so this is a clean two-variable case: {@code MVK_ERROR_FIELD_NOT_REFERENCED} is suppressed
     * here, not absent.
     */
    @Test
    void twoIndependentDefectsReportOnlyOneOfThem() {
        assertEquals(List.of("MVK_INVALID_COMPARE_TO_DATE"), codesFor("../Ref", YEAR_CLASS_MIX));
    }

    /**
     * The mirror case, and the one that refutes "a diagnostic list is one entry per defect": an error field
     * that does not exist yields TWO codes for that single defect, while the year-class defect in the very
     * same condition is suppressed. Note which way the precedence went — the opposite of
     * {@link #twoIndependentDefectsReportOnlyOneOfThem}, where the comparison won.
     *
     * <p>A plausible mechanism is that a missing error field is a model-shape objection raised earlier than the
     * rule-semantics checks, while an unreferenced one is raised later than the comparison check. That is a
     * HYPOTHESIS from the two observations here and is not what this test pins; the assertions are the
     * observations.
     */
    @Test
    void aMissingErrorFieldReportsTwoCodesForOneDefect() {
        assertEquals(List.of("MVK_ERROR_FIELD_MISSING", "MVK_MISSING_ERROR_FIELD"),
                codesFor("../Nope", YEAR_CLASS_MIX));
    }
}

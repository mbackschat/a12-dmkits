package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import org.junit.jupiter.api.Test;

/**
 * KERNEL-FINDINGS §6 — a {@code DateType} whose declared {@code format} carries only SOME calendar components
 * (KF176). This is a different declaration from the two partial shapes already locked elsewhere: it is not a
 * {@code DateFragmentType} (whose own format is partial by design), and not a {@code datePrecision} of
 * {@code DAY_OPTIONAL}/… (whose format is complete while its stored VALUE omits components as zeros — §6
 * "A partial {@code DateType} uses exact zero components"). Here the FORMAT itself is short and the value is
 * complete for it: a field declared {@code yyyy} storing {@code 2020}.
 *
 * <p>Two gates are measured, both at the model tier and both kernel-only — there is no interpreter side to compare,
 * because the kernel owns model admission and {@code rule check} delegates to it.
 *
 * <ol>
 * <li><b>Which incomplete formats exist at all.</b> The admitted set is a <em>whitelist of format strings</em>, not
 *     a compositional rule. The discriminator is that the same two components flip on their SEPARATOR
 *     ({@code yyyy-MM} is admitted, {@code yyyy/MM} and {@code yyyy.MM} are refused) while a complete format uses
 *     {@code .} freely ({@code dd.MM.yyyy}). Component ORDER alone does not explain it either: {@code MM} is
 *     admitted and {@code dd.MM} is refused. So an implementation must not infer the domain — it takes whatever
 *     format a kernel-accepted model declares.</li>
 * <li><b>Which extractors each admits.</b> An extractor for a component the format does not carry is rejected at
 *     codegen with {@code MVK_WRONG_DATE_FORMAT_FOR_…}, so the completed components are deliberately NOT reachable
 *     through {@code YearFromDate}/{@code MonthFromDate}/{@code DayFromDate}. That is what makes the completion
 *     observable only through comparison and difference — see KF176 for the runtime half.</li>
 * </ol>
 *
 * <p><b>Interpreter status: does NOT match</b> — {@code IG110} owns it. On a legal {@code yyyy} field the
 * interpreter resolves the cell to Unknown and silently suppresses every referencing rule; on {@code yyyy-MM},
 * {@code yyyyMM} and {@code MM} it emits a false {@code datumFormatFalsch}. This file locks the kernel side so the
 * fix has an oracle to move toward.
 */
class IncompleteDateFormatAdmissionLawsTest {

    /** A model with one date field at {@code format} plus a number field to hang an error on. */
    private static String visitModel(String format) {
        return DocumentModelDraft.named("visit").locale("en_US")
                .rootGroup("Visit", root -> root
                        .dateField("SeenOn", d -> d.format(format))
                        .numberField("Probe", n -> n.fractionDigits(0)))
                .build().toJson();
    }

    /**
     * Bind {@code format}'s model through the kernel consistency gate — {@code BoundModel.ofJson} runs
     * {@code checkConsistency} and throws on an ERROR. Drafting alone does NOT decide this: the draft builder
     * serializes any format string, so the verdict has to be taken from the gate rather than from {@code build()}.
     */
    private static String formatVerdict(String format) {
        try {
            io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel.ofJson(visitModel(format));
            return "admitted";
        } catch (ModelConsistencyException e) {
            return "refused";
        }
    }

    /**
     * The whitelist, measured on ten candidate formats. Asserted as one table so a kernel upgrade that widens or
     * narrows the set reports every row at once rather than aborting at the first.
     */
    @Test
    void theAdmittedIncompleteDateFormatsAreAWhitelistNotACompositionalRule() {
        List<String> verdicts = new ArrayList<>();
        for (String format : List.of(
                "yyyy", "yyyy-MM", "yyyyMM", "MM", "MM-dd",     // incomplete and admitted
                "yyyy-MM-dd", "dd.MM.yyyy",                      // the complete controls
                "MM.yyyy", "MM-yyyy", "yyyy/MM", "dd.MM")) {     // refused
            verdicts.add(format + " => " + formatVerdict(format));
        }
        assertEquals(List.of(
                "yyyy => admitted",
                "yyyy-MM => admitted",
                "yyyyMM => admitted",
                "MM => admitted",
                // MM-dd was missing from this table until 2026-08-19, which is why three published schema
                // descriptions named a two-format DATE allowlist: the enumeration they were derived from was
                // itself short. It is the day-and-month shape the DATERANGE allowlist also admits.
                "MM-dd => admitted",
                "yyyy-MM-dd => admitted",
                "dd.MM.yyyy => admitted",
                // The separator discriminator: the SAME components as the admitted `yyyy-MM`.
                "MM.yyyy => refused",
                "MM-yyyy => refused",
                "yyyy/MM => refused",
                "dd.MM => refused"), verdicts);
    }

    @Test
    void anExtractorForAComponentTheFormatOmitsIsRejectedAtCodegen() {
        List<String> verdicts = new ArrayList<>();
        for (String format : List.of("yyyy", "yyyy-MM", "yyyyMM", "MM", "yyyy-MM-dd")) {
            String json = visitModel(format);
            for (String extractor : List.of("YearFromDate", "MonthFromDate", "DayFromDate")) {
                var result = KernelLaws.checkModel(json, "/Visit", "../Probe",
                        "FieldFilled(Probe) And " + extractor + "(SeenOn) == 1");
                verdicts.add(format + " " + extractor + " => " + (result.ok() ? "admitted" : "refused"));
            }
        }
        assertEquals(List.of(
                // `yyyy` carries only the year, so only YearFromDate is authorable over it.
                "yyyy YearFromDate => admitted",
                "yyyy MonthFromDate => refused",
                "yyyy DayFromDate => refused",
                "yyyy-MM YearFromDate => admitted",
                "yyyy-MM MonthFromDate => admitted",
                "yyyy-MM DayFromDate => refused",
                "yyyyMM YearFromDate => admitted",
                "yyyyMM MonthFromDate => admitted",
                "yyyyMM DayFromDate => refused",
                // The row that shows the gate reads the format's components and not its length: a month-only
                // format admits the MONTH extractor and refuses the year.
                "MM YearFromDate => refused",
                "MM MonthFromDate => admitted",
                "MM DayFromDate => refused",
                // The complete control — without it, "refused" rows would be satisfied by a fixture whose
                // extractors never work at all.
                "yyyy-MM-dd YearFromDate => admitted",
                "yyyy-MM-dd MonthFromDate => admitted",
                "yyyy-MM-dd DayFromDate => admitted"), verdicts);
    }

    @Test
    void aRefusedFormatFailsTheConsistencyGateAndNotTheDraftBuilder() {
        // Where the refusal lives is load-bearing: the DRAFT builder serializes `MM.yyyy` happily, and only the
        // kernel gate refuses it. A reader who assumed the builder screens formats would conclude the opposite of
        // the table above, and an authoring tool would surface the failure at the wrong step.
        String json = visitModel("MM.yyyy");
        var thrown = assertThrows(ModelConsistencyException.class,
                () -> io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel.ofJson(json));
        assertEquals(true, thrown.getMessage().contains("The date format is invalid"), thrown.getMessage());
    }
}

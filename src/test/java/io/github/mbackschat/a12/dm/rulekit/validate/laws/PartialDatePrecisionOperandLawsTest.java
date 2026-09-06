package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DatePrecision;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * KERNEL-FINDINGS §6/§9 — <b>a partially known Date splits the four field-list operators along the identity
 * they compare</b>. {@code MinValue}, {@code MaxValue} and {@code NumberOfDifferentValues} compare a decoded
 * date, which a partial has not, and refuse every precision {@code MVK_PARTIAL_DATE_NOT_ALLOWED};
 * {@code FieldValuesNotUnique} compares the stored text, which a partial has, and admits all three.
 *
 * <p><b>Precision is independent of the declared component set, which is why an operand gate reading
 * components alone cannot see it.</b> Every field here declares the same complete {@code yyyy-MM-dd}; the
 * only variable across the refused rows is {@code datePrecision}, and the full-precision control on the same
 * fixture is admitted at all four carriers.
 *
 * <p><b>{@code ValueAsDate} is the inverse gate</b>, so the distinction is statically enforced in both
 * directions rather than being a one-sided permissiveness: it REQUIRES a partial source and refuses a fully
 * known one {@code MVK_INVALID_DATE_TYPE_FOR_OP}.
 *
 * <p><b>A group operand's expansion does not follow its LEADING declaration here</b>, which is the row
 * a12-kernel-lean asked for and the one a "the leader fixes the class" reading gets wrong: {@code Assessments}
 * declares a full-precision date FIRST and a month-optional one second, and the three decoding carriers still
 * refuse it — so this gate reads the whole expansion. The all-full sibling group is the control.
 *
 * <p>Reported by a12-kernel-lean as {@code SPEC-2026-09-06-05}; measured here on our own fixture. Their entry
 * additionally records that a partial precision is not authorable through {@code field add}, which is not true
 * of this tool — the spec key is {@code date.precision} and the builder row below is {@code datePrecision}.
 *
 * <p>Own domain (an insurance claim file).
 */
class PartialDatePrecisionOperandLawsTest {

    private static final String GROUP = "/Policy";
    private static final String ERROR_REL = "../Insured";
    private static final String PARTIAL_REFUSED = "MVK_PARTIAL_DATE_NOT_ALLOWED";
    private static final String NEEDS_A_PARTIAL = "MVK_INVALID_DATE_TYPE_FOR_OP";

    private static final String CLAIM = DocumentModelDraft.named("claim-precision").locale("en_US")
            .rootGroup("Policy", p -> p
                    .stringField("Insured", s -> { })
                    .dateField("IncidentOn", d -> d.format("yyyy-MM-dd").datePrecision(DatePrecision.DAY_OPTIONAL))
                    .dateField("OnsetOn", d -> d.format("yyyy-MM-dd").datePrecision(DatePrecision.MONTH_OPTIONAL))
                    .dateField("CoverStart", d -> d.format("yyyy-MM-dd").datePrecision(DatePrecision.YEAR_OPTIONAL))
                    .dateField("ReportedOn", d -> d.format("yyyy-MM-dd"))
                    .dateField("ClosedOn", d -> d.format("yyyy-MM-dd"))
                    .group("Assessments", a -> a.repeatable(10)
                            .dateField("AssessedOn", d -> d.format("yyyy-MM-dd"))
                            .dateField("EstimatedOn",
                                    d -> d.format("yyyy-MM-dd").datePrecision(DatePrecision.MONTH_OPTIONAL)))
                    .group("Reviews", r -> r.repeatable(10)
                            .dateField("ReviewedOn", d -> d.format("yyyy-MM-dd"))
                            .dateField("SignedOn", d -> d.format("yyyy-MM-dd"))))
            .build().toJson();

    private static String verdict(String condition) {
        ValidationResult r = KernelLaws.checkModel(CLAIM, GROUP, ERROR_REL, "FieldFilled(Insured) And " + condition);
        return r.ok() ? KernelLaws.ADMITTED : r.diagnostics().stream()
                .map(d -> d.code().orElse("(no code: " + d.summary() + ")"))
                .collect(Collectors.joining(","));
    }

    /** The three refusing carriers, crossed against the three precisions, with the full-precision control. */
    @Test
    void theDecodingCarriersRefuseEveryPrecisionAndTheTextCarrierAdmitsThem() {
        Map<String, String> actual = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();
        record Precision(String label, String field) { }
        var precisions = java.util.List.of(
                new Precision("DAY_OPTIONAL", "IncidentOn"),
                new Precision("MONTH_OPTIONAL", "OnsetOn"),
                new Precision("YEAR_OPTIONAL", "CoverStart"));
        for (Precision precision : precisions) {
            String p = precision.field();
            actual.put("MinValue + " + precision.label(), verdict("MinValue(" + p + ", ReportedOn) == [ClosedOn]"));
            actual.put("MaxValue + " + precision.label(), verdict("MaxValue(" + p + ", ReportedOn) == [ClosedOn]"));
            actual.put("NumberOfDifferentValues + " + precision.label(),
                    verdict("NumberOfDifferentValues(" + p + ", ReportedOn) == 1"));
            actual.put("FieldValuesNotUnique + " + precision.label(),
                    verdict("FieldValuesNotUnique(" + p + ", ReportedOn)"));
            expected.put("MinValue + " + precision.label(), PARTIAL_REFUSED);
            expected.put("MaxValue + " + precision.label(), PARTIAL_REFUSED);
            expected.put("NumberOfDifferentValues + " + precision.label(), PARTIAL_REFUSED);
            expected.put("FieldValuesNotUnique + " + precision.label(), KernelLaws.ADMITTED);
        }
        actual.put("MinValue — full-precision control", verdict("MinValue(ReportedOn, ClosedOn) == [ClosedOn]"));
        actual.put("MaxValue — full-precision control", verdict("MaxValue(ReportedOn, ClosedOn) == [ClosedOn]"));
        actual.put("NumberOfDifferentValues — full-precision control",
                verdict("NumberOfDifferentValues(ReportedOn, ClosedOn) == 1"));
        actual.put("FieldValuesNotUnique — full-precision control",
                verdict("FieldValuesNotUnique(ReportedOn, ClosedOn)"));
        expected.put("MinValue — full-precision control", KernelLaws.ADMITTED);
        expected.put("MaxValue — full-precision control", KernelLaws.ADMITTED);
        expected.put("NumberOfDifferentValues — full-precision control", KernelLaws.ADMITTED);
        expected.put("FieldValuesNotUnique — full-precision control", KernelLaws.ADMITTED);

        assertEquals(expected, actual, "the split follows the compared identity: decoded date vs stored text");
    }

    /** The inverse gate — the reason the split is a declaration rule rather than one-sided permissiveness. */
    @Test
    void valueAsDateRequiresThePartialItsNeighboursRefuse() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("ValueAsDate on a partially known date",
                verdict("ValueAsDate(IncidentOn, FirstDay) > [ReportedOn]"));
        actual.put("ValueAsDate on a fully known date", verdict("ValueAsDate(ReportedOn, FirstDay) > [ClosedOn]"));
        actual.put("ValueAsDate LastDay on a partially known date",
                verdict("ValueAsDate(CoverStart, LastDay) > [ReportedOn]"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("ValueAsDate on a partially known date", KernelLaws.ADMITTED);
        expected.put("ValueAsDate on a fully known date", NEEDS_A_PARTIAL);
        expected.put("ValueAsDate LastDay on a partially known date", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "ValueAsDate is the inverse of the extrema/distinct-count gate");
    }

    /**
     * The group carrier. {@code Assessments} declares its FULL-precision date first, so a leader-keyed reading
     * predicts admission; the gate reads the whole expansion and refuses.
     */
    @Test
    void aGroupExpansionIsGatedByAnyPartialMemberAndNotByItsLeader() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("MinValue over a mixed-precision expansion", verdict("MinValue(Assessments*) == [ClosedOn]"));
        actual.put("MaxValue over a mixed-precision expansion", verdict("MaxValue(Assessments*) == [ClosedOn]"));
        actual.put("NumberOfDifferentValues over a mixed-precision expansion",
                verdict("NumberOfDifferentValues(Assessments*) == 1"));
        actual.put("FieldValuesNotUnique over a mixed-precision expansion",
                verdict("FieldValuesNotUnique(Assessments*)"));
        actual.put("MinValue over an all-full expansion — control", verdict("MinValue(Reviews*) == [ClosedOn]"));
        actual.put("NumberOfDifferentValues over an all-full expansion — control",
                verdict("NumberOfDifferentValues(Reviews*) == 1"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("MinValue over a mixed-precision expansion", PARTIAL_REFUSED);
        expected.put("MaxValue over a mixed-precision expansion", PARTIAL_REFUSED);
        expected.put("NumberOfDifferentValues over a mixed-precision expansion", PARTIAL_REFUSED);
        expected.put("FieldValuesNotUnique over a mixed-precision expansion", KernelLaws.ADMITTED);
        expected.put("MinValue over an all-full expansion — control", KernelLaws.ADMITTED);
        expected.put("NumberOfDifferentValues over an all-full expansion — control", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "the precision gate reads the expansion, not its leading declaration");
    }
}

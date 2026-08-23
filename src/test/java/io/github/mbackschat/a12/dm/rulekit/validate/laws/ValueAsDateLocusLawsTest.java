package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DatePrecision;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * KERNEL-FINDINGS §6/§9 — a partial-Date {@code ValueAsDate} operand may lie INSIDE a repeatable group, and
 * admission is decided by the <b>reading rule's own iteration scope</b> rather than by the operand's spelling
 * (SPEC-2026-08-23-12).
 *
 * <p><b>The same condition text, two loci, two answers.</b> The persisted condition normalises a relative
 * operand to its absolute path, so a consumer cannot tell the two apart by reading the rule: what decides is
 * where the rule sits. That is why this is a locus law and not an operand-position one —
 * {@link ValueAsDatePlacementLawsTest} owns the position question, and its verdicts hold at either locus.
 *
 * <p>Two refusals ride along, each its own gate. A <b>starred</b> operand has no admitting locus at all, and
 * a bare partial-Date reference is not a Date operand: {@code MVK_INVALID_DATE_TYPE} is precisely the
 * refusal {@code ValueAsDate} exists to bridge, so a consumer that cannot produce it leaves an author with no
 * hint about which operation to reach for.
 *
 * <p>Own domain (insurance claim intake).
 */
class ValueAsDateLocusLawsTest {

    private static final String ADMITTED = "ADMITTED";
    private static final String INTAKE = "/Intake";
    private static final String ROWS = "/Intake/Rows";

    private static final String MODEL = DocumentModelDraft.named("intake-locus").locale("en_US")
            .rootGroup("Intake", i -> i
                    .stringField("Reference", f -> { })
                    .dateField("Limit", d -> d.format("yyyy-MM-dd"))
                    .dateField("PartialTop", d -> d.format("yyyy-MM-dd").datePrecision(DatePrecision.DAY_OPTIONAL))
                    .group("Rows", g -> g.repeatable(20).indexField("RowKey")
                            .field("RowKey", FieldKind.STRING)
                            .stringField("RowNote", f -> { })
                            .dateField("Partial",
                                    d -> d.format("yyyy-MM-dd").datePrecision(DatePrecision.DAY_OPTIONAL))))
            .build().toJson();

    /** One text, normalised to the absolute operand path exactly as a persisted condition would be. */
    private static final String ROW_READ =
            "ValueAsDate(/Intake/Rows/Partial, FirstDay) > [/Intake/Limit]";

    @Test
    void theSameRowReadIsAdmittedInsideAndRefusedOutside() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("read from the row's own rule", verdict(ROWS, "../RowNote",
                "FieldFilled(/Intake/Rows/RowNote) And " + ROW_READ));
        actual.put("the same read from the form's rule", verdict(INTAKE, "../Reference",
                "FieldFilled(/Intake/Reference) And " + ROW_READ));
        actual.put("a nonrepeatable operand from the form's rule — control", verdict(INTAKE, "../Reference",
                "FieldFilled(/Intake/Reference) And ValueAsDate(/Intake/PartialTop, FirstDay) > [/Intake/Limit]"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("read from the row's own rule", ADMITTED);
        expected.put("the same read from the form's rule", "MVK_NO_WILDCARD");
        expected.put("a nonrepeatable operand from the form's rule — control", ADMITTED);
        assertEquals(expected, actual, "the reading rule's scope decides, and the operand text cannot show it");
    }

    /** A starred operand has no admitting locus, and the bare reference is a different gate entirely. */
    @Test
    void theStarredOperandAndTheBareReferenceAreSeparateGates() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("starred operand, form locus", verdict(INTAKE, "../Reference",
                "FieldFilled(/Intake/Reference) And ValueAsDate(/Intake/Rows*/Partial, FirstDay) > [/Intake/Limit]"));
        actual.put("starred operand, row locus", verdict(ROWS, "../RowNote",
                "FieldFilled(/Intake/Rows/RowNote) And "
                        + "ValueAsDate(/Intake/Rows*/Partial, FirstDay) > [/Intake/Limit]"));
        actual.put("a bare partial-Date reference as a plain Date operand", verdict(INTAKE, "../Reference",
                "FieldFilled(/Intake/Reference) And [/Intake/PartialTop] > [/Intake/Limit]"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("starred operand, form locus", "MVK_NO_WILDCARDS_ALLOWED");
        expected.put("starred operand, row locus", "MVK_NO_WILDCARDS_ALLOWED");
        expected.put("a bare partial-Date reference as a plain Date operand", "MVK_INVALID_DATE_TYPE");
        assertEquals(expected, actual, "no locus admits a star, and the bare reference fails the TYPE gate");
    }

    private static String verdict(String group, String errorField, String condition) {
        ValidationResult r = new RuleValidator().check(MODEL,
                new DraftRule(group, "LocusProbe", errorField, condition, "LOCUS", "ERROR"));
        return r.ok() ? ADMITTED
                : r.diagnostics().stream().map(d -> d.code().orElse("(no code)")).collect(Collectors.joining(","));
    }
}

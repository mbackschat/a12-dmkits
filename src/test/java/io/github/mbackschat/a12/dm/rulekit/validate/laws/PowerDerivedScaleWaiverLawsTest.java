package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * KERNEL-FINDINGS §4 — <b>the power operator's derived scale, and the one waiver that admits an unknown one
 * into a known-scale target</b>. [KF43] states the derivation and [KF45] the exponent gate; this locks the
 * discriminator that separates them from a rival reading, and adds the waiver's reach.
 *
 * <p><b>The discriminating case is a scale-2 base against a scale-2 literal.</b> "Power derives the BASE's
 * scale for a literal exponent" and "power derives scale 0 only when the base is itself scale 0, else
 * unknown" agree on every row where the base is scale 0 and on every row where the comparand is scale 0. They
 * disagree on exactly one cell, {@code [Rate] ^ 2 == 100.00}, which the first reading admits and the second
 * refuses. Measured: refused. The multiplication row beside it is the armed control — {@code * } derives a
 * KNOWN scale 4 there and the same scale-2 literal is admitted, so the refusal is the power's derivation and
 * not a comparand rule.
 *
 * <p><b>The exponent gate and the derived scale are two different gates, and the pair below keeps them
 * apart.</b> An exponent of KNOWN scale greater than 0 — a fractional literal, a decimal-scale field — is
 * refused {@code MVK_INVALID_NUMBER_FOR_EXP}, and so is an UNKNOWN-scale expression such as a bare division;
 * a known scale-0 non-literal exponent passes that gate and then makes the derived scale unknown, which
 * ordering admits and equality refuses.
 *
 * <p><b>The waiver is what shows the unknown-scale shape is legal rather than merely tolerated</b>, and it is
 * not power-specific: {@code errorCodesToSuppress: ["MVK_INVALID_COMPARE_DEC_PLACES"]} admits a non-literal
 * exponent, a known-scale aggregate exponent and a bare division alike into the same scale-0 target that
 * refuses all three without it — one property apart.
 *
 * <p>Reported by a12-kernel-lean as {@code SPEC-2026-09-05-10}, whose clause states the derived scale as the
 * base's and the operand shapes as unrestricted. Both halves are refuted by the rows below; the 2×2 and the
 * waiver they also claim are confirmed.
 *
 * <p>Own domain (an e-commerce pricing sheet).
 */
class PowerDerivedScaleWaiverLawsTest {

    private static final RuleValidator VALIDATOR = new RuleValidator();

    private static final String GROUP = "/Sheet";
    private static final String ERROR_REL = "../Ref";
    private static final String SCALE_MISMATCH = "MVK_INVALID_COMPARE_DEC_PLACES";
    private static final String BAD_EXPONENT = "MVK_INVALID_NUMBER_FOR_EXP";

    private static final String SHEET = DocumentModelDraft.named("pricing-sheet").locale("en_US")
            .rootGroup("Sheet", s -> s
                    .stringField("Ref", f -> { })
                    .numberField("Qty", n -> n.fractionDigits(0))
                    .numberField("Exp", n -> n.fractionDigits(0))
                    .numberField("Rate", n -> n.fractionDigits(2))
                    .numberField("Scale0Target", n -> n.fractionDigits(0))
                    .group("Lines", g -> g.repeatable(20)
                            .numberField("LineQty", n -> n.fractionDigits(0))))
            .build().toJson();

    private static String verdict(String condition) {
        ValidationResult r = KernelLaws.checkModel(SHEET, GROUP, ERROR_REL, "FieldFilled(Ref) And " + condition);
        return r.ok() ? KernelLaws.ADMITTED : r.diagnostics().stream()
                .map(d -> d.code().orElse("(no code: " + d.summary() + ")"))
                .collect(Collectors.joining(","));
    }

    /** A computation writing the scale-0 target, optionally carrying the one suppressible code. */
    private static String target(String operation, boolean waived) {
        DraftComputation computation = new DraftComputation(GROUP, "TargetComp", "../Scale0Target", null,
                List.of(new DraftComputation.Alternative(null, operation)),
                waived ? List.of(SCALE_MISMATCH) : List.of());
        ValidationResult r = VALIDATOR.check(SHEET, computation);
        return r.ok() ? KernelLaws.ADMITTED : r.diagnostics().stream()
                .map(d -> d.code().orElse("(no code: " + d.summary() + ")"))
                .collect(Collectors.joining(","));
    }

    /** The one cell where the two readings of the derived scale disagree, with its armed control. */
    @Test
    void aLiteralExponentDerivesScaleZeroOnlyFromAScaleZeroBase() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("scale-0 base ^ 2 == scale-0 literal", verdict("[Qty] ^ 2 == 100"));
        actual.put("scale-0 base ^ 2 == scale-2 literal", verdict("[Qty] ^ 2 == 100.00"));
        actual.put("scale-2 base ^ 2 == scale-2 literal — THE DISCRIMINATOR", verdict("[Rate] ^ 2 == 100.00"));
        actual.put("scale-2 base ^ 2 == scale-0 literal", verdict("[Rate] ^ 2 == 100"));
        actual.put("scale-0 base ^ -2 == scale-0 literal", verdict("[Qty] ^ -2 == 100"));
        actual.put("scale-2 * scale-2 == scale-2 literal — armed control", verdict("[Rate] * [Rate] == 100.00"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("scale-0 base ^ 2 == scale-0 literal", KernelLaws.ADMITTED);
        expected.put("scale-0 base ^ 2 == scale-2 literal", SCALE_MISMATCH);
        expected.put("scale-2 base ^ 2 == scale-2 literal — THE DISCRIMINATOR", SCALE_MISMATCH);
        expected.put("scale-2 base ^ 2 == scale-0 literal", SCALE_MISMATCH);
        expected.put("scale-0 base ^ -2 == scale-0 literal", SCALE_MISMATCH);
        expected.put("scale-2 * scale-2 == scale-2 literal — armed control", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "the derivation is scale 0 from a scale-0 base and a nonnegative literal");
    }

    /**
     * The 2×2 that separates the derived scale from the exponent gate: hold the expression fixed, move the
     * comparison and the exponent's form.
     */
    @Test
    void theExponentGateReadsKnownScaleAndTheDerivedScaleReadsLiteralness() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("== with a literal exponent", verdict("[Qty] ^ 2 == 100"));
        actual.put("== with a scale-0 FIELD exponent", verdict("[Qty] ^ [Exp] == 100"));
        actual.put("> with a literal exponent", verdict("[Qty] ^ 2 > 100"));
        actual.put("> with a scale-0 FIELD exponent", verdict("[Qty] ^ [Exp] > 100"));
        actual.put("> with a known scale-0 AGGREGATE exponent", verdict("[Qty] ^ Sum(Lines*/LineQty) > 100"));
        actual.put("== with a known scale-0 AGGREGATE exponent", verdict("[Qty] ^ Sum(Lines*/LineQty) == 100"));
        actual.put("> with an UNKNOWN-scale exponent (a bare division)", verdict("[Qty] ^ {[Qty] / 2} > 100"));
        actual.put("> with a fractional literal exponent", verdict("[Qty] ^ 0.5 > 100"));
        actual.put("> with a decimal-scale FIELD exponent", verdict("[Qty] ^ [Rate] > 100"));
        actual.put("> with a grouped arithmetic base", verdict("{[Qty] + 1} ^ 2 > 100"));
        actual.put("> with an aggregate base", verdict("Sum(Lines*/LineQty) ^ 2 > 100"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("== with a literal exponent", KernelLaws.ADMITTED);
        expected.put("== with a scale-0 FIELD exponent", SCALE_MISMATCH);
        expected.put("> with a literal exponent", KernelLaws.ADMITTED);
        expected.put("> with a scale-0 FIELD exponent", KernelLaws.ADMITTED);
        expected.put("> with a known scale-0 AGGREGATE exponent", KernelLaws.ADMITTED);
        expected.put("== with a known scale-0 AGGREGATE exponent", SCALE_MISMATCH);
        expected.put("> with an UNKNOWN-scale exponent (a bare division)", BAD_EXPONENT);
        expected.put("> with a fractional literal exponent", BAD_EXPONENT);
        expected.put("> with a decimal-scale FIELD exponent", BAD_EXPONENT);
        expected.put("> with a grouped arithmetic base", KernelLaws.ADMITTED);
        expected.put("> with an aggregate base", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "the exponent gate refuses a known scale > 0 AND an unknown one");
    }

    /** One waiver, three unknown-scale operations, one property apart. */
    @Test
    void oneWaiverAdmitsEveryUnknownScaleOperationIntoAKnownScaleTarget() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("literal exponent, no waiver", target("[Qty] ^ 2", false));
        actual.put("field exponent, no waiver", target("[Qty] ^ [Exp]", false));
        actual.put("field exponent, waived", target("[Qty] ^ [Exp]", true));
        actual.put("aggregate exponent, no waiver", target("[Qty] ^ Sum(Lines*/LineQty)", false));
        actual.put("aggregate exponent, waived", target("[Qty] ^ Sum(Lines*/LineQty)", true));
        actual.put("bare division, no waiver", target("[Qty] / 2", false));
        actual.put("bare division, waived", target("[Qty] / 2", true));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("literal exponent, no waiver", KernelLaws.ADMITTED);
        expected.put("field exponent, no waiver", SCALE_MISMATCH);
        expected.put("field exponent, waived", KernelLaws.ADMITTED);
        expected.put("aggregate exponent, no waiver", SCALE_MISMATCH);
        expected.put("aggregate exponent, waived", KernelLaws.ADMITTED);
        expected.put("bare division, no waiver", SCALE_MISMATCH);
        expected.put("bare division, waived", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "the waiver is the unknown-scale escape hatch, not a power-specific one");
    }
}

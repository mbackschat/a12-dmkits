package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * KERNEL-FINDINGS §1/§6 — <b>a typedef-typed field is its BASE KIND at every gate</b>, which is what makes it
 * safe for a consumer to work on an already-expanded model.
 *
 * <p><b>Why this is worth a lock rather than an assumption.</b> Our CLI already reports a typedef field's
 * resolved underlying kind, so the read surface behaves as if resolution happened — but that is our
 * classification, not the kernel's admission gates. If a gate treated a typedef as an opaque kind of its own,
 * every rule over one would be refused, and if it silently admitted one everywhere, a cross-family list would
 * pass. Both readings die on the spread below rather than on any single row.
 *
 * <p><b>Each gate carries its own plain-base-kind control, and the three gates report three DIFFERENT codes.</b>
 * That is the separator: a uniform verdict — all admitted, or all refused — is what either rival account
 * predicts, and neither can produce three distinct classes keyed to the base kind.
 *
 * <p>Reported by a12-kernel-lean as {@code SPEC-2026-09-07-05}, whose point is the consequence rather than the
 * rows: a clause that puts model include/expansion out of scope is resting on this rule, so a surface that
 * documents the exclusion without it rests on an unmeasured premise. Re-derived here on our own fixture.
 *
 * <p>Unmeasured: a typedef over a config-bearing base kind (DATE, ENUM), where a declared format or domain
 * might not survive resolution; a typedef imported from a separate type-def model rather than declared inline;
 * and the computation arm.
 *
 * <p>Own domain (a warehouse stock sheet).
 */
class TypedefBaseKindGateLawsTest {

    private static final String GROUP = "/Stock";

    /**
     * Two inline typedefs — one over NUMBER, one over STRING — beside a plain field of each base kind, so
     * every row below has a control differing in exactly whether the field is typedef-typed.
     */
    private static final String STOCK = DocumentModelDraft.named("warehouse-stock").locale("en_US")
            .typeDef("Quantity", t -> t.number(n -> n.fractionDigits(0)))
            .typeDef("SkuCode", t -> t.string(s -> { }))
            .rootGroup("Stock", g -> g
                    .stringField("SheetRef", s -> { })
                    .typedefField("OnHand", "Quantity")
                    .typedefField("Sku", "SkuCode")
                    .numberField("Reserved", n -> n.fractionDigits(0))
                    .stringField("Bin", s -> { }))
            .build().toJson();

    private static String verdict(String condition) {
        ValidationResult r = KernelLaws.checkModel(STOCK, GROUP, "../SheetRef",
                "FieldFilled(SheetRef) And " + condition);
        return r.ok() ? KernelLaws.ADMITTED : r.diagnostics().stream()
                .map(d -> d.code().orElse("(no code: " + d.summary() + ")"))
                .collect(Collectors.joining(","));
    }

    @Test
    void aTypedefFieldIsItsBaseKindAtThreeGatesReportingThreeDifferentCodes() {
        Map<String, String> actual = new LinkedHashMap<>();

        // Gate 1 — Sum's numeric operand gate.
        actual.put("Sum over the NUMBER-based typedef", verdict("Sum(OnHand, Reserved) > 0"));
        actual.put("Sum over two plain numbers — control", verdict("Sum(Reserved, Reserved) > 0"));
        actual.put("Sum over the STRING-based typedef", verdict("Sum(Sku, Reserved) > 0"));
        actual.put("Sum over a plain string — control", verdict("Sum(Bin, Reserved) > 0"));

        // Gate 2 — NumberOfDifferentValues' homogeneity gate, whose code names the LEADING operand's class.
        actual.put("count over the STRING-based typedef beside a string",
                verdict("NumberOfDifferentValues(Sku, Bin) > 1"));
        actual.put("count over two plain strings — control", verdict("NumberOfDifferentValues(Bin, Bin) > 1"));
        actual.put("count over the STRING-based typedef beside a number",
                verdict("NumberOfDifferentValues(Sku, Reserved) > 1"));
        actual.put("count over a plain string beside a number — control",
                verdict("NumberOfDifferentValues(Bin, Reserved) > 1"));

        // Gate 3 — the ordering comparison's orderability gate, which reports before any kind match.
        actual.put("ordering the NUMBER-based typedef against a numeric literal", verdict("[OnHand] > 5"));
        actual.put("ordering a plain number — control", verdict("[Reserved] > 5"));
        actual.put("ordering the STRING-based typedef against a numeric literal", verdict("[Sku] > 5"));
        actual.put("ordering a plain string — control", verdict("[Bin] > 5"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("Sum over the NUMBER-based typedef", KernelLaws.ADMITTED);
        expected.put("Sum over two plain numbers — control", "MVK_DUPLICATE_PARAM1");
        expected.put("Sum over the STRING-based typedef", "MVK_NO_NUMBER");
        expected.put("Sum over a plain string — control", "MVK_NO_NUMBER");
        expected.put("count over the STRING-based typedef beside a string", KernelLaws.ADMITTED);
        expected.put("count over two plain strings — control", "MVK_DUPLICATE_PARAM1");
        expected.put("count over the STRING-based typedef beside a number",
                "MVK_STRING_ENUM_AND_NON_STRING_ENUM");
        expected.put("count over a plain string beside a number — control",
                "MVK_STRING_ENUM_AND_NON_STRING_ENUM");
        expected.put("ordering the NUMBER-based typedef against a numeric literal", KernelLaws.ADMITTED);
        expected.put("ordering a plain number — control", KernelLaws.ADMITTED);
        expected.put("ordering the STRING-based typedef against a numeric literal",
                "MVK_INVALID_TYPE_FOR_COMPARISON");
        expected.put("ordering a plain string — control", "MVK_INVALID_TYPE_FOR_COMPARISON");
        assertEquals(expected, actual, "every gate reads the typedef's BASE kind, and each reports its own class");
    }
}

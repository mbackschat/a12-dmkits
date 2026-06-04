package io.github.mbackschat.a12.rulekit.validate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.rulekit.dsl.Condition;
import io.github.mbackschat.a12.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.rulekit.model.ModelIndex;
import io.github.mbackschat.a12.rulekit.model.ModelLoader;

/**
 * F4 ground-truth check (proposal §11): rulekit injects a rendered rule into the model as DM-JSON,
 * runs the real kernel's {@code checkConsistency} (no compile, no execute), and reports problems.
 * The validator operates on the rule's {@code errorCondition} text + context, independent of how the
 * text was produced.
 */
class RuleValidatorTest {

    private static String modelJson;

    @BeforeAll
    static void loadFixture() throws Exception {
        try (InputStream in = RuleValidatorTest.class.getClassLoader()
                .getResourceAsStream("models/subscription.dm.json")) {
            assertNotNull(in, "fixture not found");
            modelJson = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void acceptsAWellFormedRule() {
        // Mirrors the kernel demo's proven QuantityPositiveRule pattern (short-name form).
        DraftRule rule = new DraftRule(
                "/Subscription/Addons", "FeePositiveRule", "../MonthlyFee",
                "FieldFilled(MonthlyFee) And [MonthlyFee] <= 0", "FEE_POSITIVE", "ERROR");

        ValidationResult result = new RuleValidator().check(modelJson, rule);

        assertTrue(result.ok(), () -> "expected a valid rule, got: " + result.problems());
    }

    @Test
    void validatesRendererOutputEndToEnd() throws Exception {
        BoundModel m;
        try (Reader r = new InputStreamReader(
                RuleValidatorTest.class.getClassLoader().getResourceAsStream("models/subscription.dm.json"),
                StandardCharsets.UTF_8)) {
            m = BoundModel.of(ModelIndex.of(ModelLoader.loadSelfContained(r)));
        }
        Condition condition = m.num("/Subscription/Addons/MonthlyFee").filled()
                .and(m.num("/Subscription/Addons/MonthlyFee").le(0));
        String dsl = new DslRenderer().render(condition);

        DraftRule rule = new DraftRule(
                "/Subscription/Addons", "RenderedFeeRule", "../MonthlyFee", dsl, "FEE_POSITIVE", "ERROR");

        ValidationResult result = new RuleValidator().check(modelJson, rule);

        assertTrue(result.ok(), () -> "renderer output should validate against the kernel, got: " + result.problems());
    }

    @Test
    void validatesEnumComparisonAgainstTheKernel() throws Exception {
        BoundModel m;
        try (Reader r = new InputStreamReader(
                RuleValidatorTest.class.getClassLoader().getResourceAsStream("models/subscription.dm.json"),
                StandardCharsets.UTF_8)) {
            m = BoundModel.of(ModelIndex.of(ModelLoader.loadSelfContained(r)));
        }
        // Tier is a text-bearing enum (BASIC/PRO/ENTERPRISE) — confirm comparison by stored value is accepted.
        String dsl = new DslRenderer().render(m.enumOf("/Subscription/Tier").is("PRO"));

        DraftRule rule = new DraftRule(
                "/Subscription", "TierIsProRule", "../Tier", dsl, "TIER_PRO", "ERROR");

        ValidationResult result = new RuleValidator().check(modelJson, rule);

        assertTrue(result.ok(), () -> "enum-by-value comparison should validate, got: " + result.problems());
    }

    @Test
    void acceptsScaleFreeNumericLiteralsOnEquality() {
        // Verified 2026-06-03: the kernel's decimal-scale check (MVK_INVALID_COMPARE_DEC_PLACES)
        // applies to field-vs-field comparisons only — numeric LITERALS are accepted at any written
        // scale against a scale-2 field (== 0, == 0.00, == 0.0, != 0, <= 0.000 all pass).
        // This test locks that behavior so a kernel upgrade that changes it fails loudly.
        // (See KERNEL-INTEGRATION-NOTES.md; the field-vs-field rejection is N7-proven via the
        // catalog's Equal invalidExample.)
        for (String cond : new String[]{"[MonthlyFee] == 0", "[MonthlyFee] == 0.0", "[MonthlyFee] != 0"}) {
            DraftRule rule = new DraftRule(
                    "/Subscription/Addons", "LiteralScaleRule", "../MonthlyFee",
                    "FieldFilled(MonthlyFee) And " + cond, "LIT_SCALE", "ERROR");
            ValidationResult result = new RuleValidator().check(modelJson, rule);
            assertTrue(result.ok(), () -> cond + " should be accepted, got: " + result.problems());
        }
    }

    @Test
    void locksConfirmAndBooleanComparisonRules() {
        // Verified 2026-06-03 (see KERNEL-INTEGRATION-NOTES): boolean compares to True/False/boolean;
        // confirm only to True (or another confirm). Lock both rejection codes.
        DraftRule confirmFalse = new DraftRule(
                "/Subscription", "ConfirmFalseRule", "../TermsAccepted",
                "[TermsAccepted] == False", "CONFIRM_FALSE", "ERROR");
        ValidationResult r1 = new RuleValidator().check(modelJson, confirmFalse);
        assertFalse(r1.ok(), "confirm == False must be rejected");
        assertTrue(r1.problems().stream()
                        .anyMatch(p -> p.mvkCode().filter("MVK_INVALID_COMPARE_TO_YES"::equals).isPresent()),
                () -> "expected MVK_INVALID_COMPARE_TO_YES, got: " + r1.problems());

        DraftRule boolVsString = new DraftRule(
                "/Subscription", "BoolVsStringRule", "../AutoRenew",
                "[AutoRenew] == \"yes\"", "BOOL_STR", "ERROR");
        ValidationResult r2 = new RuleValidator().check(modelJson, boolVsString);
        assertFalse(r2.ok(), "boolean == string must be rejected");
        assertTrue(r2.problems().stream()
                        .anyMatch(p -> p.mvkCode().filter("MVK_INVALID_COMPARE_TO_YESNO"::equals).isPresent()),
                () -> "expected MVK_INVALID_COMPARE_TO_YESNO, got: " + r2.problems());
    }

    @Test
    void locksPatternComparisonSlotRules() {
        // Verified 2026-06-04 (see KERNEL-FINDINGS §7): the regex gate is Java Pattern.compile PLUS
        // a portable-subset check — lookbehind is Java-valid yet rejected. So rulekit's
        // Pattern.compile pre-check is a necessary condition only; the subset stays kernel-delegated.
        DraftRule lookbehind = new DraftRule(
                "/Subscription/Billing", "LookbehindRule", "../InvoiceEmail",
                "[InvoiceEmail] PatternMatched \"(?<=a)b\"", "PAT_LB", "ERROR");
        ValidationResult r1 = new RuleValidator().check(modelJson, lookbehind);
        assertFalse(r1.ok(), "lookbehind is outside the portable regex subset");
        assertTrue(r1.problems().stream()
                        .anyMatch(p -> p.mvkCode().filter("MVK_INVALID_PATTERN"::equals).isPresent()),
                () -> "expected MVK_INVALID_PATTERN, got: " + r1.problems());

        // The right side must be a string CONSTANT — a field reference is rejected.
        DraftRule fieldRight = new DraftRule(
                "/Subscription/Billing", "FieldRightRule", "../InvoiceEmail",
                "[InvoiceEmail] PatternMatched [InvoiceEmail]", "PAT_FR", "ERROR");
        ValidationResult r2 = new RuleValidator().check(modelJson, fieldRight);
        assertFalse(r2.ok(), "pattern right side must be a string constant");
        assertTrue(r2.problems().stream()
                        .anyMatch(p -> p.mvkCode().filter("MVK_MISSING_CONST_IN_PATTERN_COMP"::equals).isPresent()),
                () -> "expected MVK_MISSING_CONST_IN_PATTERN_COMP, got: " + r2.problems());
    }

    @Test
    void locksKernelStringLiteralEscaping() {
        // Verified 2026-06-04: StringLiteral : '"' ( '""' | ~["] )* '"' — embedded quotes are
        // DOUBLED, backslash is a plain character. Backslash-escaping a quote is a parse error.
        DraftRule doubled = new DraftRule(
                "/Subscription/Billing", "DoubledQuoteRule", "../InvoiceEmail",
                "[InvoiceEmail] == \"say \"\"hi\"\"\"", "STR_DQ", "ERROR");
        assertTrue(new RuleValidator().check(modelJson, doubled).ok(), "doubled-quote escaping is the kernel way");

        DraftRule backslashed = new DraftRule(
                "/Subscription/Billing", "BackslashQuoteRule", "../InvoiceEmail",
                "[InvoiceEmail] == \"say \\\"hi\\\"\"", "STR_BS", "ERROR");
        assertFalse(new RuleValidator().check(modelJson, backslashed).ok(),
                "backslash escapes are not kernel string-literal syntax");
    }

    @Test
    void locksValueListSlotRules() {
        // Verified 2026-06-04 (KERNEL-FINDINGS): value-list numbers are INTEGER literals only —
        // a decimal is a grammar-level parse error, even against a scale-2 field.
        DraftRule decimal = new DraftRule(
                "/Subscription/Billing", "DecimalListRule", "../BaseFee",
                "FieldValueIncludedInValueList(BaseFee, 10.50)", "VL_DEC", "ERROR");
        ValidationResult r1 = new RuleValidator().check(modelJson, decimal);
        assertFalse(r1.ok(), "decimal literals are not allowed in value lists");

        // Boolean/confirm fields are excluded from the family.
        DraftRule boolField = new DraftRule(
                "/Subscription", "BoolListRule", "../AutoRenew",
                "FieldValueIncludedInValueList(AutoRenew, \"x\")", "VL_BOOL", "ERROR");
        ValidationResult r2 = new RuleValidator().check(modelJson, boolField);
        assertFalse(r2.ok(), "boolean fields are not allowed in value lists");
        assertTrue(r2.problems().stream()
                        .anyMatch(p -> p.mvkCode().filter("MVK_ONLY_STRING_ENUM_NUMBER_ALLOWED"::equals).isPresent()),
                () -> "expected MVK_ONLY_STRING_ENUM_NUMBER_ALLOWED, got: " + r2.problems());

        // Shape B field lists must be type-homogeneous.
        DraftRule mixed = new DraftRule(
                "/Subscription/Billing", "MixedListRule", "../InvoiceEmail",
                "NoFieldValueIncludedInValueList(InvoiceEmail, BaseFee In \"x\")", "VL_MIX", "ERROR");
        ValidationResult r3 = new RuleValidator().check(modelJson, mixed);
        assertFalse(r3.ok(), "mixed-type field lists must be rejected");
        assertTrue(r3.problems().stream()
                        .anyMatch(p -> p.mvkCode().filter("MVK_VARYING_TYPES_NOT_ALLOWED"::equals).isPresent()),
                () -> "expected MVK_VARYING_TYPES_NOT_ALLOWED, got: " + r3.problems());
    }

    @Test
    void locksWildcardSlotRules() {
        // Verified 2026-06-04 (KERNEL-FINDINGS §9): '*' builds a repetition LIST — legal only in
        // list slots. A scalar slot (FieldFilled, [value], GroupFilled) rejects it:
        DraftRule scalarStar = new DraftRule(
                "/Subscription", "ScalarStarRule", "../Tier",
                "FieldFilled(Tier) And [Addons*/MonthlyFee] > 100", "WC_SCALAR", "ERROR");
        ValidationResult r1 = new RuleValidator().check(modelJson, scalarStar);
        assertFalse(r1.ok(), "a starred path in a scalar slot must be rejected");
        assertTrue(r1.problems().stream()
                        .anyMatch(p -> p.mvkCode().filter("MVK_NO_WILDCARDS_ALLOWED"::equals).isPresent()),
                () -> "expected MVK_NO_WILDCARDS_ALLOWED, got: " + r1.problems());

        // ... and a star on a non-repeatable group is its own code:
        DraftRule invalidStar = new DraftRule(
                "/Subscription", "InvalidStarRule", "../Tier",
                "FieldFilled(Tier) And AtLeastOneFieldFilled(Billing*/BaseFee)", "WC_INV", "ERROR");
        ValidationResult r2 = new RuleValidator().check(modelJson, invalidStar);
        assertFalse(r2.ok(), "a star on a non-repeatable group must be rejected");
        assertTrue(r2.problems().stream()
                        .anyMatch(p -> p.mvkCode().filter("MVK_INVALID_WILDCARD"::equals).isPresent()),
                () -> "expected MVK_INVALID_WILDCARD, got: " + r2.problems());

        // The list slots accept it (here: a quantifier, in a non-iterating rule).
        DraftRule listStar = new DraftRule(
                "/Subscription", "ListStarRule", "../Tier",
                "FieldFilled(Tier) And AtLeastOneFieldFilled(Addons*/Name)", "WC_LIST", "ERROR");
        assertTrue(new RuleValidator().check(modelJson, listStar).ok(),
                "a starred path in a list slot is legal");
    }

    @Test
    void locksRepetitionNotUniquePositionLaw() {
        // Verified 2026-06-04 (KERNEL-FINDINGS §9): every key must be repeatable RELATIVE to the
        // rule group — position-dependent, so kernel-delegated rather than pre-checked.
        DraftRule insideTheGroup = new DraftRule(
                "/Subscription/Addons", "InsideRule", "../Name",
                "RepetitionNotUnique(/Subscription/Addons/Name)", "RNU_IN", "ERROR");
        ValidationResult r = new RuleValidator().check(modelJson, insideTheGroup);
        assertFalse(r.ok(), "RNU from inside the repeatable group must be rejected");
        assertTrue(r.problems().stream()
                        .anyMatch(p -> p.mvkCode().filter("MVK_REPEATABLE_GROUP_MISSING"::equals).isPresent()),
                () -> "expected MVK_REPEATABLE_GROUP_MISSING, got: " + r.problems());

        // ... while the same condition from the parent level validates (the canonical shape).
        DraftRule parentLevel = new DraftRule(
                "/Subscription", "ParentRule", "../Addons/Name",
                "RepetitionNotUnique(Addons/Name)", "RNU_OK", "ERROR");
        assertTrue(new RuleValidator().check(modelJson, parentLevel).ok(),
                "the parent-level canonical shape must validate");
    }

    @Test
    void rejectsATypeIllegalComparison() {
        // Ordering a string ([Name]) is a type error — MVK_INVALID_TYPE_FOR_COMPARISON.
        DraftRule rule = new DraftRule(
                "/Subscription/Addons", "BadComparisonRule", "../Name",
                "FieldFilled(Name) And [Name] <= [MonthlyFee]", "BAD_CMP", "ERROR");

        ValidationResult result = new RuleValidator().check(modelJson, rule);

        assertFalse(result.ok(), "string ordering should be rejected");
        assertTrue(
                result.problems().stream()
                        .anyMatch(p -> p.mvkCode().filter("MVK_INVALID_TYPE_FOR_COMPARISON"::equals).isPresent()),
                () -> "expected MVK_INVALID_TYPE_FOR_COMPARISON, got: " + result.problems());
    }
}

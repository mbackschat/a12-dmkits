package io.github.mbackschat.a12.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.rulekit.model.ModelIndex;
import io.github.mbackschat.a12.rulekit.model.ModelLoader;

/**
 * First vertical slice: typed, model-bound handles compose a {@link Condition} AST that the
 * catalog-driven renderer turns into canonical DSL text (proposal §6/§10). Paths render in absolute
 * form for now; the relative/short path policy + rule-group context (§18 fork 9, F3a) come later.
 */
class RenderConditionTest {

    private static final String MONTHLY_FEE = "/Subscription/Addons/MonthlyFee";
    private static final String NAME = "/Subscription/Addons/Name";

    private static BoundModel m;

    @BeforeAll
    static void bind() throws Exception {
        try (Reader reader = fixture("models/subscription.dm.json")) {
            m = BoundModel.of(ModelIndex.of(ModelLoader.loadSelfContained(reader)));
        }
    }

    @Test
    void rendersConjunctionInEnglish() {
        Condition c = m.num(MONTHLY_FEE).filled().and(m.num(MONTHLY_FEE).le(0));
        assertEquals(
                "FieldFilled(/Subscription/Addons/MonthlyFee) And [/Subscription/Addons/MonthlyFee] <= 0",
                new DslRenderer().render(c));
    }

    @Test
    void rendersSameAstInGermanFromTheCatalog() {
        Condition c = m.num(MONTHLY_FEE).filled().and(m.num(MONTHLY_FEE).le(0));
        assertEquals(
                "FeldAngegeben(/Subscription/Addons/MonthlyFee) Und [/Subscription/Addons/MonthlyFee] <= 0",
                new DslRenderer(ConditionLanguage.DE).render(c));
    }

    @Test
    void parenthesizesWhenMixingAndWithOr() {
        Condition c = m.field(NAME).filled()
                .and(m.num(MONTHLY_FEE).le(0).or(m.num(MONTHLY_FEE).gt(100)));
        assertEquals(
                "FieldFilled(/Subscription/Addons/Name) And "
                        + "([/Subscription/Addons/MonthlyFee] <= 0 Or [/Subscription/Addons/MonthlyFee] > 100)",
                new DslRenderer().render(c));
    }

    @Test
    void typedNumberHandleRejectsAStringField() {
        assertThrows(IllegalArgumentException.class, () -> m.num(NAME));
    }

    @Test
    void rendersStringEqualityWithAQuotedLiteral() {
        Condition c = m.str(NAME).eq("ACME");
        assertEquals("[/Subscription/Addons/Name] == \"ACME\"", new DslRenderer().render(c));
    }

    @Test
    void rendersScaleExplicitLiteralsFromStringAndBigDecimalOverloads() {
        // Scale-aware ergonomics (study finding 2): the written scale is preserved exactly.
        // Literals are scale-flexible at the kernel (verified — see RuleValidatorTest), so these
        // overloads are about faithful rendering, not about dodging MVK_INVALID_COMPARE_DEC_PLACES.
        assertEquals("[/Subscription/Addons/MonthlyFee] == 0.00",
                new DslRenderer().render(m.num(MONTHLY_FEE).eq("0.00")));
        assertEquals("[/Subscription/Addons/MonthlyFee] >= 19.90",
                new DslRenderer().render(m.num(MONTHLY_FEE).ge(new java.math.BigDecimal("19.90"))));
        assertEquals("Length(/Subscription/Addons/Name) <= 10.5",
                new DslRenderer().render(m.str(NAME).length().le("10.5")));
    }

    @Test
    void rendersLengthFunctionBilingually() {
        Condition c = m.str(NAME).length().gt(10);
        assertEquals("Length(/Subscription/Addons/Name) > 10", new DslRenderer().render(c));
        assertEquals("Laenge(/Subscription/Addons/Name) > 10", new DslRenderer(ConditionLanguage.DE).render(c));
    }

    @Test
    void typedStringHandleRejectsANumberField() {
        assertThrows(IllegalArgumentException.class, () -> m.str(MONTHLY_FEE));
    }

    @Test
    void rendersPatternComparisonBilingually() {
        Condition matched = m.str(NAME).matches("[A-Z].*");
        assertEquals("[/Subscription/Addons/Name] PatternMatched \"[A-Z].*\"",
                new DslRenderer().render(matched));
        assertEquals("[/Subscription/Addons/Name] WieMuster \"[A-Z].*\"",
                new DslRenderer(ConditionLanguage.DE).render(matched));

        Condition violated = m.str("/Subscription/Billing/InvoiceEmail").violates(".+@.+");
        assertEquals("[/Subscription/Billing/InvoiceEmail] PatternViolated \".+@.+\"",
                new DslRenderer().render(violated));
        assertEquals("[/Subscription/Billing/InvoiceEmail] NichtWieMuster \".+@.+\"",
                new DslRenderer(ConditionLanguage.DE).render(violated));
    }

    @Test
    void patternHandleFailsFastOnAJavaInvalidRegex() {
        // Necessary-condition pre-check for MVK_INVALID_PATTERN: anything Java's Pattern rejects,
        // the kernel rejects too. The kernel's *portable-subset* gate (e.g. lookbehind is rejected
        // although Java-valid) stays kernel-delegated — F4/N7 has the final word.
        assertThrows(IllegalArgumentException.class, () -> m.str(NAME).matches("("));
        assertThrows(IllegalArgumentException.class, () -> m.str(NAME).violates("["));
    }

    @Test
    void quotesStringLiteralsTheKernelWay() {
        // Kernel lexer (verified 2026-06-04): StringLiteral : '"' ( '""' | ~["] )* '"' — an embedded
        // quote is DOUBLED, and backslash is a plain character (no escape sequences).
        assertEquals("[/Subscription/Addons/Name] == \"say \"\"hi\"\"\"",
                new DslRenderer().render(m.str(NAME).eq("say \"hi\"")));
        assertEquals("[/Subscription/Billing/InvoiceEmail] PatternMatched \"\\d{5}\"",
                new DslRenderer().render(m.str("/Subscription/Billing/InvoiceEmail").matches("\\d{5}")));
    }

    @Test
    void rendersValueListMembershipBilingually() {
        Condition in = m.str(NAME).in("Backup", "Support");
        assertEquals("FieldValueIncludedInValueList(/Subscription/Addons/Name, \"Backup\", \"Support\")",
                new DslRenderer().render(in));
        assertEquals("FeldWertInWerteListe(/Subscription/Addons/Name, \"Backup\", \"Support\")",
                new DslRenderer(ConditionLanguage.DE).render(in));
        assertEquals("FieldValueNotIncludedInValueList(/Subscription/Addons/Name, \"Backup\")",
                new DslRenderer().render(m.str(NAME).notIn("Backup")));
    }

    @Test
    void numberValueListsAreIntegerOnlyBySignature() {
        // Kernel grammar (verified 2026-06-04): value-list numbers are '-'? digits — a decimal
        // literal is a parse error. The long... signature makes that unrepresentable.
        Condition c = m.num(MONTHLY_FEE).in(0, -10, 20);
        assertEquals("FieldValueIncludedInValueList(/Subscription/Addons/MonthlyFee, 0, -10, 20)",
                new DslRenderer().render(c));
    }

    @Test
    void enumValueListsPreCheckTheDomain() {
        Condition c = m.enumOf("/Subscription/Tier").in("PRO", "ENTERPRISE");
        assertEquals("FieldValueIncludedInValueList(/Subscription/Tier, \"PRO\", \"ENTERPRISE\")",
                new DslRenderer().render(c));
        // outside the domain → fails fast (MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION pre-checked)
        assertThrows(IllegalArgumentException.class, () -> m.enumOf("/Subscription/Tier").in("PRO", "GOLD"));
    }

    @Test
    void rendersFieldListValueListQuantifiers() {
        Condition c = Dsl.noFieldValueIncludedInValueList(java.util.List.of("test", "demo"),
                m.str(NAME), m.str("/Subscription/Billing/InvoiceEmail"));
        assertEquals("NoFieldValueIncludedInValueList(/Subscription/Addons/Name,"
                + " /Subscription/Billing/InvoiceEmail In \"test\", \"demo\")",
                new DslRenderer().render(c));
        assertEquals("KeinFeldWertInWerteListe(/Subscription/Addons/Name,"
                + " /Subscription/Billing/InvoiceEmail In \"test\", \"demo\")",
                new DslRenderer(ConditionLanguage.DE).render(c));

        Condition one = Dsl.atLeastOneFieldValueIncludedInValueList(java.util.List.of("PRO"),
                m.enumOf("/Subscription/Tier"));
        assertEquals("AtLeastOneFieldValueIncludedInValueList(/Subscription/Tier In \"PRO\")",
                new DslRenderer().render(one));
    }

    @Test
    void rendersFilledCountAggregatesBilingually() {
        // ≥2 fields by signature (MVK_PARAMSIZE_INVALIDN erased); mixed kinds are legal
        Condition c = Dsl.numberOfFilledFields(
                m.field("/Subscription/Tier"), m.field("/Subscription/AutoRenew")).lt(2);
        assertEquals("NumberOfFilledFields(/Subscription/Tier, /Subscription/AutoRenew) < 2",
                new DslRenderer().render(c));
        assertEquals("AnzahlAngegebenerFelder(/Subscription/Tier, /Subscription/AutoRenew) < 2",
                new DslRenderer(ConditionLanguage.DE).render(c));

        // the single-GROUP form counts filled fields inside the group
        assertEquals("NumberOfFilledFields(/Subscription/Billing) < 3",
                new DslRenderer().render(Dsl.numberOfFilledFields(m.group("/Subscription/Billing")).lt(3)));

        // NumberOfFilledGroups needs ≥2 groups (MVK_PARAMSIZE_INVALIDGN erased by signature)
        Condition g = Dsl.numberOfFilledGroups(
                m.group("/Subscription/Addons"), m.group("/Subscription/Billing")).eq(2);
        assertEquals("NumberOfFilledGroups(/Subscription/Addons, /Subscription/Billing) == 2",
                new DslRenderer().render(g));
        assertEquals("AnzahlAngegebenerKontexte(/Subscription/Addons, /Subscription/Billing) == 2",
                new DslRenderer(ConditionLanguage.DE).render(g));
    }

    @Test
    void filledCountAggregatesRejectRootGroups() {
        // Verified 2026-06-04: a root group draws MVK_ROOT_GROUP_REFERENCED — pre-checked here.
        assertThrows(IllegalArgumentException.class,
                () -> Dsl.numberOfFilledFields(m.group("/Subscription")));
        assertThrows(IllegalArgumentException.class,
                () -> Dsl.numberOfFilledGroups(m.group("/Subscription"), m.group("/Subscription/Addons")));
    }

    @Test
    void rendersFlattenedRepetitionLists() {
        // '*' is path syntax (language-independent), legal only in LIST slots — quantifiers,
        // shape-B value lists, count aggregates. Scalar slots take FieldRef only, so
        // MVK_NO_WILDCARDS_ALLOWED is erased by the FieldListRef/FieldRef type split.
        FieldListRef names = m.flatten("/Subscription/Addons*/Name");
        Condition c = Dsl.atLeastOneFieldFilled(names);
        assertEquals("AtLeastOneFieldFilled(/Subscription/Addons*/Name)", new DslRenderer().render(c));
        assertEquals("MindestensEinFeldAngegeben(/Subscription/Addons*/Name)",
                new DslRenderer(ConditionLanguage.DE).render(c));

        // mixed plain + starred operands are kernel-legal in one predicate
        Condition mixed = Dsl.atLeastOneFieldFilled(m.field("/Subscription/Tier"), names);
        assertEquals("AtLeastOneFieldFilled(/Subscription/Tier, /Subscription/Addons*/Name)",
                new DslRenderer().render(mixed));

        // count aggregate over a repetition list
        assertEquals("NumberOfFilledFields(/Subscription/Addons*/Name, /Subscription/Addons*/MonthlyFee) > 0",
                new DslRenderer().render(Dsl.numberOfFilledFields(
                        names, m.flatten("/Subscription/Addons*/MonthlyFee")).gt(0)));

        // shape-B value list over a repetition list
        assertEquals("NoFieldValueIncludedInValueList(/Subscription/Addons*/Name In \"test\")",
                new DslRenderer().render(Dsl.noFieldValueIncludedInValueList(
                        java.util.List.of("test"), names)));
    }

    @Test
    void flattenValidatesTheStarPlacement() {
        // a star on a non-repeatable group is MVK_INVALID_WILDCARD — pre-checked at flatten()
        assertThrows(IllegalArgumentException.class, () -> m.flatten("/Subscription/Billing*/BaseFee"));
        // the base path must resolve to a field
        assertThrows(IllegalArgumentException.class, () -> m.flatten("/Subscription/Addons*/Nope"));
        // a flatten path without any star is just a field path — reject, the caller wants m.field()
        assertThrows(IllegalArgumentException.class, () -> m.flatten("/Subscription/Addons/Name"));
    }

    @Test
    void rendersEnumComparisonByStoredValue() {
        Condition c = m.enumOf("/Subscription/Tier").is("PRO");
        assertEquals("[/Subscription/Tier] == \"PRO\"", new DslRenderer().render(c));
    }

    @Test
    void rendersEnumCategoryNavigation() {
        // Corpus item 3: [Field -> Category] compares against the CATEGORY's value domain.
        Condition c = m.enumOf("/Subscription/Tier").category("Pricing").is("PAID");
        assertEquals("[/Subscription/Tier -> Pricing] == \"PAID\"", new DslRenderer().render(c));
        // the arrow is symbolic — identical in German:
        assertEquals("[/Subscription/Tier -> Pricing] != \"FREE\"",
                new DslRenderer(ConditionLanguage.DE).render(
                        m.enumOf("/Subscription/Tier").category("Pricing").isNot("FREE")));
    }

    @Test
    void categoryHandleErasesTheVerifiedCategoryMistakes() {
        EnumRef tier = m.enumOf("/Subscription/Tier");
        // unknown category — MVK_CATEGORY_UNKOWN erased at handle creation:
        assertThrows(IllegalArgumentException.class, () -> tier.category("Nope"));
        // enum value instead of category value — MVK_INVALID_STRING_CONSTANT_FOR_ENUM_COMPARISON erased:
        assertThrows(IllegalArgumentException.class, () -> tier.category("Pricing").is("BASIC"));
        // -> on a non-enum is unrepresentable: only EnumRef has category() (MVK_NO_ENUMERATION erased)
    }

    @Test
    void enumHandleRejectsAValueOutsideTheModelDomain() {
        assertThrows(IllegalArgumentException.class, () -> m.enumOf("/Subscription/Tier").is("PLATINUM"));
    }

    @Test
    void typedEnumHandleRejectsANonEnumField() {
        assertThrows(IllegalArgumentException.class, () -> m.enumOf(MONTHLY_FEE));
    }

    @Test
    void rendersBooleanComparisonBilingually() {
        // Corpus backlog item 1: [Bool] == True is the most frequent gating pattern.
        Condition c = m.bool("/Subscription/AutoRenew").isTrue();
        assertEquals("[/Subscription/AutoRenew] == True", new DslRenderer().render(c));
        assertEquals("[/Subscription/AutoRenew] == Wahr", new DslRenderer(ConditionLanguage.DE).render(c));

        assertEquals("[/Subscription/AutoRenew] == False",
                new DslRenderer().render(m.bool("/Subscription/AutoRenew").isFalse()));
        assertEquals("[/Subscription/AutoRenew] == Falsch",
                new DslRenderer(ConditionLanguage.DE).render(m.bool("/Subscription/AutoRenew").isFalse()));
    }

    @Test
    void confirmHandleOffersOnlyKernelLegalComparisons() {
        // Confirm compares only to True (MVK_INVALID_COMPARE_TO_YES otherwise — verified, see
        // KERNEL-INTEGRATION-NOTES): the handle has confirmed()/notConfirmed(), no isFalse().
        assertEquals("[/Subscription/TermsAccepted] == True",
                new DslRenderer().render(m.confirm("/Subscription/TermsAccepted").confirmed()));
        assertEquals("[/Subscription/TermsAccepted] != True",
                new DslRenderer().render(m.confirm("/Subscription/TermsAccepted").notConfirmed()));
    }

    @Test
    void typedBoolAndConfirmHandlesRejectWrongKinds() {
        assertThrows(IllegalArgumentException.class, () -> m.bool(NAME));
        assertThrows(IllegalArgumentException.class, () -> m.confirm("/Subscription/AutoRenew"));
        assertThrows(IllegalArgumentException.class, () -> m.bool("/Subscription/TermsAccepted"));
    }

    @Test
    void rendersFieldListQuantifierBilingually() {
        Condition c = m.allFieldsFilled(NAME, MONTHLY_FEE);
        assertEquals("AllFieldsFilled(/Subscription/Addons/Name, /Subscription/Addons/MonthlyFee)",
                new DslRenderer().render(c));
        assertEquals("AlleFelderAngegeben(/Subscription/Addons/Name, /Subscription/Addons/MonthlyFee)",
                new DslRenderer(ConditionLanguage.DE).render(c));
    }

    @Test
    void fieldListQuantifierValidatesEveryPath() {
        assertThrows(IllegalArgumentException.class, () -> m.allFieldsFilled(NAME, "/Subscription/Addons/Nope"));
    }

    @Test
    void rendersGroupPresenceBilingually() {
        // Corpus backlog item 2: GroupFilled is the highest-frequency previously-uncovered construct.
        Condition c = m.group("/Subscription/Addons").filled();
        assertEquals("GroupFilled(/Subscription/Addons)", new DslRenderer().render(c));
        assertEquals("KontextAngegeben(/Subscription/Addons)", new DslRenderer(ConditionLanguage.DE).render(c));

        assertEquals("GroupNotFilled(/Subscription/Billing)",
                new DslRenderer().render(m.group("/Subscription/Billing").notFilled()));
    }

    @Test
    void rendersGroupListQuantifierBilingually() {
        Condition c = Dsl.atLeastOneGroupFilled(m.group("/Subscription/Addons"), m.group("/Subscription/Billing"));
        assertEquals("AtLeastOneGroupFilled(/Subscription/Addons, /Subscription/Billing)",
                new DslRenderer().render(c));
        assertEquals("MindestensEinKontextAngegeben(/Subscription/Addons, /Subscription/Billing)",
                new DslRenderer(ConditionLanguage.DE).render(c));
    }

    @Test
    void groupHandleRejectsAFieldPathAndViceVersa() {
        // GroupFilled(field) is MVK_NO_FIELDS_ALLOWED, FieldFilled(group) is MVK_NO_GROUPS_ALLOWED
        // (verified — see KERNEL-INTEGRATION-NOTES): the typed handles erase both classes.
        assertThrows(IllegalArgumentException.class, () -> m.group(MONTHLY_FEE));
        assertThrows(IllegalArgumentException.class, () -> m.field("/Subscription/Addons"));
    }

    @Test
    void groupHandleKnowsRepeatability() {
        assertEquals(true, m.group("/Subscription/Addons").repeating());
        assertEquals(false, m.group("/Subscription/Billing").repeating());
    }

    private static Reader fixture(String classpathLocation) {
        InputStream in = RenderConditionTest.class.getClassLoader().getResourceAsStream(classpathLocation);
        assertNotNull(in, "fixture not found: " + classpathLocation);
        return new InputStreamReader(in, StandardCharsets.UTF_8);
    }
}

package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.edit.GroupEditor;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;

/**
 * A category read of a key-selected row, {@code [Lines/Grade -> Tier For "a"]}: the category before the key, the
 * order A12's {@code entitySpec} takes them in (KERNEL-FINDINGS KF568; DEV-API-GAPS DG137). A category named like
 * a keyword is quoted in a condition, as a field is.
 *
 * <p>The kernel decides every row and each verdict is pinned. Own domain (an order's lines).
 */
class KeyedCategoryTest {

    private static final String ORDER = DocumentModelDraft.named("order-keyed-category").locale("en_US")
            .rootGroup("Order", o -> o
                    .numberField("Total", n -> n.fractionDigits(0))
                    .stringField("Pick", f -> { })
                    .enumField("Status", e -> e.value("OPEN").value("SHIPPED").category("Tier", "High", "Low"))
                    .group("Lines", l -> l.repeatable(5).indexField("Sku")
                            .stringField("Sku", f -> { })
                            .enumField("Grade", e -> e.value("OPEN").value("SHIPPED")
                                    .category("Tier", "High", "Low").category("For", "A", "B"))))
            .build().toJson();

    private final GroupEditor order = EditSession.read(ORDER).group("/Order");
    private final NumberRef total = order.num("Total");
    private final CategoryRef tier = order.enumOf("Lines/Grade").category("Tier");

    private Rule rule(Condition condition) {
        return order.rule(r -> r.name("TierCheck").code("TIER_CHECK").errorField(total)
                .when(total.filled().and(condition)).message(Dsl.en("Check the tier.")).build());
    }

    private static String verdict(Rule rule) {
        var result = new RuleValidator().check(ORDER, rule.draft());
        return result.ok() ? "admitted" : result.diagnostics().stream()
                .map(d -> d.code().orElse("(no code)")).collect(Collectors.joining(" + "));
    }

    private static String verdict(String condition) {
        var result = new RuleValidator().check(ORDER, new io.github.mbackschat.a12.dm.rulekit.validate.DraftRule(
                "/Order", "TierCheck", "../Total", "FieldFilled(Total) And " + condition, "TIER_CHECK", "ERROR"));
        return result.ok() ? "admitted" : result.diagnostics().stream()
                .map(d -> d.code().orElse("(no code)")).collect(Collectors.joining(" + "));
    }

    @Test
    void aKeyedCategoryRendersTheCategoryBeforeTheKeyAndA12AdmitsIt() {
        Rule literal = rule(tier.forIndex("a").is("High"));
        Rule field = rule(tier.forIndex(order.field("Pick")).is("High"));
        Rule firstFilled = rule(Dsl.firstFilledString(tier.forIndex("a").firstFilledOperand(),
                order.enumOf("Status").category("Tier").firstFilledOperand()).eq("High"));

        assertEquals("FieldFilled(Total) And [Lines/Grade -> Tier For \"a\"] == \"High\"", literal.render());
        assertEquals("FieldFilled(Total) And [Lines/Grade -> Tier For Pick] == \"High\"", field.render());
        assertEquals("FieldFilled(Total) And FirstFilledValue(Lines/Grade -> Tier For \"a\", Status -> Tier) == \"High\"",
                firstFilled.render());
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("literal key", verdict(literal));
        actual.put("field key", verdict(field));
        actual.put("first filled", verdict(firstFilled));
        assertEquals(Map.of("literal key", "admitted", "field key", "admitted", "first filled", "admitted"), actual);
    }

    @Test
    void aFieldKeyReferencesTheErrorFieldAsA12CountsIt() {
        StringRef pick = order.str("Pick");
        Rule keyedOnly = order.rule(r -> r.name("PickTier").code("PICK_TIER").errorField(pick)
                .when(tier.forIndex(pick.field()).is("High")).message(Dsl.en("Check the picked tier.")).build());
        boolean advises = keyedOnly.deferredChecks().stream()
                .anyMatch(d -> d.code().filter("MVK_ERROR_FIELD_NOT_REFERENCED"::equals).isPresent());
        assertEquals(Map.of("kernel", "admitted", "advisory", false),
                Map.of("kernel", verdict(keyedOnly), "advisory", advises));
    }

    /**
     * A correlated key, {@code For $Pick}, is refused wherever a keyed category can stand, so it never meets its
     * uncorrelated twin in one operand list. That is the measured reason folding the key's {@code $} into the
     * duplicate identity changes no verdict. The fold is kept anyway, in the one spelling
     * {@link FirstFilledOperand} shares, because leaving it out would be broader than the kernel if the shape ever
     * became authorable. The literal key inside the filter is the control: A12 refuses any key there, not only
     * the correlated one.
     */
    @Test
    void aCorrelatedKeyIsRefusedWhereverAKeyedCategoryStands() {
        FieldRef pick = order.field("Pick");
        String inFilter = "NumberOfFilledFields(Lines*/Sku Having [Lines/Sku] == [$Pick] And %s) > 0";
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("plain locus", verdict(rule(tier.forIndex(pick.outer()).is("High"))));
        actual.put("plain value list beside its twin", verdict("AtLeastOneFieldValueIncludedInValueList("
                + "Lines/Grade -> Tier For $Pick, Lines/Grade -> Tier For Pick In \"High\")"));
        actual.put("filter", verdict(inFilter.formatted("[Lines/Grade -> Tier For $Pick] == \"High\"")));
        actual.put("filter, literal key", verdict(inFilter.formatted("[Lines/Grade -> Tier For \"a\"] == \"High\"")));
        actual.put("filter value list beside its twin", verdict(inFilter.formatted(
                "AtLeastOneFieldValueIncludedInValueList(Lines/Grade -> Tier For $Pick, Lines/Grade -> Tier For Pick In \"High\")")));
        assertEquals(Map.of("plain locus", "MVK_INVALID_OUTER_ITERATION",
                "plain value list beside its twin", "MVK_INVALID_OUTER_ITERATION",
                "filter", "MVK_SEMANTIC_INDEX_NOT_ALLOWED", "filter, literal key", "MVK_SEMANTIC_INDEX_NOT_ALLOWED",
                "filter value list beside its twin", "MVK_SEMANTIC_INDEX_NOT_ALLOWED"), actual);

        EnumCategoryAccess correlated = tier.forIndex(pick.outer()).access();
        EnumCategoryAccess plain = tier.forIndex(pick).access();
        assertEquals(List.of(false, false), List.of(correlated.exactIdentity().equals(plain.exactIdentity()),
                correlated.overlapModifier().equals(plain.overlapModifier())),
                "the key's $ is part of both identities, as on a first-filled operand");
    }

    @Test
    void aSecondKeyIsRefusedAsA12RefusesIt() {
        assertThrows(IllegalStateException.class, () -> tier.forIndex("a").forIndex("b"));
        assertEquals("MVK_UNEXPECTED_TOKEN", verdict("[Lines/Grade -> Tier For \"a\" For \"b\"] == \"High\""));
    }

    @Test
    void aCategoryNamedLikeAKeywordIsQuotedInTheCondition() {
        CategoryRef keyword = order.enumOf("Lines/Grade").category("For");
        Rule quoted = rule(keyword.forIndex("a").is("A"));
        assertEquals("FieldFilled(Total) And [Lines/Grade -> 'For' For \"a\"] == \"A\"", quoted.render());

        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("quoted", verdict(quoted));
        actual.put("unquoted", verdict("[Lines/Grade -> For For \"a\"] == \"A\""));
        actual.put("quoted ordinary", verdict("[Lines/Grade -> 'Tier' For \"a\"] == \"High\""));
        assertEquals(Map.of("quoted", "admitted", "unquoted", "MVK_UNEXPECTED_TOKEN", "quoted ordinary", "admitted"),
                actual);
    }
}

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
 * A keyed category keeps its key in every role it plays, not only in a comparison and a first-filled operand
 * ({@code KeyedCategoryTest}): a value-list member, a concatenation, {@code FieldValueAsNumber}, and a category added
 * to a first-filled operand (KERNEL-FINDINGS KF568). {@code FieldValueAsNumber} rebuilt the projection from the field
 * and the name, so it dropped the key, left a field key unreferenced and a keyword-named category unquoted; on a
 * repeating field the bare projection is A12's {@code MVK_NO_WILDCARD} (2026-10-25 cold review, R10).
 *
 * <p>The kernel decides every row and each verdict is pinned. Own domain (an order's lines).
 */
class KeyedCategoryRoleTest {

    private static final String ORDER = DocumentModelDraft.named("order-keyed-category-roles").locale("en_US")
            .rootGroup("Order", o -> o
                    .numberField("Total", n -> n.fractionDigits(0))
                    .stringField("Pick", f -> { })
                    .group("Lines", l -> l.repeatable(5).indexField("Sku")
                            .stringField("Sku", f -> { })
                            .enumField("Grade", e -> e.value("OPEN").value("SHIPPED").category("Tier", "High", "Low"))
                            .enumField("Band", e -> e.value("B1").value("B2")
                                    .category("Rank", "1", "2").category("For", "3", "4"))
                            .enumField("Qty", e -> e.value("1").value("2"))))
            .build().toJson();

    private final GroupEditor order = EditSession.read(ORDER).group("/Order");
    private final NumberRef total = order.num("Total");
    private final CategoryRef tier = order.enumOf("Lines/Grade").category("Tier");
    private final CategoryRef rank = order.enumOf("Lines/Band").category("Rank");

    private Rule rule(Condition condition) {
        return order.rule(r -> r.name("TierCheck").code("TIER_CHECK").errorField(total)
                .when(total.filled().and(condition)).message(Dsl.en("Check the tier.")).build());
    }

    private static String verdict(Rule rule) {
        var result = new RuleValidator().check(ORDER, rule.draft());
        return result.ok() ? "admitted" : result.diagnostics().stream()
                .map(d -> d.code().orElse("(no code)")).collect(Collectors.joining(" + "));
    }

    @Test
    void everyRoleRendersTheKeyAndA12AdmitsIt() {
        Map<String, Rule> rules = new LinkedHashMap<>();
        rules.put("value list", rule(Dsl.atLeastOneFieldValueIncludedInValueList(List.of("High"), tier.forIndex("a"))));
        rules.put("concatenation", rule(order.str("Pick").asString().eq(tier.forIndex("a").plus("-x"))));
        rules.put("as number", rule(Dsl.fieldValueAsNumber(rank.forIndex("a")).gt(1)));
        rules.put("as number, field key", rule(Dsl.fieldValueAsNumber(rank.forIndex(order.field("Pick"))).gt(1)));
        rules.put("as number, keyed field", rule(Dsl.fieldValueAsNumber(order.field("Lines/Qty").semanticIndex("a")).gt(1)));
        rules.put("as number, keyword category", rule(Dsl.fieldValueAsNumber(
                order.enumOf("Lines/Band").category("For").forIndex("a")).gt(3)));

        Map<String, String> rendered = new LinkedHashMap<>();
        Map<String, String> verdicts = new LinkedHashMap<>();
        rules.forEach((name, rule) -> {
            rendered.put(name, rule.render());
            verdicts.put(name, verdict(rule));
        });
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("value list", "FieldFilled(Total) And AtLeastOneFieldValueIncludedInValueList(Lines/Grade -> Tier For \"a\" In \"High\")");
        expected.put("concatenation", "FieldFilled(Total) And [Pick] == [Lines/Grade -> Tier For \"a\"] + \"-x\"");
        expected.put("as number", "FieldFilled(Total) And FieldValueAsNumber(Lines/Band -> Rank For \"a\") > 1");
        expected.put("as number, field key", "FieldFilled(Total) And FieldValueAsNumber(Lines/Band -> Rank For Pick) > 1");
        expected.put("as number, keyed field", "FieldFilled(Total) And FieldValueAsNumber(Lines/Qty For \"a\") > 1");
        expected.put("as number, keyword category",
                "FieldFilled(Total) And FieldValueAsNumber(Lines/Band -> 'For' For \"a\") > 3");
        assertEquals(expected, rendered);
        assertEquals(Map.of("value list", "admitted", "concatenation", "admitted", "as number", "admitted",
                "as number, field key", "admitted", "as number, keyed field", "admitted",
                "as number, keyword category", "admitted"), verdicts);
    }

    /** The bare projection is what the key drop used to render: A12 asks a repeating read for its star. */
    @Test
    void theUnkeyedProjectionOnARepeatingFieldIsRefused() {
        assertEquals("MVK_NO_WILDCARD", verdict(rule(Dsl.fieldValueAsNumber(rank).gt(1))));
    }

    /** A12 counts the key as a reference of the error field, so the advisory stays silent, as in a comparison. */
    @Test
    void aFieldKeyInFieldValueAsNumberReferencesTheErrorField() {
        StringRef pick = order.str("Pick");
        Rule keyedOnly = order.rule(r -> r.name("PickRank").code("PICK_RANK").errorField(pick)
                .when(Dsl.fieldValueAsNumber(rank.forIndex(pick.field())).gt(1))
                .message(Dsl.en("Check the picked rank.")).build());
        boolean advises = keyedOnly.deferredChecks().stream()
                .anyMatch(d -> d.code().filter("MVK_ERROR_FIELD_NOT_REFERENCED"::equals).isPresent());
        assertEquals(Map.of("kernel", "admitted", "advisory", false),
                Map.of("kernel", verdict(keyedOnly), "advisory", advises));
    }

    /** The same for a keyed field, whose key the source-switch folds as an index rather than as a plain field. */
    @Test
    void aFieldKeyOfAKeyedFieldReferencesTheErrorField() {
        StringRef pick = order.str("Pick");
        Rule keyedOnly = order.rule(r -> r.name("PickQty").code("PICK_QTY").errorField(pick)
                .when(Dsl.fieldValueAsNumber(order.field("Lines/Qty").semanticIndex(pick.field())).gt(1))
                .message(Dsl.en("Check the picked quantity.")).build());
        boolean advises = keyedOnly.deferredChecks().stream()
                .anyMatch(d -> d.code().filter("MVK_ERROR_FIELD_NOT_REFERENCED"::equals).isPresent());
        assertEquals(Map.of("kernel", "admitted", "advisory", false),
                Map.of("kernel", verdict(keyedOnly), "advisory", advises));
    }

    @Test
    void aKeyedCategoryAddedToAFirstFilledOperandKeepsItsKey() {
        FirstFilledOperand operand = FirstFilledOperand.of(order.field("Lines/Grade")).withCategory(tier.forIndex("a"));
        Rule firstFilled = rule(Dsl.firstFilledString(operand, tier.forIndex("b").firstFilledOperand()).eq("High"));
        assertEquals("FieldFilled(Total) And FirstFilledValue(Lines/Grade -> Tier For \"a\", Lines/Grade -> Tier For \"b\")"
                + " == \"High\"", firstFilled.render());
        assertEquals("admitted", verdict(firstFilled));
        assertThrows(IllegalStateException.class,
                () -> FirstFilledOperand.of(order.field("Lines/Grade")).forIndex("b").withCategory(tier.forIndex("a")));
    }
}

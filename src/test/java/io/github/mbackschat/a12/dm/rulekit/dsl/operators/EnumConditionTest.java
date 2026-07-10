package io.github.mbackschat.a12.dm.rulekit.dsl.operators;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.*;

/** Enums (corpus item 3): comparison by stored value + category navigation — KERNEL-FINDINGS §8. */
class EnumConditionTest {

    private static final BoundModel m = Fixtures.subscription();

    /** Two enum fields of the <em>same</em> type (a shared typeDef) — for field-to-field comparison. */
    private static BoundModel approvals() {
        return BoundModel.ofJson(DocumentModelDraft.named("approvals").locale("en_US")
                .typeDef("Status", t -> t.enumeration(e -> e.value("DRAFT").value("FINAL")))
                .rootGroup("Approval", a -> a
                        .typedefField("Requested", "Status")
                        .typedefField("Granted", "Status"))
                .build().toJson());
    }

    @Test
    void rendersEnumComparisonByStoredValue() {
        Condition c = m.enumOf("/Subscription/Tier").is("PRO");
        assertEquals("[/Subscription/Tier] == \"PRO\"", new DslRenderer().render(c));
    }

    @Test
    void eqNeAreTheConsistentEqualityVerbsAlongsideIsIsNot() {
        // D1: eq/ne are the equality verbs shared with the number/string/date refs; enum/category keep
        // is/isNot as the domain-natural reading. Both render identically across every overload.
        DslRenderer r = new DslRenderer();
        EnumRef tier = m.enumOf("/Subscription/Tier");
        assertEquals(r.render(tier.is("PRO")), r.render(tier.eq("PRO")));
        assertEquals(r.render(tier.isNot("PRO")), r.render(tier.ne("PRO")));

        BoundModel a = approvals();
        EnumRef req = a.enumOf("/Approval/Requested");
        EnumRef granted = a.enumOf("/Approval/Granted");
        assertEquals(r.render(req.is(granted)), r.render(req.eq(granted)));
        assertEquals(r.render(req.isNot(granted)), r.render(req.ne(granted)));

        EnumExpr expr = tier.forIndex("K");   // an enum expression (semantic index) — render-only here
        assertEquals(r.render(tier.is(expr)), r.render(tier.eq(expr)));
        assertEquals(r.render(expr.is("PRO")), r.render(expr.eq("PRO")));
        assertEquals(r.render(expr.isNot("PRO")), r.render(expr.ne("PRO")));

        CategoryRef pricing = tier.category("Pricing");
        assertEquals(r.render(pricing.is("PAID")), r.render(pricing.eq("PAID")));
        assertEquals(r.render(pricing.isNot("PAID")), r.render(pricing.ne("PAID")));
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
    void rendersEnumFieldToFieldComparison() {
        BoundModel a = approvals();
        EnumRef requested = a.enumOf("/Approval/Requested");
        EnumRef granted = a.enumOf("/Approval/Granted");
        assertEquals("[/Approval/Requested] == [/Approval/Granted]", new DslRenderer().render(requested.is(granted)));
        assertEquals("[/Approval/Requested] != [/Approval/Granted]",
                new DslRenderer(ConditionLanguage.DE).render(requested.isNot(granted)));
    }

    @Test
    void enumHandleRejectsAValueOutsideTheModelDomain() {
        assertThrows(IllegalArgumentException.class, () -> m.enumOf("/Subscription/Tier").is("PLATINUM"));
    }

    @Test
    void typedEnumHandleRejectsANonEnumField() {
        assertThrows(IllegalArgumentException.class, () -> m.enumOf("/Subscription/Addons/MonthlyFee"));
    }
}

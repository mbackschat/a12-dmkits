package io.github.mbackschat.a12.dm.docsnippets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateExpr;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateTimeExpr;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr;
import io.github.mbackschat.a12.dm.rulekit.dsl.TimeExpr;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;

/**
 * The canonical, runnable snippets for the typed authoring shapes closed under DEV-API-GAPS §2 — each
 * tagged region is mirrored verbatim into {@code docs/API-GUIDE.md} and locked by
 * {@link io.github.mbackschat.a12.dm.DocSnippetFreshnessTest}. Authored here so the guide shows code that
 * genuinely compiles and renders against the real kernel (order fixture).
 */
class NewShapesSnippetsTest {

    private static final BoundModel m = Fixtures.order();

    @Test
    void dateMinMaxSnippet() {
        // tag::date-minmax[]
        // Min/Max preserve a homogeneous temporal family: DATE → DateExpr, TIME → TimeExpr,
        // DATETIME → DateTimeExpr. Mixing families does not compile.
        DateExpr earliestDate = Dsl.min(m.date("/Order/OrderDate"), m.date("/Order/DeliveryDate"));
        TimeExpr earliestTime = Dsl.min(m.time("/Order/PickupTime"), Dsl.time("09", "00", "00"));
        DateTimeExpr latestInstant = Dsl.max(m.dateTime("/Order/ScheduledAt"),
                m.dateTime("/Order/CompletedAt"));
        // end::date-minmax[]
        assertEquals("Min([/Order/OrderDate], [/Order/DeliveryDate])",
                new DslRenderer().render(earliestDate.expression()));
        assertEquals("Min([/Order/PickupTime], Time(\"09\", \"00\", \"00\"))",
                new DslRenderer().render(earliestTime.expression()));
        assertEquals("Max([/Order/ScheduledAt], [/Order/CompletedAt])",
                new DslRenderer().render(latestInstant.expression()));
    }

    @Test
    void fieldRoundingSnippet() {
        // tag::field-rounding[]
        // Expression and *Value forms both accept optional 0..14 places; omission means 0.
        NumberExpr wholeUnitPrice = Dsl.roundAccounting(m.num("/Order/UnitPrice").asNumber());
        NumberExpr roundedUnitPrice = Dsl.roundAccountingValue(m.num("/Order/UnitPrice"), 2);
        Condition unitPriceNeedsRounding = m.num("/Order/UnitPrice").ne(roundedUnitPrice);
        // end::field-rounding[]
        assertEquals("RoundAccounting([/Order/UnitPrice])", new DslRenderer().render(wholeUnitPrice.expression()));
        assertEquals("[/Order/UnitPrice] != RoundAccountingValue(/Order/UnitPrice, 2)",
                new DslRenderer().render(unitPriceNeedsRounding));
    }

    @Test
    void indexedPresenceSnippet() {
        // tag::indexed-presence[]
        // semanticIndex(key) is the presence handle for the field value a semantic index resolves to;
        // .filled()/.notFilled() test its presence. (The VALUE for a comparison is forIndex(key).)
        Condition present = m.num("/Order/Items/Count").semanticIndex("SKU-1").filled();
        // end::indexed-presence[]
        assertEquals("FieldFilled(/Order/Items/Count For \"SKU-1\")", new DslRenderer().render(present));
    }

    @Test
    void indexedCoercionSnippet() {
        // tag::indexed-coercion[]
        // fieldValueAsString also takes a semanticIndex(key) operand — the indexed field value coerced to
        // string, e.g. to splice a keyed number value into a concatenation (the corpus shape).
        Condition c = m.str("/Order/CustomerName").eq(
                Dsl.text("C-").plus(Dsl.fieldValueAsString(m.num("/Order/Items/Count").semanticIndex("SKU-1"))));
        // end::indexed-coercion[]
        assertEquals("[/Order/CustomerName] == \"C-\" + FieldValueAsString(/Order/Items/Count For \"SKU-1\")",
                new DslRenderer().render(c));
    }

    @Test
    void ruleGroupSnippet() {
        // tag::rule-group[]
        // Dsl.ruleGroup() is the rule's OWN group (the kernel's RegelKontext) — it renders the reserved
        // keyword, never a path. On a rule iterating a repeatable group it guards "is this row present".
        Condition attachment = Dsl.ruleGroup().filled()
                .and(Dsl.notExactlyOneFieldFilled(m.num("/Order/Items/Count").field(),
                        m.num("/Order/Items/UnitWeight").field()));
        // end::rule-group[]
        assertEquals("GroupFilled(RuleGroup) And NotExactlyOneFieldFilled(/Order/Items/Count, /Order/Items/UnitWeight)",
                new DslRenderer().render(attachment));
    }

    @Test
    void starredValueListSnippet() {
        // tag::starred-value-list[]
        // A value-list quantifier over a flattened repetition list — the starred FIELDS side.
        Condition anyForbidden = Dsl.atLeastOneFieldValueIncludedInValueList(
                List.of("A", "B"), m.flatten("/Order/Items*/Sku"));   // …(/Order/Items*/Sku In "A", "B")
        // end::starred-value-list[]
        assertEquals("AtLeastOneFieldValueIncludedInValueList(/Order/Items*/Sku In \"A\", \"B\")",
                new DslRenderer().render(anyForbidden));
    }

    @Test
    void enumValueListUnionSnippet() {
        // tag::enum-value-list-union[]
        // An enum value-list quantifier member may be a direct reference OR a category projection
        // [field -> Cat]; each literal rides the UNION of the selected members' domains — "STANDARD" from
        // Priority's stored values, "FAST" from the SpeedClass category (KERNEL-FINDINGS §8).
        Condition standardOrFast = Dsl.atLeastOneFieldValueIncludedInValueList(
                List.of("STANDARD", "FAST"),
                m.enumOf("/Order/Priority"),
                m.enumOf("/Order/Priority").category("SpeedClass"));
        // end::enum-value-list-union[]
        assertEquals("AtLeastOneFieldValueIncludedInValueList(/Order/Priority,"
                + " /Order/Priority -> SpeedClass In \"STANDARD\", \"FAST\")",
                new DslRenderer().render(standardOrFast));
    }

    @Test
    void computationSuppressionSnippet() {
        // tag::computation-suppress[]
        // errorCodesToSuppress — the kernel's model-level escape hatch (domain-checked): it lets a
        // scale-mismatched operation load (the static assignment-scale gate, MVK_INVALID_COMPARE_DEC_PLACES).
        NumberExpr product = m.num("/Order/UnitPrice").asNumber().times(m.num("/Order/UnitPrice").asNumber());
        Computation comp = m.computation(m.num("/Order/BackorderQuantity"))
                .name("BackorderProjection")
                .calc(product.expression())              // scale-4 product into a scale-0 target
                .errorCodesToSuppress("MVK_INVALID_COMPARE_DEC_PLACES")
                .message(Dsl.en("Backorder derived from the unit price."))
                .build();
        // end::computation-suppress[]
        assertTrue(new RuleValidator().check(Fixtures.orderJson(), comp.draft()).ok());
    }
}

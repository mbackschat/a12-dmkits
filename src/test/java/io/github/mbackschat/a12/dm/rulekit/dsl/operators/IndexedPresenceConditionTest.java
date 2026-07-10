package io.github.mbackschat.a12.dm.rulekit.dsl.operators;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.*;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * Presence over a semantic index (DEV-API-GAPS shape (2); KERNEL-FINDINGS §9 presence / IF59):
 * {@code FieldFilled([Count For "SKU-1"])} resolves the indexed cell and tests its presence. The
 * {@code forIndex(...)} value expression grows {@code .filled()}/{@code .notFilled()}; the presence
 * renders the semantic index BARE (no value brackets). Stored form pinned by the interpreter
 * differential {@code SemanticIndexPresenceDiffTest}; this proves the typed authoring renders to it and
 * the real kernel accepts it.
 */
class IndexedPresenceConditionTest {

    private static final BoundModel m = Fixtures.order();
    private static final String COUNT = "/Order/Items/Count";
    private static final String PC = "/Order/ProductCode";

    @Test
    void literalKeyedPresenceRendersBare() {
        // pinned by SemanticIndexPresenceDiffTest: `FieldFilled($COUNT For "SKU-1")`
        Condition filled = m.num(COUNT).forIndex("SKU-1").filled();
        assertEquals("FieldFilled(/Order/Items/Count For \"SKU-1\")", new DslRenderer().render(filled));
        Condition notFilled = m.num(COUNT).forIndex("SKU-1").notFilled();
        assertEquals("FieldNotFilled(/Order/Items/Count For \"SKU-1\")", new DslRenderer().render(notFilled));
        // DE: the "For" keyword localizes to "Fuer"; FieldFilled → FeldAngegeben
        assertEquals("FeldAngegeben(/Order/Items/Count Fuer \"SKU-1\")",
                new DslRenderer(ConditionLanguage.DE).render(filled));
    }

    @Test
    void fieldKeyedPresenceRendersBare() {
        // pinned by SemanticIndexPresenceDiffTest: `FieldFilled($COUNT For ProductCode)`
        Condition c = m.num(COUNT).forIndex(m.field(PC)).filled();
        assertEquals("FieldFilled(/Order/Items/Count For /Order/ProductCode)", new DslRenderer().render(c));
    }

    @Test
    void presenceWorksAcrossKinds() {
        // forIndex is kind-agnostic, so presence is too — a string-cell index reads the same way
        Condition c = m.str("/Order/Items/Sku").forIndex("SKU-1").notFilled();
        assertEquals("FieldNotFilled(/Order/Items/Sku For \"SKU-1\")", new DslRenderer().render(c));
    }

    /** A repeatable line with a cell of each remaining indexable kind (date/time/dateTime/enum). */
    private static final BoundModel cart = BoundModel.ofJson(DocumentModelDraft.named("cart").locale("en_US")
            .rootGroup("Cart", c -> c.group("Lines", l -> {
                l.repeatable(20);
                l.field("Sku", FieldKind.STRING);
                l.dateField("DueDate", d -> { });
                l.timeField("PickupTime", t -> { });
                l.dateTimeField("OrderedAt", dt -> { });
                l.enumField("Status", e -> e.value("OPEN").value("SHIPPED"));
            })).build().toJson());

    @Test
    void presenceRendersForDateTimeAndEnumCells() {
        // the render locks above cover num + str; forIndex is kind-agnostic, so date/time/dateTime/enum cells
        // render the SAME bare semantic-index form — lock the remaining kinds so a per-kind regression is caught.
        DslRenderer r = new DslRenderer();
        assertEquals("FieldFilled(/Cart/Lines/DueDate For \"K1\")",
                r.render(cart.date("/Cart/Lines/DueDate").forIndex("K1").filled()));
        assertEquals("FieldNotFilled(/Cart/Lines/PickupTime For \"K1\")",
                r.render(cart.time("/Cart/Lines/PickupTime").forIndex("K1").notFilled()));
        assertEquals("FieldFilled(/Cart/Lines/OrderedAt For \"K1\")",
                r.render(cart.dateTime("/Cart/Lines/OrderedAt").forIndex("K1").filled()));
        assertEquals("FieldFilled(/Cart/Lines/Status For \"K1\")",
                r.render(cart.enumOf("/Cart/Lines/Status").forIndex("K1").filled()));
        // DE keyword localization holds across kinds too (the enum cell)
        assertEquals("FeldAngegeben(/Cart/Lines/Status Fuer \"K1\")",
                new DslRenderer(ConditionLanguage.DE).render(cart.enumOf("/Cart/Lines/Status").forIndex("K1").filled()));
    }

    @Test
    void filledOnANonIndexedExpressionIsRejected() {
        // .filled() is a fail-fast corrective: only an indexed cell reference has a presence, an
        // arbitrary aggregate/arithmetic expression does not (a kernel-illegal FieldFilled(Sum(...))).
        assertThrows(IllegalStateException.class,
                () -> Dsl.sum(m.flatten("/Order/Items*/Count")).filled());
    }

    @Test
    void theRealKernelAcceptsTypedIndexedPresence() {
        // round-trip mirrors SemanticIndexPresenceDiffTest: a guarded rule over the indexed cell.
        Condition c = m.str("/Order/CustomerName").filled()
                .and(m.num(COUNT).forIndex("SKU-1").filled());
        String rendered = new DslRenderer().render(c);
        ValidationResult vr = new RuleValidator().check(Fixtures.orderJson(),
                new DraftRule("/Order", "IndexedPresenceRule", "../CustomerName", rendered, "IDX", "ERROR"));
        assertTrue(vr.ok(), () -> "kernel rejected typed indexed presence: " + vr.diagnostics());
    }
}

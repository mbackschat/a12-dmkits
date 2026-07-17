package io.github.mbackschat.a12.dm.adapter.review;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * MODEL-REVIEW — the report's computation catalog carries the <b>preconditions</b> (Set RP task 2), in parity
 * with {@code computation read}: the {@code commonPrecondition} (the gate over the whole computation) and each
 * alternative's own {@code precondition} + {@code operation}. Without them a reviewer reading {@code model
 * report} can't see <em>when</em> a computed field is filled — a gap versus {@code computation read}.
 */
class ModelReportComputationPreconditionsTest {

    private static final String SHIPPING = "/Order/ShippingFee";
    private static final String DISCOUNT = "/Order/Discount";

    /** An e-commerce order-pricing model: one computation gated by a <b>common precondition</b> + one with
     *  <b>per-alternative preconditions</b> — authored through the typed surface (dogfood), never raw DM-JSON. */
    private static String orderPricingModel() {
        String base = DocumentModelDraft.named("order-pricing").locale("en_US")
                .rootGroup("Order", g -> g
                        .field("Subtotal", FieldKind.NUMBER)
                        .field("BaseShipping", FieldKind.NUMBER)
                        .field("MemberRate", FieldKind.NUMBER)
                        .field("GuestRate", FieldKind.NUMBER)
                        .field("ShippingFee", FieldKind.NUMBER)
                        .field("Discount", FieldKind.NUMBER))
                .build().toJson();
        EditSession s = EditSession.read(base);
        // (A) a common precondition gating the whole computation + two preconditioned alternatives (a common
        //     precondition over a single unconditional operation folds onto that alternative as its own
        //     precondition on the kernel round-trip — semantically identical; KERNEL-FINDINGS §11 supplement,
        //     locked by ComputationCommonPreconditionFoldTest — so it survives distinctly only with ≥2 alternatives)
        s.addComputation(m -> m.computation(m.num(SHIPPING))
                .name("ShippingFeeComp")
                .commonPrecondition(m.num("/Order/Subtotal").gt(0))
                .when(m.num("/Order/Subtotal").gt(500)).calc(m.num("/Order/GuestRate").value())
                .when(m.num("/Order/Subtotal").le(500)).calc(m.num("/Order/BaseShipping").value())
                .message(en("Shipping fee applies once the order has a subtotal.")).build());
        // (B) multiple alternatives, each with its own mutually-exclusive precondition
        s.addComputation(m -> m.computation(m.num(DISCOUNT))
                .name("DiscountComp")
                .when(m.num("/Order/Subtotal").gt(1000)).calc(m.num("/Order/MemberRate").value())
                .when(m.num("/Order/Subtotal").le(1000)).calc(m.num("/Order/GuestRate").value())
                .message(en("Discount depends on the order subtotal.")).build());
        return s.toJson();
    }

    @Test
    void reportCarriesTheCommonPrecondition() {
        ModelReport.ComputationEntry shipping = entry(ModelReport.of(orderPricingModel()), SHIPPING);
        assertNotNull(shipping.commonPrecondition(),
                "the common precondition (the gate over the whole computation) must ride the report entry");
        assertEquals(2, shipping.alternatives().size(), "two preconditioned alternatives under the common gate");
        assertTrue(shipping.alternatives().stream().allMatch(a -> a.precondition() != null && a.operation() != null),
                () -> "each alternative carries its own precondition + operation, got: " + shipping.alternatives());
    }

    @Test
    void reportCarriesPerAlternativePreconditions() {
        ModelReport.ComputationEntry discount = entry(ModelReport.of(orderPricingModel()), DISCOUNT);
        assertNull(discount.commonPrecondition(), "this computation has no common precondition");
        assertEquals(2, discount.alternatives().size(), "two preconditioned alternatives");
        assertTrue(discount.alternatives().stream().allMatch(a -> a.precondition() != null && a.operation() != null),
                () -> "each alternative carries its own precondition + operation, got: " + discount.alternatives());
    }

    private static ModelReport.ComputationEntry entry(ModelReport r, String computedField) {
        return r.computations().stream().filter(c -> computedField.equals(c.computedField())).findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no computation computing " + computedField + " in the catalog: " + r.computations()));
    }
}

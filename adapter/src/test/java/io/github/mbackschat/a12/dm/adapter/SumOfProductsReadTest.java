package io.github.mbackschat.a12.dm.adapter;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * F10 read tail — {@code SumOfProducts(a, b)}: the element-wise Σ aᵢ·bᵢ over two parallel number
 * repetition lists (the kernel's {@code feldListenOp} sibling of {@code Sum}). Own domain: an invoice
 * whose line items each carry a quantity and a unit price; the stored total must equal Σ qty·price.
 */
class SumOfProductsReadTest {

    private static final DslRenderer RENDER = new DslRenderer(ConditionLanguage.EN);

    private static String invoiceJson() {
        return DocumentModelDraft.named("invoice").locale("en_US")
                .rootGroup("Invoice", inv -> inv
                        .numberField("StoredTotal", n -> n.fractionDigits(2))
                        .group("LineItems", li -> li
                                .repeatable(50).indexField("Sku")
                                .stringField("Sku", s -> { })
                                .numberField("Quantity", n -> { })
                                .numberField("UnitPrice", n -> n.fractionDigits(2))))
                .build().toJson();
    }

    @Test
    void roundTripsSumOfProducts() {
        BoundModel m = BoundModel.ofJson(invoiceJson());
        // [StoredTotal] != RoundAccounting(SumOfProducts(LineItems*/Quantity, LineItems*/UnitPrice), 2)
        Condition full = m.num("/Invoice/StoredTotal").filled()
                .and(m.num("/Invoice/StoredTotal").ne(Dsl.roundAccounting(
                        Dsl.sumOfProducts(m.flatten("/Invoice/LineItems*/Quantity"),
                                m.flatten("/Invoice/LineItems*/UnitPrice")), 2)));
        KernelAdapter adapter = KernelAdapter.of(m.add(rule(m, "TotalRule", "/Invoice/StoredTotal", full)));
        assertTrue(RENDER.render(full)
                .contains("SumOfProducts(/Invoice/LineItems*/Quantity, /Invoice/LineItems*/UnitPrice)"),
                RENDER.render(full));
        assertEquals(RENDER.render(full), RENDER.render(converted(adapter.read("/Invoice/TotalRule"))));
    }

    @Test
    void sumOfProductsIsKernelValid() {
        BoundModel m = BoundModel.ofJson(invoiceJson());
        Condition c = m.num("/Invoice/StoredTotal").filled()
                .and(m.num("/Invoice/StoredTotal").ne(Dsl.sumOfProducts(
                        m.flatten("/Invoice/LineItems*/Quantity"), m.flatten("/Invoice/LineItems*/UnitPrice"))));
        ValidationResult r = new RuleValidator().check(invoiceJson(),
                new DraftRule("/Invoice", "TotalRule", "../StoredTotal", RENDER.render(c), "C", "ERROR"));
        assertTrue(r.ok(), () -> "expected kernel acceptance, got: " + r.diagnostics());
    }

    private static Rule rule(BoundModel m, String name, String errorField, Condition cond) {
        return m.rule(m.group("/Invoice")).name(name).errorField(m.field(errorField)).when(cond)
                .code("C").severity(Severity.ERROR).message(en("x")).build();
    }

    private static Condition converted(ReadResult result) {
        return assertInstanceOf(ReadResult.Converted.class, result,
                () -> "expected a converted read, got: " + result).condition();
    }
}

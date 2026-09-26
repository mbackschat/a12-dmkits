package io.github.mbackschat.a12.dm.adapter;
import io.github.mbackschat.a12.dm.rulekit.read.ReadComputation;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * The {@code DateRange} constructor through the computation full cycle: a computation assigning a
 * date-range field via {@code DateRange(start, end)} is kernel-accepted and reads back to a typed
 * {@code DateRangeConstruction} operation (render → extract → convert is the identity). This is the
 * constructor's computation use site; equality/inequality are covered by {@code DateRangeReadTest}.
 */
class DateRangeComputationReadTest {

    private static final String COVERAGE = "/Order/CoverageWindow";

    private static BoundModel order() {
        return BoundModel.ofJson(TestResources.text("models/order.dm.json"));
    }

    @Test
    void roundTripsADateRangeComputation() {
        BoundModel m = order();
        Computation comp = m.computation(m.field(COVERAGE)).name("CoverageWindowComp")
                .calc(Dsl.dateRange(m.date("/Order/OrderDate"), m.date("/Order/DeliveryDate")))
                .message(en("Coverage window spans the order through the delivery date.")).build();

        ReadComputation result = KernelAdapter.of(m.add(comp)).readComputation("/Order/CoverageWindowComp");
        Computation got = assertInstanceOf(ReadComputation.Converted.class, result,
                () -> "expected a converted computation, got: " + result).computation();
        assertEquals(comp.draft(), got.draft());
    }

    @Test
    void roundTripsTheOwnerLegalDateFragmentConstruction() {
        String json = DocumentModelDraft.named("fragment-range").locale("en_US")
                .rootGroup("Period", period -> period
                        .dateFragmentField("FromMonth", field -> field.fragmentFormat("MM"))
                        .dateFragmentField("ToMonth", field -> field.fragmentFormat("MM"))
                        .dateRangeField("Months", field -> field.format("MM").separator("/")))
                .build().toJson();
        BoundModel m = BoundModel.ofJson(json);
        Computation comp = m.computation(m.field("/Period/Months")).name("MonthsComp")
                .calc(Dsl.dateRange(m.dateFragment("/Period/FromMonth"),
                        m.dateFragment("/Period/ToMonth")))
                .message(en("Selected month range.")).build();

        ReadComputation result = KernelAdapter.of(m.add(comp)).readComputation("/Period/MonthsComp");
        Computation got = assertInstanceOf(ReadComputation.Converted.class, result,
                () -> "expected a converted computation, got: " + result).computation();
        assertEquals(comp.draft(), got.draft());
    }

    @Test
    void roundTripsTheOwnerLegalSemanticIndexConstruction() {
        String json = DocumentModelDraft.named("indexed-range").locale("en_US")
                .rootGroup("Order", order -> order
                        .stringField("SelectedSku", field -> { })
                        .dateRangeField("Coverage", field -> field.format("yyyy-MM-dd").separator("/"))
                        .group("Items", items -> items.repeatable(20).indexField("Sku")
                                .stringField("Sku", field -> { })
                                .dateField("PromiseDate", field -> field.format("yyyy-MM-dd"))))
                .build().toJson();
        BoundModel m = BoundModel.ofJson(json);
        Computation comp = m.computation(m.field("/Order/Coverage")).name("IndexedCoverage")
                .calc(Dsl.dateRange(m.date("/Order/Items/PromiseDate").forIndex("sku1"),
                        m.date("/Order/Items/PromiseDate").forIndex(m.field("/Order/SelectedSku"))))
                .message(en("Indexed coverage range.")).build();

        ReadComputation result = KernelAdapter.of(m.add(comp)).readComputation("/Order/IndexedCoverage");
        Computation got = assertInstanceOf(ReadComputation.Converted.class, result,
                () -> "expected a converted computation, got: " + result).computation();
        assertEquals(comp.draft(), got.draft());
    }

    @Test
    void roundTripsMixedDateAndFragmentEndpoints() {
        String json = DocumentModelDraft.named("mixed-range").locale("en_US")
                .rootGroup("Period", period -> period
                        .dateField("DateMonth", field -> field.format("MM"))
                        .dateFragmentField("FragmentMonth", field -> field.fragmentFormat("MM"))
                        .dateRangeField("Months", field -> field.format("MM").separator("/")))
                .build().toJson();
        BoundModel m = BoundModel.ofJson(json);
        Computation comp = m.computation(m.field("/Period/Months")).name("MixedMonths")
                .calc(Dsl.dateRange(m.date("/Period/DateMonth"), m.dateFragment("/Period/FragmentMonth")))
                .message(en("Mixed owner types with matching components.")).build();

        ReadComputation result = KernelAdapter.of(m.add(comp)).readComputation("/Period/MixedMonths");
        Computation got = assertInstanceOf(ReadComputation.Converted.class, result,
                () -> "expected a converted computation, got: " + result).computation();
        assertEquals(comp.draft(), got.draft());
    }
}

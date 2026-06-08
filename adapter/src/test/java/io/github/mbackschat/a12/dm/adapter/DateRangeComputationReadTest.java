package io.github.mbackschat.a12.dm.adapter;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;

/**
 * The {@code DateRange} constructor through the computation full cycle: a computation assigning a
 * date-range field via {@code DateRange(start, end)} is kernel-accepted and reads back to a typed
 * {@code DateRangeConstruction} operation (render → extract → convert is the identity). This is the
 * constructor's only legal use site (it has no condition form).
 */
class DateRangeComputationReadTest {

    private static final String COVERAGE = "/Order/CoverageWindow";

    private static BoundModel order() {
        return BoundModel.ofJson(read("models/order.dm.json"));
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

    private static String read(String path) {
        try (InputStream in = DateRangeComputationReadTest.class.getClassLoader().getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

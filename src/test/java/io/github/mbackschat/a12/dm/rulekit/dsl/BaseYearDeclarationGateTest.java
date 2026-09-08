package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * The typed peer of {@code validate.laws.BaseYearDeclarationLawsTest}: every {@code BaseYear} carrier needs the
 * model to declare a base year, else {@code MVK_NO_BASE_YEAR}.
 *
 * <p>This is the gate that the format gate over the same constant is NOT. A format rule would have to invent a
 * declaration {@code BaseYear} does not carry, and is measured to over-reject
 * ({@code validate.laws.BaseYearExtractionFormatLawsTest}). This rule reads a fact the model states outright,
 * so it is exact in both directions: it refuses exactly when the kernel does, and it cannot refuse a model
 * that declares one.
 *
 * <p>{@code Dsl}'s four factories stay ungated — {@code :adapter}'s converter reconstructs {@code BaseYear}
 * through them and through the bare records while rebuilding a kernel-ACCEPTED model, so a refusal there would
 * make a legal stored model unreadable.
 */
class BaseYearDeclarationGateTest {

    private static String json(Integer baseYear) {
        var builder = DocumentModelDraft.named(
                baseYear == null ? "gate-unconfigured" : "gate-configured").locale("en_US");
        if (baseYear != null) {
            builder.baseYear(baseYear);
        }
        return builder
                .rootGroup("Cover", c -> c
                        .field("N", FieldKind.NUMBER)
                        .dateField("FiledOn", d -> d.format("yyyy-MM-dd")))
                .build().toJson();
    }

    private static final BoundModel UNCONFIGURED = BoundModel.ofJson(json(null));
    private static final BoundModel CONFIGURED = BoundModel.ofJson(json(2024));

    private static List<Supplier<?>> carriers(BoundModel m) {
        return List.of(m::baseYear, m::baseYearNumber, m::startOfBaseYear, m::endOfBaseYear);
    }

    @Test
    void everyCarrierIsRefusedWithoutADeclaredBaseYear() {
        for (Supplier<?> carrier : carriers(UNCONFIGURED)) {
            var thrown = assertThrows(IllegalArgumentException.class, carrier::get);
            assertTrue(thrown.getMessage().contains("MVK_NO_BASE_YEAR"), thrown::getMessage);
            assertTrue(thrown.getMessage().contains("declares none"), thrown::getMessage);
        }
    }

    /** THE LIVENESS CONTROL: the same four carriers on a model that declares one. */
    @Test
    void everyCarrierIsAdmittedWithADeclaredBaseYear() {
        for (Supplier<?> carrier : carriers(CONFIGURED)) {
            assertDoesNotThrow(carrier::get);
        }
    }

    /**
     * The extraction carrier needs no gate of its own, and this is why: it takes a {@link DateOperand}, so the
     * only constant it can be built over is one a checked factory already returned. The refusal therefore
     * arrives from the operand, one step earlier.
     */
    @Test
    void anExtractionOverTheConstantIsRefusedThroughItsOperand() {
        assertThrows(IllegalArgumentException.class,
                () -> Dsl.yearFromDate(UNCONFIGURED.baseYear()));
        assertDoesNotThrow(() -> Dsl.yearFromDate(CONFIGURED.baseYear()));
    }

    /**
     * The read path's route stays open. {@code :adapter} rebuilds the constant through these, and a
     * kernel-accepted model reaching the converter has a declared base year by construction — so gating them
     * would refuse nothing real and break reading.
     */
    @Test
    void theUngatedDslFactoriesRemainAvailable() {
        assertDoesNotThrow(Dsl::baseYear);
        assertDoesNotThrow(Dsl::baseYearNumber);
        assertDoesNotThrow(Dsl::startOfBaseYear);
        assertDoesNotThrow(Dsl::endOfBaseYear);
    }
}

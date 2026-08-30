package io.github.mbackschat.a12.dm.adapter.laws;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;

/**
 * <b>Repetition DEPTH does not bound the group-count operand's row constituent — and the half-instantiated
 * shape settles which reading that is.</b> [KF217] established the two content constituents on a fixture whose
 * repeatable descendant sat exactly ONE level below the operand, which cannot tell a containment rule ("any
 * instantiated row anywhere beneath it") from a leaf-row rule ("a row of the innermost repeatable").
 *
 * <p>{@code Shipments} is the shell: no direct field, its only content a repeatable {@code Pallets} which in
 * turn contains a repeatable {@code Cartons}. So content is two repetition levels down, and the outer level can
 * be instantiated without the inner one — the document neither project had measured.
 *
 * <p>Own domain (warehouse fulfilment), distinct from the supplier catalogue the one-level locks use.
 */
class DeepRepeatableRowConstituentLawsTest {

    private static final String TALLY = "/Fulfilment/Tally";

    private static final String FULFILMENT = DocumentModelDraft.named("fulfilment").locale("en_US")
            .rootGroup("Fulfilment", f -> f
                    .field("Reference", FieldKind.STRING)
                    .numberField("Tally", n -> n.fractionDigits(0))
                    .group("Depot", d -> d.field("Code", FieldKind.STRING))
                    .group("Shipments", s -> s
                            .group("Pallets", p -> p.repeatable(5)
                                    .group("Cartons", c -> c.repeatable(5)
                                            .field("Sku", FieldKind.STRING)
                                            .numberField("Units", n -> n.fractionDigits(0))))))
            .build().toJson();

    private static final BoundModel M = BoundModel.ofJson(FULFILMENT);

    /** {@code NumberOfFilledGroups(Depot, Shipments)} — a flat operand beside the two-level shell. */
    private static final String MODEL = model();

    private static String model() {
        Computation comp = M.computation(M.field(TALLY)).name("ShipmentTallyComp")
                .calc(Dsl.numberOfFilledGroups(M.group("/Fulfilment/Depot"), M.group("/Fulfilment/Shipments"))
                        .expression())
                .message(en("Shipment tally."))
                .build();
        return M.add(comp).toJson();
    }

    private static RuntimeLaws.Doc base() {
        return RuntimeLaws.doc().group("/Fulfilment", 1)
                .field("/Fulfilment/Reference", "FUL-1", 1, 1)
                .field("/Fulfilment/Depot/Code", "D-7", 1, 1, 1);
    }

    private static String tally(RuntimeLaws.Doc doc) {
        return RuntimeLaws.computedValue(RuntimeLaws.compute(MODEL, doc), TALLY)
                .map(v -> new BigDecimal(v).stripTrailingZeros().toPlainString())
                .orElse("(none)");
    }

    /**
     * a12-kernel-lean's {@code SPEC-2026-08-30-08} acceptance condition, on our own fixture, plus the row its
     * stated limit could not reach.
     *
     * <p>The half-instantiated row is the discriminator: an outer {@code Pallets} row with no {@code Cartons}
     * row beneath it. A containment reading counts the shell as present; a leaf-row reading does not.
     *
     * <p>The empty-document row is the control that keeps every count above it from being a constant, and the
     * admission assertion is here because this test claims the model is authorable at all — running the engine
     * and getting a number is not an {@code MVK_*} verdict.
     */
    @Test
    void aRowTwoRepetitionLevelsDownIsContentAndSoIsAnOuterRowWithNoInnerRow() {
        assertEquals(List.of(), RuntimeLaws.consistencyMessages(MODEL),
                "a shell whose content is two repeatable levels down is a statically admitted operand");

        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("leaf row instantiated, its cells EMPTY", tally(base()
                .group("/Fulfilment/Shipments/Pallets/Cartons", 1, 1, 1, 1)));
        actual.put("leaf row instantiated, a cell FILLED", tally(base()
                .group("/Fulfilment/Shipments/Pallets/Cartons", 1, 1, 1, 1)
                .field("/Fulfilment/Shipments/Pallets/Cartons/Sku", "SKU-9", 1, 1, 1, 1, 1)));
        actual.put("HALF-INSTANTIATED: outer row, no inner row", tally(base()
                .group("/Fulfilment/Shipments/Pallets", 1, 1, 1)));
        actual.put("no row at either level — control", tally(base()));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("leaf row instantiated, its cells EMPTY", "2");
        expected.put("leaf row instantiated, a cell FILLED", "2");
        expected.put("HALF-INSTANTIATED: outer row, no inner row", "2");
        expected.put("no row at either level — control", "1");
        assertEquals(expected, actual, "containment, not a leaf-row rule and not an axis count");
    }
}

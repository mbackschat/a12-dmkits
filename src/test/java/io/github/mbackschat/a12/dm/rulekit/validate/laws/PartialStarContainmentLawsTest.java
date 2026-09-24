package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Calculation;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;

/**
 * KERNEL-FINDINGS §11 — <b>a star that leaves ANY repeatable level of its path unstarred is a per-row read for
 * {@code MVK_ERROR_FIELD_NOT_IN_RULEGROUP}</b>, on both constructs, whichever side of the first star the level
 * sits and whether or not the declaring group binds it. Only a star on every level reads no row.
 *
 * <p>This is the cell [KF466]'s fixed-target rows could not isolate: each of them held the target CONTAINED
 * whenever the declaring group bound nothing, so "the declaring group binds the level" and "the target lies
 * outside the declaring group" moved together. Here the target is outside the declaring group on every row,
 * and the declaring group is the variable — {@code Shipments} is the level itself, {@code Ledger} is a fixed
 * sibling that binds nothing.
 *
 * <p><b>Containment answers ahead of the index class.</b> The keyed row is the one where the same operand into
 * a contained target draws {@code MVK_ERROR_FIELD_NO_INDEX} ([KF466]); uncontained, the index declaration moves
 * nothing, as it moves nothing for a plain per-row read ({@code ErrorTargetContainmentLawsTest}).
 *
 * <p>Own domain (an e-commerce fulfilment sheet).
 */
class PartialStarContainmentLawsTest {

    private static final String NOT_IN_RULEGROUP = "MVK_ERROR_FIELD_NOT_IN_RULEGROUP";

    private static final Sheet PLAIN = new Sheet(false);
    private static final Sheet SHIPMENTS_KEYED = new Sheet(true);

    private static final String SHIPMENTS = "/Fulfilment/Shipments";
    private static final String LEDGER = "/Fulfilment/Ledger";
    private static final String TARGET = "/Fulfilment/Summary/Result";

    private static final String ABOVE = "/Fulfilment/Shipments/Parcels*/Weight";
    private static final String BELOW = "/Fulfilment/Shipments*/Parcels/Weight";
    private static final String EVERY = "/Fulfilment/Shipments*/Parcels*/Weight";

    /**
     * {@code ComputationStarBindingLawsTest}'s fulfilment sheet with one fixed {@code Ledger} added beside
     * {@code Summary}, so the portable checker's mirror can read both locks off one model; the flag declares an
     * index.
     */
    private record Sheet(String json, BoundModel model) {

        Sheet(boolean shipmentsKeyed) {
            this(sheet(shipmentsKeyed));
        }

        private Sheet(String json) {
            this(json, BoundModel.ofJson(json));
        }

        private static String sheet(boolean shipmentsKeyed) {
            return DocumentModelDraft.named("fulfilment").locale("en_US")
                    .rootGroup("Fulfilment", f -> f
                            .group("Summary", s -> s.numberField("Result", n -> n.fractionDigits(0)))
                            .group("Ledger", l -> l.numberField("Total", n -> n.fractionDigits(0)))
                            .group("Shipments", sh -> {
                                sh.repeatable(2);
                                if (shipmentsKeyed) {
                                    sh.indexField("ShipKey");
                                }
                                sh.field("ShipKey", FieldKind.STRING)
                                        .numberField("ShipWeight", n -> n.fractionDigits(0))
                                        .numberField("ShipResult", n -> n.fractionDigits(0))
                                        .group("Parcels", p -> p.repeatable(2)
                                                .field("ParcelKey", FieldKind.STRING)
                                                .numberField("Weight", n -> n.fractionDigits(0))
                                                .numberField("Share", n -> n.fractionDigits(0))
                                                .group("Seal", se -> se.field("SealCode", FieldKind.STRING)));
                            }))
                    .build().toJson();
        }

        String computation(String group, Calculation operation) {
            return KernelLaws.computationVerdict(json, model.computation(model.num(TARGET))
                    .name("ContainmentProbe")
                    .group(model.group(group))
                    .calc(operation)
                    .message(Dsl.en("Containment probe."))
                    .build());
        }

        /**
         * A rule reporting on the target, which its condition reaches through the non-repeatable root. The error
         * field is written as text because {@code Rule.Builder.build()} refuses an uncontained one outright
         * (FEATURE-GAPS §4), which is the very shape measured; both declaring groups sit one level below the root.
         */
        String rule(String group, Condition read) {
            Condition reach = model.field(TARGET).filled();
            return KernelLaws.verdictModel(json, group, "../../Summary/Result", reach.and(read));
        }

        Calculation sum(String starPath) {
            return Dsl.sum(model.flatten(starPath)).expression();
        }

        Calculation countSeals(String starGroupPath) {
            return model.numberOfFilledGroups(model.flattenGroup(starGroupPath)).expression();
        }
    }

    @Test
    void onTheComputationArmEveryUnstarredLevelIsPerRow() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("Ledger | Sum, unstarred above the first star", PLAIN.computation(LEDGER, PLAIN.sum(ABOVE)));
        actual.put("Ledger | Sum, unstarred below the first star", PLAIN.computation(LEDGER, PLAIN.sum(BELOW)));
        actual.put("Ledger | the group count, above",
                PLAIN.computation(LEDGER, PLAIN.countSeals("/Fulfilment/Shipments/Parcels*/Seal")));
        actual.put("Ledger | the group count, below",
                PLAIN.computation(LEDGER, PLAIN.countSeals("/Fulfilment/Shipments*/Parcels/Seal")));
        actual.put("Ledger | Sum, above, Shipments index-keyed",
                SHIPMENTS_KEYED.computation(LEDGER, SHIPMENTS_KEYED.sum(ABOVE)));
        actual.put("Shipments | Sum, unstarred below the first star", PLAIN.computation(SHIPMENTS, PLAIN.sum(BELOW)));
        actual.put("Ledger | Sum, every level starred — control", PLAIN.computation(LEDGER, PLAIN.sum(EVERY)));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("Ledger | Sum, unstarred above the first star", NOT_IN_RULEGROUP);
        expected.put("Ledger | Sum, unstarred below the first star", NOT_IN_RULEGROUP);
        expected.put("Ledger | the group count, above", NOT_IN_RULEGROUP);
        expected.put("Ledger | the group count, below", NOT_IN_RULEGROUP);
        expected.put("Ledger | Sum, above, Shipments index-keyed", NOT_IN_RULEGROUP);
        expected.put("Shipments | Sum, unstarred below the first star", NOT_IN_RULEGROUP);
        expected.put("Ledger | Sum, every level starred — control", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "a partial star into a target outside the declaring group");
    }

    @Test
    void onTheRuleArmTheSameReadsArePerRow() {
        BoundModel m = PLAIN.model();
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("Shipments | Sum, above", PLAIN.rule(SHIPMENTS, Dsl.sum(m.flatten(ABOVE)).gt(0)));
        actual.put("Shipments | the group count, above", PLAIN.rule(SHIPMENTS,
                m.numberOfFilledGroups(m.flattenGroup("/Fulfilment/Shipments/Parcels*/Seal")).gt(0)));
        actual.put("Ledger | Sum, above", PLAIN.rule(LEDGER, Dsl.sum(m.flatten(ABOVE)).gt(0)));
        actual.put("Ledger | Sum, below", PLAIN.rule(LEDGER, Dsl.sum(m.flatten(BELOW)).gt(0)));
        actual.put("Shipments | Sum, every level starred — control",
                PLAIN.rule(SHIPMENTS, Dsl.sum(m.flatten(EVERY)).gt(0)));
        actual.put("Ledger | Sum, every level starred — control", PLAIN.rule(LEDGER, Dsl.sum(m.flatten(EVERY)).gt(0)));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("Shipments | Sum, above", NOT_IN_RULEGROUP);
        expected.put("Shipments | the group count, above", NOT_IN_RULEGROUP);
        expected.put("Ledger | Sum, above", NOT_IN_RULEGROUP);
        expected.put("Ledger | Sum, below", NOT_IN_RULEGROUP);
        expected.put("Shipments | Sum, every level starred — control", KernelLaws.ADMITTED);
        expected.put("Ledger | Sum, every level starred — control", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "a partial star in a rule reporting outside its group");
    }
}

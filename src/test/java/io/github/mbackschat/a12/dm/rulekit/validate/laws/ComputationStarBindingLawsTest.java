package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Calculation;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;

/**
 * KERNEL-FINDINGS §11 — <b>a repeatable level a star leaves UNSTARRED must be bound</b>, on the computation arm,
 * and who binds the outermost one decides the class (reconciles a12-kernel-lean {@code SPEC-2026-09-24-03},
 * their report; every row measured here on our own fixture).
 *
 * <p>Below the first star, an unstarred level is {@code MVK_NO_WILDCARD} whatever binds it. Above it, into a
 * FIXED target, a repeatable declaring group that binds the level draws {@code MVK_ERROR_FIELD_NOT_IN_RULEGROUP},
 * and a level nothing binds draws {@code MVK_NO_WILDCARD}, or {@code MVK_ERROR_FIELD_NO_INDEX} when its group
 * declares an index field. A target inside the level admits the same operand. A plain read follows the same
 * table, and its OUTERMOST level alone decides.
 *
 * <p><b>One variable per row.</b> The three models differ only in which group declares an index field; every
 * one carries both key fields, so the declaration is the one thing that moves. Within a model a table varies the
 * declaring group, or the target, with the operation held fixed.
 *
 * <p>Own domain (an e-commerce fulfilment sheet).
 */
class ComputationStarBindingLawsTest {

    private static final String NO_WILDCARD = "MVK_NO_WILDCARD";
    private static final String NOT_IN_RULEGROUP = "MVK_ERROR_FIELD_NOT_IN_RULEGROUP";
    private static final String NO_INDEX = "MVK_ERROR_FIELD_NO_INDEX";

    private static final Fixture PLAIN = new Fixture(false, false);
    private static final Fixture SHIPMENTS_KEYED = new Fixture(true, false);
    private static final Fixture PARCELS_KEYED = new Fixture(false, true);

    private static final String SUMMARY = "/Fulfilment/Summary";
    private static final String SHIPMENTS = "/Fulfilment/Shipments";
    private static final String PARCELS = "/Fulfilment/Shipments/Parcels";
    private static final String FIXED_TARGET = SUMMARY + "/Result";
    private static final String SHIPMENT_TARGET = SHIPMENTS + "/ShipResult";
    private static final String PARCEL_TARGET = PARCELS + "/Share";

    private static final String BELOW_THE_STAR = "/Fulfilment/Shipments*/Parcels/Weight";
    private static final String ABOVE_THE_STAR = "/Fulfilment/Shipments/Parcels*/Weight";

    /** One fulfilment sheet; the two flags only declare an existing key field as its group's index. */
    private record Fixture(String json, BoundModel model) {

        Fixture(boolean shipmentsKeyed, boolean parcelsKeyed) {
            this(sheet(shipmentsKeyed, parcelsKeyed));
        }

        private Fixture(String json) {
            this(json, BoundModel.ofJson(json));
        }

        private static String sheet(boolean shipmentsKeyed, boolean parcelsKeyed) {
            return DocumentModelDraft.named("fulfilment").locale("en_US")
                    .rootGroup("Fulfilment", f -> f
                            .group("Summary", s -> s.numberField("Result", n -> n.fractionDigits(0)))
                            .group("Shipments", sh -> {
                                sh.repeatable(2);
                                if (shipmentsKeyed) {
                                    sh.indexField("ShipKey");
                                }
                                sh.field("ShipKey", FieldKind.STRING)
                                        .numberField("ShipWeight", n -> n.fractionDigits(0))
                                        .numberField("ShipResult", n -> n.fractionDigits(0))
                                        .group("Parcels", p -> {
                                            p.repeatable(2);
                                            if (parcelsKeyed) {
                                                p.indexField("ParcelKey");
                                            }
                                            p.field("ParcelKey", FieldKind.STRING)
                                                    .numberField("Weight", n -> n.fractionDigits(0))
                                                    .numberField("Share", n -> n.fractionDigits(0))
                                                    .group("Seal", se -> se.field("SealCode", FieldKind.STRING));
                                        });
                            }))
                    .build().toJson();
        }

        String verdict(String target, String group, Function<BoundModel, Calculation> operation) {
            return KernelLaws.computationVerdict(json, computation(target, group, operation));
        }

        /** The verdict on an operation written as text, for a shape the typed surface refuses. */
        String draftVerdict(String group, String targetRelPath, String operation) {
            return KernelLaws.computationVerdict(json, new DraftComputation(group, "BindingProbe", targetRelPath,
                    null, List.of(new DraftComputation.Alternative(null, operation))));
        }

        /** What the kernel is handed: the declaring group, the target as written from it, and the operation. */
        String rendered(String target, String group, Function<BoundModel, Calculation> operation) {
            DraftComputation draft = computation(target, group, operation).draft();
            return draft.groupPath() + " | " + draft.computedFieldRelPath() + " | "
                    + draft.alternatives().get(0).operation();
        }

        private Computation computation(String target, String group, Function<BoundModel, Calculation> operation) {
            return model.computation(model.num(target))
                    .name("BindingProbe")
                    .group(model.group(group))
                    .calc(operation.apply(model))
                    .message(Dsl.en("Star binding probe."))
                    .build();
        }
    }

    private static Function<BoundModel, Calculation> sum(String starPath) {
        return m -> Dsl.sum(m.flatten(starPath)).expression();
    }

    private static Function<BoundModel, Calculation> sumAbsolute(String starPath) {
        return m -> Dsl.sum(m.flatten(starPath).absolute()).expression();
    }

    private static Function<BoundModel, Calculation> countSeals(String starGroupPath) {
        return m -> m.numberOfFilledGroups(m.flattenGroup(starGroupPath)).expression();
    }

    private static Function<BoundModel, Calculation> plusOne(String field) {
        return m -> m.num(field).asNumber().plus(1).expression();
    }

    /** Pins the spellings the kernel is handed, so no verdict below is read off a derived unknown. */
    @Test
    void theOperationsRenderAsTheRowsName() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("relative", PLAIN.rendered(FIXED_TARGET, SHIPMENTS, sum(ABOVE_THE_STAR)));
        actual.put("absolute", PLAIN.rendered(FIXED_TARGET, SHIPMENTS, sumAbsolute(ABOVE_THE_STAR)));
        actual.put("from Summary", PLAIN.rendered(FIXED_TARGET, SUMMARY, sum(ABOVE_THE_STAR)));
        actual.put("below the star", PLAIN.rendered(FIXED_TARGET, SUMMARY, sum(BELOW_THE_STAR)));
        actual.put("group count",
                PLAIN.rendered(SHIPMENT_TARGET, SHIPMENTS, countSeals("/Fulfilment/Shipments/Parcels*/Seal")));
        actual.put("plain read", PLAIN.rendered(FIXED_TARGET, SUMMARY, plusOne(SHIPMENTS + "/ShipWeight")));
        actual.put("starred group count", PLAIN.rendered(FIXED_TARGET, SUMMARY, countSeals(PARCELS + "*")));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("relative", "/Fulfilment/Shipments | ../../Summary/Result | Sum(Parcels*/Weight)");
        expected.put("absolute",
                "/Fulfilment/Shipments | ../../Summary/Result | Sum(/Fulfilment/Shipments/Parcels*/Weight)");
        expected.put("from Summary", "/Fulfilment/Summary | ../Result | Sum(../Shipments/Parcels*/Weight)");
        expected.put("below the star", "/Fulfilment/Summary | ../Result | Sum(../Shipments*/Parcels/Weight)");
        expected.put("group count", "/Fulfilment/Shipments | ../ShipResult | NumberOfFilledGroups(Parcels*/Seal)");
        expected.put("plain read", "/Fulfilment/Summary | ../Result | [../Shipments/ShipWeight] + 1");
        expected.put("starred group count", "/Fulfilment/Summary | ../Result | NumberOfFilledGroups(../Shipments/Parcels*)");
        assertEquals(expected, actual, "the rendered computations");
    }

    @Test
    void belowTheFirstStarAnUnstarredLevelIsRefusedWhateverBindsIt() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("Sum into the fixed target", PLAIN.verdict(FIXED_TARGET, SUMMARY, sum(BELOW_THE_STAR)));
        actual.put("Sum into a target in Shipments", PLAIN.verdict(SHIPMENT_TARGET, SHIPMENTS, sum(BELOW_THE_STAR)));
        actual.put("Sum into a target in Parcels, the level itself",
                PLAIN.verdict(PARCEL_TARGET, PARCELS, sum(BELOW_THE_STAR)));
        actual.put("Sum, Shipments index-keyed", SHIPMENTS_KEYED.verdict(FIXED_TARGET, SUMMARY, sum(BELOW_THE_STAR)));
        actual.put("Sum, Parcels index-keyed", PARCELS_KEYED.verdict(FIXED_TARGET, SUMMARY, sum(BELOW_THE_STAR)));
        actual.put("the group count into the fixed target",
                PLAIN.verdict(FIXED_TARGET, SUMMARY, countSeals("/Fulfilment/Shipments*/Parcels/Seal")));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("Sum into the fixed target", NO_WILDCARD);
        expected.put("Sum into a target in Shipments", NO_WILDCARD);
        expected.put("Sum into a target in Parcels, the level itself", NO_WILDCARD);
        expected.put("Sum, Shipments index-keyed", NO_WILDCARD);
        expected.put("Sum, Parcels index-keyed", NO_WILDCARD);
        expected.put("the group count into the fixed target", NO_WILDCARD);
        assertEquals(expected, actual, "an unstarred level below the first star");
    }

    @Test
    void aboveTheFirstStarWhoBindsTheOutermostLevelDecidesTheClass() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("declared in Shipments, relative", PLAIN.verdict(FIXED_TARGET, SHIPMENTS, sum(ABOVE_THE_STAR)));
        actual.put("declared in Shipments, absolute",
                PLAIN.verdict(FIXED_TARGET, SHIPMENTS, sumAbsolute(ABOVE_THE_STAR)));
        actual.put("declared in Summary", PLAIN.verdict(FIXED_TARGET, SUMMARY, sum(ABOVE_THE_STAR)));
        actual.put("declared in Summary, Shipments index-keyed",
                SHIPMENTS_KEYED.verdict(FIXED_TARGET, SUMMARY, sum(ABOVE_THE_STAR)));
        actual.put("declared in Summary, Parcels index-keyed",
                PARCELS_KEYED.verdict(FIXED_TARGET, SUMMARY, sum(ABOVE_THE_STAR)));
        actual.put("the group count, declared in Shipments",
                PLAIN.verdict(FIXED_TARGET, SHIPMENTS, countSeals("/Fulfilment/Shipments/Parcels*/Seal")));
        actual.put("the group count, declared in Summary",
                PLAIN.verdict(FIXED_TARGET, SUMMARY, countSeals("/Fulfilment/Shipments/Parcels*/Seal")));
        actual.put("the group count, declared in Summary, Shipments index-keyed",
                SHIPMENTS_KEYED.verdict(FIXED_TARGET, SUMMARY, countSeals("/Fulfilment/Shipments/Parcels*/Seal")));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("declared in Shipments, relative", NOT_IN_RULEGROUP);
        expected.put("declared in Shipments, absolute", NOT_IN_RULEGROUP);
        expected.put("declared in Summary", NO_WILDCARD);
        expected.put("declared in Summary, Shipments index-keyed", NO_INDEX);
        expected.put("declared in Summary, Parcels index-keyed", NO_WILDCARD);
        expected.put("the group count, declared in Shipments", NOT_IN_RULEGROUP);
        expected.put("the group count, declared in Summary", NO_WILDCARD);
        expected.put("the group count, declared in Summary, Shipments index-keyed", NO_INDEX);
        assertEquals(expected, actual, "an unstarred level above the first star, into a fixed target");
    }

    /**
     * The same operands into a target inside the level are admitted, so the binding decides and not the path.
     * The partial-binding row is the plain read of the deeper, index-keyed level from there: the target binds
     * `Shipments` and nothing binds `Parcels`, and the class stays the missing star.
     */
    @Test
    void aTargetInsideTheLevelAdmitsTheSameOperand() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("Sum", PLAIN.verdict(SHIPMENT_TARGET, SHIPMENTS, sum(ABOVE_THE_STAR)));
        actual.put("the group count",
                PLAIN.verdict(SHIPMENT_TARGET, SHIPMENTS, countSeals("/Fulfilment/Shipments/Parcels*/Seal")));
        actual.put("Sum, Shipments index-keyed",
                SHIPMENTS_KEYED.verdict(SHIPMENT_TARGET, SHIPMENTS, sum(ABOVE_THE_STAR)));
        actual.put("a plain read of the deeper level",
                PLAIN.verdict(SHIPMENT_TARGET, SHIPMENTS, plusOne(PARCELS + "/Weight")));
        actual.put("a plain read of the deeper level, Parcels index-keyed",
                PARCELS_KEYED.verdict(SHIPMENT_TARGET, SHIPMENTS, plusOne(PARCELS + "/Weight")));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("Sum", KernelLaws.ADMITTED);
        expected.put("the group count", KernelLaws.ADMITTED);
        expected.put("Sum, Shipments index-keyed", KernelLaws.ADMITTED);
        expected.put("a plain read of the deeper level", NO_WILDCARD);
        expected.put("a plain read of the deeper level, Parcels index-keyed", NO_WILDCARD);
        assertEquals(expected, actual, "a target inside the unstarred level");
    }

    /**
     * The group count's own repeatable level is walked like any other: unbound, it is the missing star, or the
     * missing index where the level declares one — the group itself as the level, and a level above a starred one.
     *
     * <p>The lone unstarred group is written as text because the typed count refuses it, a single group needing a
     * star; A12 refuses it too, and these rows measure which class it names.
     */
    @Test
    void aGroupOperandsOwnLevelFollowsTheSameTable() {
        String lone = "NumberOfFilledGroups(../Shipments)";
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("the group itself", PLAIN.draftVerdict(SUMMARY, "../Result", lone));
        actual.put("the group itself, index-keyed", SHIPMENTS_KEYED.draftVerdict(SUMMARY, "../Result", lone));
        actual.put("a starred group below it, index-keyed",
                SHIPMENTS_KEYED.verdict(FIXED_TARGET, SUMMARY, countSeals(PARCELS + "*")));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("the group itself", NO_WILDCARD);
        expected.put("the group itself, index-keyed", NO_INDEX);
        expected.put("a starred group below it, index-keyed", NO_INDEX);
        assertEquals(expected, actual, "the group count's own level, from Summary");
    }

    /** A plain read into the fixed target, where the OUTERMOST repeatable level it crosses decides. */
    @Test
    void aPlainReadFollowsTheSameTable() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("own-level read, declared in Shipments",
                PLAIN.verdict(FIXED_TARGET, SHIPMENTS, plusOne(SHIPMENTS + "/ShipWeight")));
        actual.put("own-level read, declared in Summary",
                PLAIN.verdict(FIXED_TARGET, SUMMARY, plusOne(SHIPMENTS + "/ShipWeight")));
        actual.put("own-level read, declared in Summary, Shipments index-keyed",
                SHIPMENTS_KEYED.verdict(FIXED_TARGET, SUMMARY, plusOne(SHIPMENTS + "/ShipWeight")));
        actual.put("deeper read, declared in Shipments",
                PLAIN.verdict(FIXED_TARGET, SHIPMENTS, plusOne(PARCELS + "/Weight")));
        actual.put("deeper read, declared in Shipments, Parcels index-keyed",
                PARCELS_KEYED.verdict(FIXED_TARGET, SHIPMENTS, plusOne(PARCELS + "/Weight")));
        actual.put("deeper read, declared in Summary, Parcels index-keyed",
                PARCELS_KEYED.verdict(FIXED_TARGET, SUMMARY, plusOne(PARCELS + "/Weight")));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("own-level read, declared in Shipments", NOT_IN_RULEGROUP);
        expected.put("own-level read, declared in Summary", NO_WILDCARD);
        expected.put("own-level read, declared in Summary, Shipments index-keyed", NO_INDEX);
        expected.put("deeper read, declared in Shipments", NOT_IN_RULEGROUP);
        expected.put("deeper read, declared in Shipments, Parcels index-keyed", NOT_IN_RULEGROUP);
        expected.put("deeper read, declared in Summary, Parcels index-keyed", NO_WILDCARD);
        assertEquals(expected, actual, "a plain read into the fixed target");
    }
}

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
 * KERNEL-FINDINGS §11 [KF466] — <b>a repeatable level a star leaves UNSTARRED is a per-row read</b>, on the
 * computation arm (reconciles a12-kernel-lean {@code SPEC-2026-09-24-03}, their report; every row measured here
 * on our own fixture).
 *
 * <p>Into a target OUTSIDE the declaring group containment answers, {@code MVK_ERROR_FIELD_NOT_IN_RULEGROUP}:
 * here that is every row declared in {@code Shipments} writing the fixed {@code Summary/Result}. With the target
 * inside the declaring group the level must be bound. Below the first star it is {@code MVK_NO_WILDCARD}
 * whatever binds it. Above the first star, or in a read with no star, a level the target's coordinates do not
 * bind is {@code MVK_NO_WILDCARD}, or {@code MVK_ERROR_FIELD_NO_INDEX} when it is the read's OUTERMOST
 * repeatable level and its group declares an index field.
 *
 * <p><b>What a row holds fixed.</b> The three models differ only in which group declares an index field; every
 * one carries both key fields, so the declaration is the one thing that moves between them. On this sheet the
 * declaring group and the target's containment move together: every row declared in {@code Shipments} into the
 * fixed target writes outside its group, and every row declared in {@code Summary} writes inside it.
 * {@link PartialStarContainmentLawsTest} holds the target outside and moves the declaring group alone, which is
 * what attributes those rows to containment rather than to the binding.
 *
 * <p>Each row's value is the target and operation as the kernel was handed them, then the verdict, so no
 * verdict is read off a spelling the renderer derived differently.
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
    private static final String SEALS_BELOW_THE_STAR = "/Fulfilment/Shipments*/Parcels/Seal";
    private static final String SEALS_ABOVE_THE_STAR = "/Fulfilment/Shipments/Parcels*/Seal";

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

        /** The target and operation as written from {@code group}, then the kernel's verdict on them. */
        String row(String target, String group, Function<BoundModel, Calculation> operation) {
            Computation computation = model.computation(model.num(target))
                    .name("BindingProbe")
                    .group(model.group(group))
                    .calc(operation.apply(model))
                    .message(Dsl.en("Star binding probe."))
                    .build();
            DraftComputation draft = computation.draft();
            return is(draft.computedFieldRelPath() + " = " + draft.alternatives().get(0).operation(),
                    KernelLaws.computationVerdict(json, computation));
        }

        /** The same, for an operation written as text because the typed surface refuses its shape. */
        String textRow(String group, String targetRelPath, String operation) {
            return is(targetRelPath + " = " + operation, KernelLaws.computationVerdict(json, new DraftComputation(
                    group, "BindingProbe", targetRelPath, null, List.of(new DraftComputation.Alternative(null, operation)))));
        }
    }

    private static String is(String spelling, String verdict) {
        return spelling + " → " + verdict;
    }

    private static Function<BoundModel, Calculation> sum(String starPath) {
        return m -> Dsl.sum(m.flatten(starPath)).expression();
    }

    private static Function<BoundModel, Calculation> sumAbsolute(String starPath) {
        return m -> Dsl.sum(m.flatten(starPath).absolute()).expression();
    }

    private static Function<BoundModel, Calculation> countGroups(String starGroupPath) {
        return m -> m.numberOfFilledGroups(m.flattenGroup(starGroupPath)).expression();
    }

    private static Function<BoundModel, Calculation> plusOne(String field) {
        return m -> m.num(field).asNumber().plus(1).expression();
    }

    /** Every row's target lies inside its declaring group, and the declaring group or the target moves. */
    @Test
    void belowTheFirstStarAContainedTargetRefusesTheLevelWhateverBindsIt() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("Summary into the fixed target", PLAIN.row(FIXED_TARGET, SUMMARY, sum(BELOW_THE_STAR)));
        actual.put("Shipments into a target in Shipments", PLAIN.row(SHIPMENT_TARGET, SHIPMENTS, sum(BELOW_THE_STAR)));
        actual.put("Parcels into a target in Parcels, the level itself",
                PLAIN.row(PARCEL_TARGET, PARCELS, sum(BELOW_THE_STAR)));
        actual.put("Summary, Shipments index-keyed", SHIPMENTS_KEYED.row(FIXED_TARGET, SUMMARY, sum(BELOW_THE_STAR)));
        actual.put("Summary, Parcels index-keyed", PARCELS_KEYED.row(FIXED_TARGET, SUMMARY, sum(BELOW_THE_STAR)));
        actual.put("Summary, the group count", PLAIN.row(FIXED_TARGET, SUMMARY, countGroups(SEALS_BELOW_THE_STAR)));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("Summary into the fixed target", is("../Result = Sum(../Shipments*/Parcels/Weight)", NO_WILDCARD));
        expected.put("Shipments into a target in Shipments",
                is("../ShipResult = Sum(../Shipments*/Parcels/Weight)", NO_WILDCARD));
        expected.put("Parcels into a target in Parcels, the level itself",
                is("../Share = Sum(../../Shipments*/Parcels/Weight)", NO_WILDCARD));
        expected.put("Summary, Shipments index-keyed", is("../Result = Sum(../Shipments*/Parcels/Weight)", NO_WILDCARD));
        expected.put("Summary, Parcels index-keyed", is("../Result = Sum(../Shipments*/Parcels/Weight)", NO_WILDCARD));
        expected.put("Summary, the group count",
                is("../Result = NumberOfFilledGroups(../Shipments*/Parcels/Seal)", NO_WILDCARD));
        assertEquals(expected, actual, "an unstarred level below the first star, into a contained target");
    }

    /**
     * From {@code Shipments} the fixed target lies outside the declaring group, so containment answers, relative
     * or absolute; from {@code Summary} it lies inside, so the walk does, and the unbound outermost level is the
     * missing star or the missing index by its own group's declaration.
     */
    @Test
    void aboveTheFirstStarContainmentAnswersBeforeTheOutermostLevel() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("Shipments, relative", PLAIN.row(FIXED_TARGET, SHIPMENTS, sum(ABOVE_THE_STAR)));
        actual.put("Shipments, absolute", PLAIN.row(FIXED_TARGET, SHIPMENTS, sumAbsolute(ABOVE_THE_STAR)));
        actual.put("Summary", PLAIN.row(FIXED_TARGET, SUMMARY, sum(ABOVE_THE_STAR)));
        actual.put("Summary, Shipments index-keyed", SHIPMENTS_KEYED.row(FIXED_TARGET, SUMMARY, sum(ABOVE_THE_STAR)));
        actual.put("Summary, Parcels index-keyed", PARCELS_KEYED.row(FIXED_TARGET, SUMMARY, sum(ABOVE_THE_STAR)));
        actual.put("Shipments, the group count", PLAIN.row(FIXED_TARGET, SHIPMENTS, countGroups(SEALS_ABOVE_THE_STAR)));
        actual.put("Summary, the group count", PLAIN.row(FIXED_TARGET, SUMMARY, countGroups(SEALS_ABOVE_THE_STAR)));
        actual.put("Summary, the group count, Shipments index-keyed",
                SHIPMENTS_KEYED.row(FIXED_TARGET, SUMMARY, countGroups(SEALS_ABOVE_THE_STAR)));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("Shipments, relative", is("../../Summary/Result = Sum(Parcels*/Weight)", NOT_IN_RULEGROUP));
        expected.put("Shipments, absolute",
                is("../../Summary/Result = Sum(/Fulfilment/Shipments/Parcels*/Weight)", NOT_IN_RULEGROUP));
        expected.put("Summary", is("../Result = Sum(../Shipments/Parcels*/Weight)", NO_WILDCARD));
        expected.put("Summary, Shipments index-keyed", is("../Result = Sum(../Shipments/Parcels*/Weight)", NO_INDEX));
        expected.put("Summary, Parcels index-keyed", is("../Result = Sum(../Shipments/Parcels*/Weight)", NO_WILDCARD));
        expected.put("Shipments, the group count",
                is("../../Summary/Result = NumberOfFilledGroups(Parcels*/Seal)", NOT_IN_RULEGROUP));
        expected.put("Summary, the group count",
                is("../Result = NumberOfFilledGroups(../Shipments/Parcels*/Seal)", NO_WILDCARD));
        expected.put("Summary, the group count, Shipments index-keyed",
                is("../Result = NumberOfFilledGroups(../Shipments/Parcels*/Seal)", NO_INDEX));
        assertEquals(expected, actual, "an unstarred level above the first star, into the fixed target");
    }

    /**
     * Into a target inside {@code Shipments}, declared there, the same operands are admitted: with the target
     * contained, what decides is whether its coordinates bind the level. The partial-binding rows read the deeper
     * level plainly: the target binds {@code Shipments}, nothing binds {@code Parcels}, and the class stays the
     * missing star with either level index-keyed, so the index class needs the read's outermost level unbound.
     */
    @Test
    void aTargetThatBindsTheLevelAdmitsTheSameOperand() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("Sum", PLAIN.row(SHIPMENT_TARGET, SHIPMENTS, sum(ABOVE_THE_STAR)));
        actual.put("the group count", PLAIN.row(SHIPMENT_TARGET, SHIPMENTS, countGroups(SEALS_ABOVE_THE_STAR)));
        actual.put("Sum, Shipments index-keyed", SHIPMENTS_KEYED.row(SHIPMENT_TARGET, SHIPMENTS, sum(ABOVE_THE_STAR)));
        actual.put("a plain read of the deeper level", PLAIN.row(SHIPMENT_TARGET, SHIPMENTS, plusOne(PARCELS + "/Weight")));
        actual.put("a plain read of the deeper level, Parcels index-keyed",
                PARCELS_KEYED.row(SHIPMENT_TARGET, SHIPMENTS, plusOne(PARCELS + "/Weight")));
        actual.put("a plain read of the deeper level, Shipments index-keyed",
                SHIPMENTS_KEYED.row(SHIPMENT_TARGET, SHIPMENTS, plusOne(PARCELS + "/Weight")));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("Sum", is("../ShipResult = Sum(Parcels*/Weight)", KernelLaws.ADMITTED));
        expected.put("the group count", is("../ShipResult = NumberOfFilledGroups(Parcels*/Seal)", KernelLaws.ADMITTED));
        expected.put("Sum, Shipments index-keyed", is("../ShipResult = Sum(Parcels*/Weight)", KernelLaws.ADMITTED));
        expected.put("a plain read of the deeper level", is("../ShipResult = [Parcels/Weight] + 1", NO_WILDCARD));
        expected.put("a plain read of the deeper level, Parcels index-keyed",
                is("../ShipResult = [Parcels/Weight] + 1", NO_WILDCARD));
        expected.put("a plain read of the deeper level, Shipments index-keyed",
                is("../ShipResult = [Parcels/Weight] + 1", NO_WILDCARD));
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
        actual.put("the group itself", PLAIN.textRow(SUMMARY, "../Result", lone));
        actual.put("the group itself, index-keyed", SHIPMENTS_KEYED.textRow(SUMMARY, "../Result", lone));
        actual.put("a starred group below it", PLAIN.row(FIXED_TARGET, SUMMARY, countGroups(PARCELS + "*")));
        actual.put("a starred group below it, index-keyed",
                SHIPMENTS_KEYED.row(FIXED_TARGET, SUMMARY, countGroups(PARCELS + "*")));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("the group itself", is("../Result = " + lone, NO_WILDCARD));
        expected.put("the group itself, index-keyed", is("../Result = " + lone, NO_INDEX));
        expected.put("a starred group below it", is("../Result = NumberOfFilledGroups(../Shipments/Parcels*)", NO_WILDCARD));
        expected.put("a starred group below it, index-keyed",
                is("../Result = NumberOfFilledGroups(../Shipments/Parcels*)", NO_INDEX));
        assertEquals(expected, actual, "the group count's own level, from Summary");
    }

    /**
     * A plain read into the fixed target. From {@code Shipments} containment answers, an index on the deeper
     * level moving nothing; from {@code Summary} the read's OUTERMOST repeatable level picks the class.
     */
    @Test
    void aPlainReadFollowsTheSameTable() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("own-level read, Shipments", PLAIN.row(FIXED_TARGET, SHIPMENTS, plusOne(SHIPMENTS + "/ShipWeight")));
        actual.put("own-level read, Summary", PLAIN.row(FIXED_TARGET, SUMMARY, plusOne(SHIPMENTS + "/ShipWeight")));
        actual.put("own-level read, Summary, Shipments index-keyed",
                SHIPMENTS_KEYED.row(FIXED_TARGET, SUMMARY, plusOne(SHIPMENTS + "/ShipWeight")));
        actual.put("deeper read, Shipments", PLAIN.row(FIXED_TARGET, SHIPMENTS, plusOne(PARCELS + "/Weight")));
        actual.put("deeper read, Shipments, Parcels index-keyed",
                PARCELS_KEYED.row(FIXED_TARGET, SHIPMENTS, plusOne(PARCELS + "/Weight")));
        actual.put("deeper read, Summary, Parcels index-keyed",
                PARCELS_KEYED.row(FIXED_TARGET, SUMMARY, plusOne(PARCELS + "/Weight")));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("own-level read, Shipments", is("../../Summary/Result = [ShipWeight] + 1", NOT_IN_RULEGROUP));
        expected.put("own-level read, Summary", is("../Result = [../Shipments/ShipWeight] + 1", NO_WILDCARD));
        expected.put("own-level read, Summary, Shipments index-keyed",
                is("../Result = [../Shipments/ShipWeight] + 1", NO_INDEX));
        expected.put("deeper read, Shipments", is("../../Summary/Result = [Parcels/Weight] + 1", NOT_IN_RULEGROUP));
        expected.put("deeper read, Shipments, Parcels index-keyed",
                is("../../Summary/Result = [Parcels/Weight] + 1", NOT_IN_RULEGROUP));
        expected.put("deeper read, Summary, Parcels index-keyed",
                is("../Result = [../Shipments/Parcels/Weight] + 1", NO_WILDCARD));
        assertEquals(expected, actual, "a plain read into the fixed target");
    }
}

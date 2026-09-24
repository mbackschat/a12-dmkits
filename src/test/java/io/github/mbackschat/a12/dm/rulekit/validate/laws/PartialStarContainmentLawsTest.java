package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Calculation;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;

/**
 * KERNEL-FINDINGS §11 [KF466] — <b>a star that leaves ANY repeatable level of its path unstarred is a per-row read
 * for {@code MVK_ERROR_FIELD_NOT_IN_RULEGROUP}</b>, on both constructs, whichever side of the first star the level
 * sits and whether or not the declaring group binds it. Only a star on every level reads no row.
 *
 * <p>This is the cell {@link ComputationStarBindingLawsTest}'s fixed-target rows could not isolate: each of them
 * held the target CONTAINED whenever the declaring group bound nothing, so "the declaring group binds the level"
 * and "the target lies outside the declaring group" moved together. Here the declaring group is the variable —
 * {@code Shipments} is the level itself, {@code Ledger} is a fixed sibling that binds nothing — and each
 * uncontained row has its contained twin on this same sheet, declared in {@code Summary}, which is the
 * within-model control: one builder call apart, the class changes.
 *
 * <p><b>Containment answers ahead of the index class.</b> The keyed row is the one where the same operand into
 * a contained target draws {@code MVK_ERROR_FIELD_NO_INDEX}; uncontained, the index declaration moves nothing, as
 * it moves nothing for a plain per-row read ({@code ErrorTargetContainmentLawsTest}).
 *
 * <p>Each row's value is the spelling the kernel was handed, then the verdict.
 *
 * <p>Own domain (an e-commerce fulfilment sheet).
 */
class PartialStarContainmentLawsTest {

    private static final String NOT_IN_RULEGROUP = "MVK_ERROR_FIELD_NOT_IN_RULEGROUP";
    private static final String NO_WILDCARD = "MVK_NO_WILDCARD";
    private static final String NO_INDEX = "MVK_ERROR_FIELD_NO_INDEX";

    private static final Sheet PLAIN = new Sheet(false);
    private static final Sheet SHIPMENTS_KEYED = new Sheet(true);

    private static final String SUMMARY = "/Fulfilment/Summary";
    private static final String SHIPMENTS = "/Fulfilment/Shipments";
    private static final String LEDGER = "/Fulfilment/Ledger";
    private static final String TARGET = "/Fulfilment/Summary/Result";
    private static final String SHIPMENT_TARGET = SHIPMENTS + "/ShipResult";

    private static final String ABOVE = "/Fulfilment/Shipments/Parcels*/Weight";
    private static final String BELOW = "/Fulfilment/Shipments*/Parcels/Weight";
    private static final String EVERY = "/Fulfilment/Shipments*/Parcels*/Weight";
    private static final String SEALS_ABOVE = "/Fulfilment/Shipments/Parcels*/Seal";
    private static final String SEALS_BELOW = "/Fulfilment/Shipments*/Parcels/Seal";

    /**
     * {@link ComputationStarBindingLawsTest}'s fulfilment sheet with one fixed {@code Ledger} added beside
     * {@code Summary}, and nothing else; the flag declares {@code ShipKey} as {@code Shipments}' index.
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

        /** A computation of {@code target} declared in {@code group}: its spelling, then the kernel's verdict. */
        String computation(String group, String target, Calculation operation) {
            Computation computation = model.computation(model.num(target))
                    .name("ContainmentProbe")
                    .group(model.group(group))
                    .calc(operation)
                    .message(Dsl.en("Containment probe."))
                    .build();
            DraftComputation draft = computation.draft();
            return is(draft.computedFieldRelPath() + " = " + draft.alternatives().get(0).operation(),
                    KernelLaws.computationVerdict(json, computation));
        }

        String computation(String group, Calculation operation) {
            return computation(group, TARGET, operation);
        }

        /** The same, for an operation written as text because the typed surface refuses its shape. */
        String textComputation(String group, String targetRelPath, String operation) {
            return is(targetRelPath + " = " + operation, KernelLaws.computationVerdict(json, new DraftComputation(
                    group, "ContainmentProbe", targetRelPath, null,
                    List.of(new DraftComputation.Alternative(null, operation)))));
        }

        /**
         * A rule reporting on the target, which its condition reaches through the non-repeatable root. The error
         * field is written as text because {@code Rule.Builder.build()} refuses an uncontained one outright
         * (FEATURE-GAPS §4), which is the very shape measured; both declaring groups sit one level below the root.
         */
        String rule(String group, Condition read) {
            Condition condition = model.field(TARGET).filled().and(read);
            return is(KernelLaws.render(group, condition),
                    KernelLaws.verdictModel(json, group, "../../Summary/Result", condition));
        }

        Calculation sum(String starPath) {
            return Dsl.sum(model.flatten(starPath)).expression();
        }

        Condition sumIsPositive(String starPath) {
            return Dsl.sum(model.flatten(starPath)).gt(0);
        }

        Calculation countGroups(String starGroupPath) {
            return model.numberOfFilledGroups(model.flattenGroup(starGroupPath)).expression();
        }

        Condition someGroupCounted(String starGroupPath) {
            return model.numberOfFilledGroups(model.flattenGroup(starGroupPath)).gt(0);
        }
    }

    private static String is(String spelling, String verdict) {
        return spelling + " → " + verdict;
    }

    @Test
    void onTheComputationArmEveryUnstarredLevelIsPerRow() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("Ledger | Sum, above the first star", PLAIN.computation(LEDGER, PLAIN.sum(ABOVE)));
        actual.put("Summary | Sum, above, contained — control", PLAIN.computation(SUMMARY, PLAIN.sum(ABOVE)));
        actual.put("Ledger | Sum, below the first star", PLAIN.computation(LEDGER, PLAIN.sum(BELOW)));
        actual.put("Shipments | Sum, below the first star", PLAIN.computation(SHIPMENTS, PLAIN.sum(BELOW)));
        actual.put("Summary | Sum, below, contained — control", PLAIN.computation(SUMMARY, PLAIN.sum(BELOW)));
        actual.put("Ledger | the group count, above", PLAIN.computation(LEDGER, PLAIN.countGroups(SEALS_ABOVE)));
        actual.put("Ledger | the group count, below", PLAIN.computation(LEDGER, PLAIN.countGroups(SEALS_BELOW)));
        actual.put("Ledger | Sum, above, Shipments index-keyed",
                SHIPMENTS_KEYED.computation(LEDGER, SHIPMENTS_KEYED.sum(ABOVE)));
        actual.put("Summary | Sum, above, Shipments index-keyed, contained — control",
                SHIPMENTS_KEYED.computation(SUMMARY, SHIPMENTS_KEYED.sum(ABOVE)));
        actual.put("Ledger | Sum, every level starred — control", PLAIN.computation(LEDGER, PLAIN.sum(EVERY)));
        actual.put("Shipments | Sum, every level starred — control", PLAIN.computation(SHIPMENTS, PLAIN.sum(EVERY)));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("Ledger | Sum, above the first star",
                is("../../Summary/Result = Sum(../Shipments/Parcels*/Weight)", NOT_IN_RULEGROUP));
        expected.put("Summary | Sum, above, contained — control",
                is("../Result = Sum(../Shipments/Parcels*/Weight)", NO_WILDCARD));
        expected.put("Ledger | Sum, below the first star",
                is("../../Summary/Result = Sum(../Shipments*/Parcels/Weight)", NOT_IN_RULEGROUP));
        expected.put("Shipments | Sum, below the first star",
                is("../../Summary/Result = Sum(../Shipments*/Parcels/Weight)", NOT_IN_RULEGROUP));
        expected.put("Summary | Sum, below, contained — control",
                is("../Result = Sum(../Shipments*/Parcels/Weight)", NO_WILDCARD));
        expected.put("Ledger | the group count, above",
                is("../../Summary/Result = NumberOfFilledGroups(../Shipments/Parcels*/Seal)", NOT_IN_RULEGROUP));
        expected.put("Ledger | the group count, below",
                is("../../Summary/Result = NumberOfFilledGroups(../Shipments*/Parcels/Seal)", NOT_IN_RULEGROUP));
        expected.put("Ledger | Sum, above, Shipments index-keyed",
                is("../../Summary/Result = Sum(../Shipments/Parcels*/Weight)", NOT_IN_RULEGROUP));
        expected.put("Summary | Sum, above, Shipments index-keyed, contained — control",
                is("../Result = Sum(../Shipments/Parcels*/Weight)", NO_INDEX));
        expected.put("Ledger | Sum, every level starred — control",
                is("../../Summary/Result = Sum(../Shipments*/Parcels*/Weight)", KernelLaws.ADMITTED));
        expected.put("Shipments | Sum, every level starred — control",
                is("../../Summary/Result = Sum(../Shipments*/Parcels*/Weight)", KernelLaws.ADMITTED));
        assertEquals(expected, actual, "a partial star into a target outside the declaring group");
    }

    /**
     * A target that binds the unstarred level does not rescue a declaring group it lies outside: its own
     * repeatable scope already derives the iteration there, so even the fully starred control is refused
     * ([KF212]'s second source).
     */
    @Test
    void aTargetBindingTheLevelFromOutsideTheDeclaringGroupIsRefusedToo() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("Ledger | Sum, above", PLAIN.computation(LEDGER, SHIPMENT_TARGET, PLAIN.sum(ABOVE)));
        actual.put("Ledger | Sum, every level starred", PLAIN.computation(LEDGER, SHIPMENT_TARGET, PLAIN.sum(EVERY)));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("Ledger | Sum, above",
                is("../../Shipments/ShipResult = Sum(../Shipments/Parcels*/Weight)", NOT_IN_RULEGROUP));
        expected.put("Ledger | Sum, every level starred",
                is("../../Shipments/ShipResult = Sum(../Shipments*/Parcels*/Weight)", NOT_IN_RULEGROUP));
        assertEquals(expected, actual, "into a repeatable target outside the declaring group");
    }

    /**
     * A leaf inside a {@code Having} filter and a semantic-index read, the two unstarred spellings that do not
     * read the path they name one row at a time. Each has its contained twin, declared in {@code Summary}.
     */
    @Test
    void aFilterLeafAndAKeyedReadAreNotPerRowReads() {
        String filtered = "Sum(../Shipments*/ShipWeight Having [../Shipments/ShipKey] == \"X\")";
        String keyed = "[../Shipments/ShipWeight For \"A\"] + 1";
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("Ledger | a plain unstarred read — the other control",
                PLAIN.textComputation(LEDGER, "../../Summary/Result", "[../Shipments/ShipWeight] + 1"));
        actual.put("Ledger | a starred aggregate — the control",
                PLAIN.textComputation(LEDGER, "../../Summary/Result", "Sum(../Shipments*/ShipWeight)"));
        actual.put("Ledger | the same, filtered", PLAIN.textComputation(LEDGER, "../../Summary/Result", filtered));
        actual.put("Summary | the same, filtered, contained",
                PLAIN.textComputation(SUMMARY, "../Result", filtered));
        actual.put("Ledger | a keyed read", SHIPMENTS_KEYED.textComputation(LEDGER, "../../Summary/Result", keyed));
        actual.put("Summary | a keyed read, contained", SHIPMENTS_KEYED.textComputation(SUMMARY, "../Result", keyed));
        actual.put("Ledger | rule, the same filtered aggregate", is(filtered, KernelLaws.verdictModel(PLAIN.json(),
                LEDGER, "../../Summary/Result", "FieldFilled(../Summary/Result) And " + filtered + " > 0")));
        actual.put("Ledger | rule, a keyed read", is("[../Shipments/ShipWeight For \"A\"] > 0", KernelLaws.verdictModel(SHIPMENTS_KEYED.json(), LEDGER,
                "../../Summary/Result", "FieldFilled(../Summary/Result) And [../Shipments/ShipWeight For \"A\"] > 0")));

        Map<String, String> expected = new LinkedHashMap<>();
        String admitted = KernelLaws.ADMITTED;
        expected.put("Ledger | a plain unstarred read — the other control",
                is("../../Summary/Result = [../Shipments/ShipWeight] + 1", NOT_IN_RULEGROUP));
        expected.put("Ledger | a starred aggregate — the control",
                is("../../Summary/Result = Sum(../Shipments*/ShipWeight)", admitted));
        // The filter's own leaf names `Shipments` unstarred and reads no row of it: the filter is about the
        // starred extent, so the construct stays non-iterating and may write outside its group.
        expected.put("Ledger | the same, filtered", is("../../Summary/Result = " + filtered, admitted));
        expected.put("Summary | the same, filtered, contained", is("../Result = " + filtered, admitted));
        // …and a semantic-index key picks one row by VALUE, not by position, so it iterates nothing either.
        expected.put("Ledger | a keyed read", is("../../Summary/Result = " + keyed, admitted));
        expected.put("Summary | a keyed read, contained", is("../Result = " + keyed, admitted));
        expected.put("Ledger | rule, the same filtered aggregate", is(filtered, admitted));
        expected.put("Ledger | rule, a keyed read", is("[../Shipments/ShipWeight For \"A\"] > 0", admitted));
        assertEquals(expected, actual, "a filter leaf and a keyed read");
    }

    /**
     * A star on a level that does not repeat is the wildcard gate's, which answers during resolution, so it is
     * not a star that spends itself on a repeatable level and says nothing about which rows the read takes.
     */
    @Test
    void aStarOnALevelThatDoesNotRepeatIsTheWildcardGatesAlone() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("Ledger | a star on the field", PLAIN.textComputation(LEDGER, "../../Summary/Result",
                "Sum(../Shipments/Parcels/Weight*)"));
        actual.put("Summary | the same, contained", PLAIN.textComputation(SUMMARY, "../Result",
                "Sum(../Shipments/Parcels/Weight*)"));
        actual.put("Ledger | a star on the fixed group", PLAIN.textComputation(LEDGER, "../../Summary/Result",
                "NumberOfFilledGroups(../Shipments/Parcels/Seal*)"));
        actual.put("Ledger | a star on each level and on the field", PLAIN.textComputation(LEDGER,
                "../../Summary/Result", "Sum(../Shipments*/Parcels*/Weight*)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("Ledger | a star on the field",
                is("../../Summary/Result = Sum(../Shipments/Parcels/Weight*)", "MVK_INVALID_WILDCARD"));
        expected.put("Summary | the same, contained",
                is("../Result = Sum(../Shipments/Parcels/Weight*)", "MVK_INVALID_WILDCARD"));
        expected.put("Ledger | a star on the fixed group",
                is("../../Summary/Result = NumberOfFilledGroups(../Shipments/Parcels/Seal*)", "MVK_INVALID_WILDCARD"));
        expected.put("Ledger | a star on each level and on the field",
                is("../../Summary/Result = Sum(../Shipments*/Parcels*/Weight*)", "MVK_INVALID_WILDCARD"));
        assertEquals(expected, actual, "a star on a level that does not repeat");
    }

    @Test
    void onTheRuleArmTheSameReadsArePerRow() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("Shipments | Sum, above", PLAIN.rule(SHIPMENTS, PLAIN.sumIsPositive(ABOVE)));
        actual.put("Shipments | Sum, below", PLAIN.rule(SHIPMENTS, PLAIN.sumIsPositive(BELOW)));
        actual.put("Shipments | the group count, above", PLAIN.rule(SHIPMENTS, PLAIN.someGroupCounted(SEALS_ABOVE)));
        actual.put("Shipments | the group count, below", PLAIN.rule(SHIPMENTS, PLAIN.someGroupCounted(SEALS_BELOW)));
        actual.put("Ledger | Sum, above", PLAIN.rule(LEDGER, PLAIN.sumIsPositive(ABOVE)));
        actual.put("Ledger | Sum, below", PLAIN.rule(LEDGER, PLAIN.sumIsPositive(BELOW)));
        actual.put("Ledger | the group count, above", PLAIN.rule(LEDGER, PLAIN.someGroupCounted(SEALS_ABOVE)));
        actual.put("Ledger | the group count, below", PLAIN.rule(LEDGER, PLAIN.someGroupCounted(SEALS_BELOW)));
        actual.put("Shipments | Sum, every level starred — control", PLAIN.rule(SHIPMENTS, PLAIN.sumIsPositive(EVERY)));
        actual.put("Ledger | Sum, every level starred — control", PLAIN.rule(LEDGER, PLAIN.sumIsPositive(EVERY)));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("Shipments | Sum, above",
                is("FieldFilled(../Summary/Result) And Sum(Parcels*/Weight) > 0", NOT_IN_RULEGROUP));
        expected.put("Shipments | Sum, below",
                is("FieldFilled(../Summary/Result) And Sum(../Shipments*/Parcels/Weight) > 0", NOT_IN_RULEGROUP));
        expected.put("Shipments | the group count, above",
                is("FieldFilled(../Summary/Result) And NumberOfFilledGroups(Parcels*/Seal) > 0", NOT_IN_RULEGROUP));
        expected.put("Shipments | the group count, below", is(
                "FieldFilled(../Summary/Result) And NumberOfFilledGroups(../Shipments*/Parcels/Seal) > 0",
                NOT_IN_RULEGROUP));
        expected.put("Ledger | Sum, above",
                is("FieldFilled(../Summary/Result) And Sum(../Shipments/Parcels*/Weight) > 0", NOT_IN_RULEGROUP));
        expected.put("Ledger | Sum, below",
                is("FieldFilled(../Summary/Result) And Sum(../Shipments*/Parcels/Weight) > 0", NOT_IN_RULEGROUP));
        expected.put("Ledger | the group count, above", is(
                "FieldFilled(../Summary/Result) And NumberOfFilledGroups(../Shipments/Parcels*/Seal) > 0",
                NOT_IN_RULEGROUP));
        expected.put("Ledger | the group count, below", is(
                "FieldFilled(../Summary/Result) And NumberOfFilledGroups(../Shipments*/Parcels/Seal) > 0",
                NOT_IN_RULEGROUP));
        expected.put("Shipments | Sum, every level starred — control",
                is("FieldFilled(../Summary/Result) And Sum(../Shipments*/Parcels*/Weight) > 0", KernelLaws.ADMITTED));
        expected.put("Ledger | Sum, every level starred — control",
                is("FieldFilled(../Summary/Result) And Sum(../Shipments*/Parcels*/Weight) > 0", KernelLaws.ADMITTED));
        assertEquals(expected, actual, "a partial star in a rule reporting outside its group");
    }
}

package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

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

        /**
         * The same rule over a carrier written as text, for the grid below: the twenty-odd carriers have no one
         * typed factory to vary, and the text is what the kernel is handed either way.
         */
        String textRule(String group, String read) {
            String condition = "FieldFilled(" + TARGET + ") And " + read;
            return is(condition, KernelLaws.verdictModel(json, group, "../../Summary/Result", condition));
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

    /** Every validation star carrier, over a level list {@code %s} that is starred or not below the first star. */
    private static final List<String> CARRIERS = List.of(
            "Sum(%s/Weight) > 0",
            "MaxValue(%s/Weight) > 0",
            "FirstFilledValue(%s/Weight) > 0",
            "NumberOfValueInFields(0 In %s/Weight) > 0",
            "NumberOfFilledGroups(%s/Seal) > 0",
            "AtLeastOneFieldFilled(%s/Weight)",
            "AllFieldsFilled(%s/Weight)",
            "NoFieldFilled(%s/Weight)",
            "NotAllFieldsFilled(%s/Weight)",
            "FieldsNotCollectivelyFilled(%s/Weight)",
            "NotExactlyOneFieldFilled(%s/Weight)",
            "MoreThanOneFieldFilled(%s/Weight)",
            "NumberOfFilledFields(%s/Weight) > 0",
            "AtLeastOneFieldValueIncludedInValueList(%s/ParcelKey In \"X\")",
            "NoFieldValueIncludedInValueList(%s/ParcelKey In \"X\")",
            "NotAllFieldValuesIncludedInValueList(%s/ParcelKey In \"X\")",
            "AtLeastOneGroupFilled(%s/Seal)",
            "NoGroupFilled(%s/Seal)",
            "AllGroupsFilled(%s/Seal)",
            "NotAllGroupsFilled(%s/Seal)",
            "GroupsNotCollectivelyFilled(%s/Seal)",
            "FieldValuesNotUnique(%s/ParcelKey)",
            "NumberOfDifferentValues(%s/ParcelKey) < 2");

    /** The carriers that forbid a star in their operand, so their fully starred control draws that bar. */
    private static final List<String> STAR_FORBIDDING = List.of(
            "AllGroupsFilled(%s/Seal)", "NotAllGroupsFilled(%s/Seal)", "GroupsNotCollectivelyFilled(%s/Seal)");

    /**
     * a12-kernel-lean's {@code SPEC-2026-09-24-06}, measured here: every validation star carrier reads a level left
     * unstarred below the first star per row, so a rule group the error field lies outside draws containment and
     * the one it lies in draws the missing star — not only {@code Sum} and the group count, which the rule-arm test
     * above carries. The fully starred row is each carrier's control, so a class the carrier draws of its own
     * cannot pass for the placement.
     */
    @Test
    void everyValidationStarCarrierPlacesAnUnstarredLevelBelowTheFirstStar() {
        Map<String, String> actual = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();
        CARRIERS.forEach(carrier -> {
            String below = carrier.formatted("/Fulfilment/Shipments*/Parcels");
            String every = carrier.formatted("/Fulfilment/Shipments*/Parcels*");
            String control =
                    STAR_FORBIDDING.contains(carrier) ? "MVK_NO_WILDCARDS_G_ALLOWED" : KernelLaws.ADMITTED;
            for (String group : List.of(SHIPMENTS, LEDGER)) {
                String from = group.substring(group.lastIndexOf('/') + 1);
                actual.put(from + " | " + below, PLAIN.textRule(group, below));
                expected.put(from + " | " + below, is("FieldFilled(" + TARGET + ") And " + below, NOT_IN_RULEGROUP));
                actual.put(from + " | " + every + " — control", PLAIN.textRule(group, every));
                expected.put(from + " | " + every + " — control",
                    is("FieldFilled(" + TARGET + ") And " + every, control));
            }
            actual.put("Summary | " + below, PLAIN.textRule(SUMMARY, below));
            expected.put("Summary | " + below, is("FieldFilled(" + TARGET + ") And " + below, NO_WILDCARD));
        });
        assertEquals(expected, actual, "the placed class on every validation star carrier");
    }

    /** Calls whose operator runs a gate of its own, each with an operand A12 refuses while reading it. */
    private static final Map<String, String> GATE_BEHIND_A_REFUSED_READ = Map.of(
            "the scalar star bar", "GroupFilled(/Fulfilment/Shipments*/Parcels/Seal)",
            "the scalar star bar, negated", "GroupNotFilled(/Fulfilment/Shipments/Parcels*/Seal)",
            "the group-list star bar", "AllGroupsFilled(/Fulfilment/Shipments/Parcels*/Seal)",
            "the root gate", "AllGroupsFilled(/Fulfilment/Shipments*/Parcels/Seal, /Fulfilment)",
            "the duplicate gate",
                    "AllGroupsFilled(/Fulfilment/Shipments/Parcels/Seal, /Fulfilment/Shipments/Parcels/Seal)",
            "the overlap gate", "AtLeastOneGroupFilled(/Fulfilment, /Fulfilment/Shipments/Parcels/Seal)",
            "the field-kind gate", "GroupFilled(/Fulfilment/Shipments/ShipWeight)",
            "the field-kind gate on the count", "NumberOfFilledGroups(/Fulfilment/Shipments/ShipWeight) > 0");

    /** …and each gate's control: the same gate over operands A12 reads, where it answers. */
    private static final Map<String, String[]> GATE_ON_A_READ_OPERAND = Map.of(
            "the scalar star bar",
                    new String[] {"GroupFilled(/Fulfilment/Shipments*/Parcels*/Seal)", "MVK_NO_WILDCARDS_ALLOWED"},
            "the group-list star bar",
                    new String[] {
                        "AllGroupsFilled(/Fulfilment/Shipments*/Parcels*/Seal)", "MVK_NO_WILDCARDS_G_ALLOWED"},
            "the root gate", new String[] {"AllGroupsFilled(/Fulfilment)", "MVK_ROOT_GROUP_REFERENCED"},
            "the duplicate gate",
                    new String[] {"AllGroupsFilled(/Fulfilment/Ledger, /Fulfilment/Ledger)", "MVK_DUPLICATE_PARAM1"},
            "the overlap gate",
                    new String[] {"AtLeastOneGroupFilled(/Fulfilment, /Fulfilment/Ledger)", "MVK_DUPLICATE_PARAM2"},
            "the field-kind gate", new String[] {"GroupFilled(/Fulfilment/Ledger/Total)", "MVK_NO_FIELDS_ALLOWED"});

    /**
     * An operator's own gates run only once its operands have been READ, so an operand the read refuses answers
     * for the whole call. A12 checks each operand's entity spec as it parses it, and
     * {@code CheckKontextListenBedingungImpl.check} then gets a list whose members have already been read. The rows
     * separate that from the rival order, where the operator's gate would answer first: every gate below is
     * silent, from a rule group the error field lies outside (containment) and from the one it lies in (the
     * missing star), and every control shows the same gate answering over an operand A12 reads.
     */
    @Test
    void anOperatorsOwnGatesWaitForItsOperandsRead() {
        Map<String, String> actual = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();
        new TreeMap<>(GATE_BEHIND_A_REFUSED_READ).forEach((gate, read) -> {
            actual.put(gate + " | from Ledger", PLAIN.textRule(LEDGER, read));
            expected.put(gate + " | from Ledger", is("FieldFilled(" + TARGET + ") And " + read, NOT_IN_RULEGROUP));
            actual.put(gate + " | from Summary", PLAIN.textRule(SUMMARY, read));
            expected.put(gate + " | from Summary", is("FieldFilled(" + TARGET + ") And " + read, NO_WILDCARD));
        });
        new TreeMap<>(GATE_ON_A_READ_OPERAND).forEach((gate, row) -> {
            actual.put(gate + " | the control", PLAIN.textRule(SUMMARY, row[0]));
            expected.put(gate + " | the control", is("FieldFilled(" + TARGET + ") And " + row[0], row[1]));
        });
        assertEquals(expected, actual, "an operator's own gates behind a refused read");
    }

    /**
     * a12-kernel-lean's {@code SPEC-2026-09-24-05} asked for a BARE group operand with the error field outside the
     * rule group: {@link GroupOperandRepetitionScopeLawsTest}'s carriers over the repeatable {@code Shipments}
     * itself, unstarred, with the fixed {@code Summary} as the second operand where a carrier takes two.
     * Containment answers from both groups the error field lies outside, as it does for a field read, and the
     * missing star from the one it lies in.
     */
    @Test
    void aBareRepeatableGroupOperandIsReadPerRowToo() {
        Map<String, String> actual = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();
        GroupOperandRepetitionScopeLawsTest.CARRIERS.forEach(carrier -> {
            String read = carrier.replace("Claims", SHIPMENTS).replace("Insured", SUMMARY);
            for (String group : List.of(SHIPMENTS, LEDGER)) {
                String from = group.substring(group.lastIndexOf('/') + 1);
                actual.put(from + " | " + read, PLAIN.textRule(group, read));
                expected.put(from + " | " + read, is("FieldFilled(" + TARGET + ") And " + read, NOT_IN_RULEGROUP));
            }
            actual.put("Summary | " + read, PLAIN.textRule(SUMMARY, read));
            expected.put("Summary | " + read, is("FieldFilled(" + TARGET + ") And " + read, NO_WILDCARD));
        });
        assertEquals(expected, actual, "a bare repeatable group operand from outside the rule group");
    }
}

package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * The two gates every entity-list operator meets BEFORE its own arity or type questions.
 *
 * <p>{@code CheckEntityListenUtils.getRefList} refuses a repeated entity while it BUILDS the list, and most
 * callers then ask {@code checkNotRootGroupReferenced}. Both are shared utilities, so the operator does not
 * decide the law — it decides only whether the law is asked, which makes the CALLER TABLE the thing to
 * measure rather than the method. The grid therefore moves the operator and holds the list fixed.
 *
 * <p>The operator set is the one whose arity gates are already locked ({@code EntityListArityLawsTest}),
 * because those are exactly the families that today yield to these two gates without reporting them.
 *
 * <p>The groups the grid names are deliberately NOT repeatable: an unstarred read of a repeatable group is
 * refused by {@code MVK_NO_WILDCARD} before either pre-gate is reached, so a fixture built on one measures
 * the wildcard gate and nothing else.
 */
class EntityListPreGateLawsTest {

    private static final String GROUP = "/Order";

    private static final String ORDER_JSON = DocumentModelDraft.named("order").locale("en_US")
            .rootGroup("Order", g -> {
                g.stringField("Reference", f -> { });
                g.numberField("Quantity", n -> n.fractionDigits(0));
                g.numberField("Total", n -> n.fractionDigits(0));
                g.group("Shipping", s -> {
                    s.stringField("Carrier", f -> { });
                    s.numberField("Weight", n -> n.fractionDigits(0));
                });
                g.group("Billing", b -> b.stringField("Method", f -> { }));
                g.group("Lines", l -> {
                    l.repeatable(50);
                    l.numberField("Amount", n -> n.fractionDigits(0));
                });
            })
            .rootGroup("Payment", p -> p.stringField("Method", f -> { }))
            .build().toJson();

    /** The operators whose arity gates are locked, each spelled as its EN keyword. */
    private static final List<String> OPERATORS = List.of(
            "FieldFilled", "FieldNotFilled", "GroupFilled", "GroupNotFilled", "SumOfProducts",
            "AllFieldsFilled", "FieldsNotCollectivelyFilled", "NotAllFieldsFilled",
            "NotExactlyOneFieldFilled", "MoreThanOneFieldFilled", "FieldValuesNotUnique", "MaxValue",
            "MinValue", "Sum", "FirstFilledValue", "NumberOfFilledFields", "NumberOfDifferentValues",
            "DateRangesOverlap", "AllGroupsFilled", "GroupsNotCollectivelyFilled", "NotAllGroupsFilled",
            "NumberOfFilledGroups");

    private static String verdict(String condition) {
        return KernelLaws.verdictModel(ORDER_JSON, GROUP, "../Reference",
                "FieldFilled(Reference) And " + condition);
    }

    private static Map<String, String> overOperators(String list) {
        Map<String, String> actual = new LinkedHashMap<>();
        OPERATORS.forEach(op -> actual.put(op, verdict(op + "(" + list + ")")));
        return actual;
    }

    private static Map<String, String> everyOperator(String verdict) {
        Map<String, String> expected = new LinkedHashMap<>();
        OPERATORS.forEach(op -> expected.put(op, verdict));
        return expected;
    }

    /** A field named twice in one list — the DIRECT duplicate, asked by every caller. */
    @Test
    void everyOperatorRefusesARepeatedField() {
        assertEquals(everyOperator("MVK_DUPLICATE_PARAM1"), overOperators("Quantity, Quantity"),
                "a repeated field");
    }

    /** A non-repeatable group named twice — the same gate, on the other entity kind. */
    @Test
    void everyOperatorRefusesARepeatedGroup() {
        assertEquals(everyOperator("MVK_DUPLICATE_PARAM1"), overOperators("Shipping, Shipping"),
                "a repeated group");
    }

    /** A group beside a field it already contains — the INDIRECT duplicate. */
    @Test
    void everyOperatorRefusesANestedDuplicate() {
        assertEquals(everyOperator("MVK_DUPLICATE_PARAM2"), overOperators("Shipping, Shipping/Carrier"),
                "a nested duplicate");
    }

    /** The nested pair written the other way round — the utility scans both directions or only one. */
    @Test
    void whetherTheNestedPairIsRefusedInEitherOrder() {
        assertEquals(everyOperator("MVK_DUPLICATE_PARAM2"), overOperators("Shipping/Carrier, Shipping"),
                "a nested duplicate, the contained entity first");
    }

    /** The same starred read twice — {@code hasWildCards()} exempts it from the DIRECT duplicate test. */
    @Test
    void aStarredReadIsExemptFromTheDuplicateTest() {
        Map<String, String> expected = everyOperator("ADMITTED");
        // What speaks instead is each operator's OWN gate, which is the point: no duplicate class reaches it.
        List.of("FieldFilled", "FieldNotFilled", "GroupFilled", "GroupNotFilled")
                .forEach(op -> expected.put(op, "MVK_PARAMSIZE_INVALID1"));
        List.of("SumOfProducts", "MaxValue", "MinValue", "Sum", "FirstFilledValue", "NumberOfFilledFields",
                "NumberOfDifferentValues").forEach(op -> expected.put(op, "MVK_INCOMPLETE_INPUT"));
        expected.put("DateRangesOverlap", "MVK_NO_DATE_RANGE");
        List.of("AllGroupsFilled", "GroupsNotCollectivelyFilled", "NotAllGroupsFilled")
                .forEach(op -> expected.put(op, "MVK_NO_WILDCARDS_G_ALLOWED"));
        expected.put("NumberOfFilledGroups", "MVK_NO_FIELDS_ALLOWED");
        assertEquals(expected, overOperators("Lines*/Amount, Lines*/Amount"), "the same starred read twice");
    }

    /**
     * The root group BESIDE another entity is an indirect duplicate, never a root-group reference.
     *
     * <p>Every entity of the model is under the root group, so the pair always satisfies
     * {@code isEntityIndirectlyReferenced} — which runs first, inside {@code getRefList}. The root-group gate
     * is therefore unreachable on a list of more than one entity, whatever the second entity is.
     */
    @Test
    void theRootGroupBesideAnythingElseIsANestedDuplicate() {
        assertEquals(everyOperator("MVK_DUPLICATE_PARAM2"), overOperators("/Order, Shipping"),
                "the root group beside a subgroup");
    }

    /**
     * The root group ALONE — the only list on which the gate some callers ask can speak.
     *
     * <p>Three answers, and the operator picks which: {@code GroupFilled}/{@code GroupNotFilled} take the
     * {@code KONTEXT_ANGEGEBEN} arm of {@code CheckKontextListenBedingungImpl}, which asks only
     * {@code checkOnlyGroups}; {@code NumberOfFilledGroups} asks the MORE-THAN-ONE-PARAMETER variant of the
     * gate, so a lone root group reaches its arity class instead; every other caller asks the plain gate.
     */
    @Test
    void whichOperatorsRefuseTheRootGroupAlone() {
        Map<String, String> expected = everyOperator("MVK_ROOT_GROUP_REFERENCED");
        expected.put("GroupFilled", "ADMITTED");
        expected.put("GroupNotFilled", "ADMITTED");
        expected.put("NumberOfFilledGroups", "MVK_PARAMSIZE_INVALIDGN");
        assertEquals(expected, overOperators("/Order"), "the root group alone");
    }

    /** Duplicates are found while the list is BUILT, so they precede the root-group gate. */
    @Test
    void aDuplicateSpeaksBeforeTheRootGroupGate() {
        assertEquals(everyOperator("MVK_DUPLICATE_PARAM1"), overOperators("/Order, /Order"),
                "the root group, repeated");
    }

    /** …and precede the arity gate too: three entities where the operator demands one or two. */
    @Test
    void aDuplicateSpeaksBeforeTheArityGate() {
        assertEquals(everyOperator("MVK_DUPLICATE_PARAM1"), overOperators("Quantity, Quantity, Quantity"),
                "three entities, all the same");
    }

    /**
     * The caller asymmetry: {@code RepetitionNotUnique} builds the same list and asks no root-group gate.
     *
     * <p>{@code CheckWiederholungBedingungImpl} calls {@code getRefList} — so it inherits both duplicate
     * classes — but is the one caller in the table that never calls {@code checkNotRootGroupReferenced}.
     * This row is what makes the gate a property of the CALLER rather than of the list.
     */
    @Test
    void theOneCallerThatAsksNoRootGroupGate() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("the root group alone", verdict("RepetitionNotUnique(/Order)"));
        actual.put("a repeated field", verdict("RepetitionNotUnique(Quantity, Quantity)"));
        actual.put("a nested duplicate", verdict("RepetitionNotUnique(Shipping, Shipping/Carrier)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("the root group alone", "MVK_NO_GROUPS_ALLOWED");
        expected.put("a repeated field", "MVK_DUPLICATE_PARAM1");
        expected.put("a nested duplicate", "MVK_DUPLICATE_PARAM2");
        assertEquals(expected, actual, "what RepetitionNotUnique asks");
    }

    /**
     * A SECOND root group — the only shape on which the more-than-one-parameter gate could speak.
     *
     * <p>{@code NumberOfFilledGroups} asks {@code checkNoRootGroupWhenMoreThanOneParameter}, whose class is
     * {@code MVK_ROOT_GROUP_WITH_OTHER_PARAMETERS}. Every entity of a model is under its own root group, so
     * the indirect-duplicate gate pre-empts that gate on a one-root model; a second root group is the only
     * way a list can hold a root group beside something not under it.
     */
    @Test
    void whetherTheMoreThanOneParameterRootGroupGateIsReachable() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("NumberOfFilledGroups", verdict("NumberOfFilledGroups(/Order, /Payment) > 0"));
        actual.put("AllGroupsFilled", verdict("AllGroupsFilled(/Order, /Payment)"));
        actual.put("AllFieldsFilled", verdict("AllFieldsFilled(/Order, /Payment)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("NumberOfFilledGroups", "MVK_ROOT_GROUP_WITH_OTHER_PARAMETERS");
        expected.put("AllGroupsFilled", "MVK_ROOT_GROUP_REFERENCED");
        expected.put("AllFieldsFilled", "MVK_ROOT_GROUP_REFERENCED");
        assertEquals(expected, actual, "a root group beside a second root group");
    }

    /**
     * {@code NumberOfValueInFields} lists its entities after an infix {@code In}, and asks the same gates.
     *
     * <p>{@code CheckFeldUndWerteListenBedingungImpl} calls {@code checkParamsReferencedOnlyOnce} directly
     * rather than through {@code getRefList}, so the duplicate classes are reached by a second route.
     */
    @Test
    void whatTheInfixListCarrierAsks() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a repeated field", verdict("NumberOfValueInFields(\"x\" In Quantity, Quantity) > 0"));
        actual.put("a nested duplicate",
                verdict("NumberOfValueInFields(\"x\" In Shipping, Shipping/Carrier) > 0"));
        actual.put("the root group alone", verdict("NumberOfValueInFields(\"x\" In /Order) > 0"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a repeated field", "MVK_DUPLICATE_PARAM1");
        expected.put("a nested duplicate", "MVK_DUPLICATE_PARAM2");
        expected.put("the root group alone", "MVK_ROOT_GROUP_REFERENCED");
        assertEquals(expected, actual, "what the infix list carrier asks");
    }

    /**
     * The four operators that call NO arity gate — they still build a list, so they meet the pre-gates.
     *
     * <p>They are absent from {@code EntityListArityLawsTest}'s grid for want of an arity law, which is
     * exactly why their pre-gate row has to be measured here rather than carried over.
     */
    @Test
    void whatTheFourOperatorsWithNoArityLawAsk() {
        Map<String, String> actual = new LinkedHashMap<>();
        List.of("AtLeastOneFieldFilled", "NoFieldFilled", "AtLeastOneGroupFilled", "NoGroupFilled")
                .forEach(op -> {
                    actual.put(op + ": a repeated field", verdict(op + "(Quantity, Quantity)"));
                    actual.put(op + ": a nested duplicate", verdict(op + "(Shipping, Shipping/Carrier)"));
                    actual.put(op + ": the root group alone", verdict(op + "(/Order)"));
                });

        Map<String, String> expected = new LinkedHashMap<>();
        actual.keySet().forEach(key -> expected.put(key,
                key.endsWith("a repeated field") ? "MVK_DUPLICATE_PARAM1"
                        : key.endsWith("a nested duplicate") ? "MVK_DUPLICATE_PARAM2"
                        : key.startsWith("AtLeastOneGroupFilled") || key.startsWith("NoGroupFilled")
                                ? "ADMITTED" : "MVK_ROOT_GROUP_REFERENCED"));
        assertEquals(expected, actual, "what the four arity-free operators ask");
    }
}

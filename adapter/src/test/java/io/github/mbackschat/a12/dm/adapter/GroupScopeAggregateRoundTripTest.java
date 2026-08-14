package io.github.mbackschat.a12.dm.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;

/**
 * {@code CG15} — a GROUP-scope aggregate operand must survive the round trip <b>as a group</b>, executing the
 * ledger's completion gate: <i>"a round-trip row proves each converted group operand renders back to the
 * authored condition"</i>.
 *
 * <p><b>The one root the ledger listed as four symptoms.</b> {@code ConditionConverter} rebuilt a group operand
 * as an explicit list of the group's descendant NUMBER fields instead of keeping the group. That single choice
 * produced every remaining CG15 arm at once: a nested repeatable subgroup lost its star (so the rule silently
 * meant row 1), a legal non-repeatable group operand reached {@code BoundModel#flatten} with no {@code *} and
 * threw, a homogeneous non-number group expanded to zero operands and was reported "unsupported", and
 * {@code NumberOfFilledFields} had no arm at all. The typed surface for the correct shape already existed and is
 * kernel-measured ({@code rulekit.validate.laws.AggregateGroupOperandLawsTest}): {@code GroupListRef} is an
 * {@code AggregateOperand}, so the expansion belongs to the kernel, which is where the table says it lives.
 *
 * <p>The round trip is the oracle — author → serialize → convert back → render, compared against the authored
 * text. A shape lost anywhere in that chain shows up as a mismatch regardless of which layer dropped it.
 */
class GroupScopeAggregateRoundTripTest {

    private static final DslRenderer RENDER = new DslRenderer(ConditionLanguage.EN);

    /**
     * An order model carrying one HOMOGENEOUS group per kind the arms need, in both repetition shapes.
     * {@code Lines} nests a repeatable {@code Fees} because a nested star is the case the field expansion
     * could not represent at all; {@code Billing} is the non-repeatable number group; {@code Contact} is the
     * all-STRING group the number filter used to erase.
     */
    private static String orderJson() {
        return DocumentModelDraft.named("order").locale("en_US")
                .rootGroup("Order", o -> o
                        .stringField("Reference", f -> { })
                        .group("Lines", l -> l.repeatable(20)
                                .numberField("Amount", n -> n.fractionDigits(0, 2))
                                .group("Fees", e -> e.repeatable(20)
                                        .numberField("Fee", n -> n.fractionDigits(0, 2))))
                        .group("Billing", b -> b
                                .numberField("Net", n -> n.fractionDigits(0, 2))
                                .numberField("Vat", n -> n.fractionDigits(0, 2)))
                        .group("Contact", c -> c
                                .stringField("Email", f -> { })
                                .stringField("Phone", f -> { })))
                .build().toJson();
    }

    private static final String ORDER_JSON = orderJson();

    /**
     * <b>The nested-star row.</b> {@code Lines} contains a repeatable {@code Fees}, so the field expansion had
     * to name {@code /Order/Lines*}{@code /Fees/Fee} — a repeatable group addressed without its star, which the
     * kernel refuses {@code MVK_NO_WILDCARD}. Keeping the group operand makes the shape unrepresentable.
     */
    @Test
    void aStarredGroupUnderSumRendersBackAsTheGroup() {
        assertRoundTrips("Sum(/Order/Lines*) > 0");
    }

    /**
     * <b>The non-repeatable row.</b> A group operand with no star is kernel-legal (measured:
     * {@code AggregateGroupOperandLawsTest.aNonRepeatableGroupScopeIsLegalUnstarred}), and the field expansion
     * fed it to {@code flatten}, which rejects a path carrying no {@code *} — surfacing a legal model as a
     * converter error.
     */
    @Test
    void aNonRepeatableGroupUnderAnExtremumRendersBackAsTheGroup() {
        assertRoundTrips("MaxValue(/Order/Billing) > 0");
    }

    /**
     * <b>The kind-filter row.</b> {@code NumberOfDifferentValues} counts distinct values of any comparable kind,
     * so an all-STRING group is a legal operand; the expansion's {@code kind == NUMBER} filter left zero
     * operands and reported "a group with no number fields".
     */
    @Test
    void anAllStringGroupUnderNumberOfDifferentValuesRendersBackAsTheGroup() {
        assertRoundTrips("NumberOfDifferentValues(/Order/Contact) > 0");
    }

    /**
     * <b>The missing-arm row.</b> {@code NumberOfFilledFields} over a single group scope is ACCEPTED by the
     * kernel in both repetition shapes ([KF187]); the group branch's switch simply had no case for it and fell
     * through to "unsupported group-scope aggregate operator".
     */
    @Test
    void aGroupScopeUnderNumberOfFilledFieldsRendersBackAsTheGroup() {
        assertRoundTrips("NumberOfFilledFields(/Order/Contact) > 0");
        assertRoundTrips("NumberOfFilledFields(/Order/Lines*) > 0");
    }

    /**
     * The CONTROL. An explicit flattened FIELD list must keep converting to fields — otherwise "always keep the
     * group" would satisfy every row above by erasing the distinction the operand type exists to carry.
     */
    @Test
    void anExplicitFieldListIsStillConvertedAsFields() {
        assertRoundTrips("Sum(/Order/Lines*/Amount) > 0");
        assertRoundTrips("Sum(/Order/Billing/Net, /Order/Billing/Vat) > 0");
    }

    /**
     * Author → serialize → convert back → render, compared against the authored text.
     *
     * <p>The kernel verdict comes first and is not decoration: a round trip over a condition the kernel would
     * refuse compares two artefacts of a shape no stored model can contain, and would pass just as happily.
     * {@code diagnose} is the kernel's own {@code checkConsistency}, so a green row here means the authored
     * form is legal <i>and</i> survives the read path.
     */
    private static void assertRoundTrips(String aggregate) {
        String authored = "FieldFilled(/Order/Reference) And " + aggregate;
        KernelAdapter adapter = KernelAdapter.ofJson(inject(ORDER_JSON, authored));
        var verdict = adapter.diagnose("/Order/AggregateRule");
        assertTrue(verdict.ok(),
                () -> "the authored condition must be kernel-legal, else the round trip measures nothing: "
                        + authored + " → " + verdict.diagnostics());
        var converted = assertInstanceOf(ReadResult.Converted.class, adapter.read("/Order/AggregateRule"),
                () -> "the group-scope aggregate must convert: " + authored);
        assertEquals(authored, RENDER.render(converted.condition()),
                "the converted operand must render back to the authored condition");
    }

    private static String inject(String modelJson, String rawCondition) {
        String escaped = rawCondition.replace("\\", "\\\\").replace("\"", "\\\"");
        String rule = "{ \"type\": \"Rule\", \"id\": \"/Order/AggregateRule\", \"name\": \"AggregateRule\","
                + " \"Rule\": { \"errorEntityRelPath\": \"../Reference\", \"errorCode\": \"A\","
                + " \"errorCondition\": \"" + escaped + "\", \"severity\": \"ERROR\","
                + " \"errorMessage\": [ { \"locale\": \"en_US\", \"text\": \"x\" } ] } }";
        int br = modelJson.indexOf('[', modelJson.indexOf("\"elements\""));
        return modelJson.substring(0, br + 1) + "\n" + rule + ",\n" + modelJson.substring(br + 1);
    }
}

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
 * {@code CG15} — {@code FieldValuesNotUnique} over a GROUP scope, on the READ path.
 *
 * <p>The kernel admits a group operand here in both repetition shapes and its arity gate is satisfied by the
 * group slot alone, even when the expansion is a single field
 * ({@code rulekit.validate.laws.FieldValuesNotUniqueAdmissionLawsTest}, [KF173]); {@code Dsl} has authored the
 * shape since DG33. The converter nevertheless reported it <i>unsupported</i>, so a rule this estate can WRITE
 * could not be READ back — the asymmetry that makes a read-path hole worth its own row rather than a limit.
 *
 * <p><b>Why this is not in the aggregate round-trip file.</b> {@code FieldValuesNotUnique} is an entity-list
 * <i>condition</i>, not an aggregate: it reaches a different converter branch, and a group-first operand list
 * never enters the field branch at all. That branching is also why the MIXED rows below assert both operand
 * orders — the kernel treats them alike and the converter, keying on whether the FIRST entity is a field, did
 * not.
 */
class FieldValuesNotUniqueGroupRoundTripTest {

    private static final DslRenderer RENDER = new DslRenderer(ConditionLanguage.EN);

    /** {@code Contact} is the non-repeatable string group; {@code Tags} the repeatable one. */
    private static String orderJson() {
        return DocumentModelDraft.named("order").locale("en_US")
                .rootGroup("Order", o -> o
                        .stringField("Reference", f -> { })
                        .group("Contact", c -> c
                                .stringField("Email", f -> { })
                                .stringField("Phone", f -> { }))
                        .group("Tags", t -> t.repeatable(20)
                                .stringField("Key", f -> { })
                                .stringField("Value", f -> { })))
                .build().toJson();
    }

    private static final String ORDER_JSON = orderJson();

    @Test
    void aNonRepeatableGroupOperandRendersBackAsTheGroup() {
        assertRoundTrips("FieldValuesNotUnique(/Order/Contact)");
    }

    @Test
    void aStarredRepeatableGroupOperandRendersBackAsTheGroup() {
        assertRoundTrips("FieldValuesNotUnique(/Order/Tags*)");
    }

    /** Two group operands are still a group list — the plural form must not collapse to the first scope. */
    @Test
    void twoGroupOperandsBothSurvive() {
        assertRoundTrips("FieldValuesNotUnique(/Order/Contact, /Order/Tags*)");
    }

    /**
     * A list mixing a field with a group, in <b>both</b> operand orders — kernel-ACCEPTED either way
     * ({@code GroupOperandCarrierAdmissionLawsTest}), and now representable, since DG36 gave the quantifier a
     * single entity list over {@code AggregateOperand}. This row was authored asserting the {@code Opaque}
     * degrade, deliberately so that it would fail the moment the type landed; it did, which is what made the
     * gap's closure visible rather than something to remember.
     */
    @Test
    void aMixedFieldAndGroupListRoundTripsInBothOrders() {
        assertRoundTrips("FieldValuesNotUnique(/Order/Reference, /Order/Contact)");
        assertRoundTrips("FieldValuesNotUnique(/Order/Contact, /Order/Reference)");
    }

    private static void assertRoundTrips(String predicate) {
        String authored = "FieldFilled(/Order/Reference) And " + predicate;
        KernelAdapter adapter = KernelAdapter.ofJson(inject(ORDER_JSON, authored));
        var verdict = adapter.diagnose("/Order/UniqueRule");
        assertTrue(verdict.ok(),
                () -> "the authored condition must be kernel-legal: " + authored + " → " + verdict.diagnostics());
        var converted = assertInstanceOf(ReadResult.Converted.class, adapter.read("/Order/UniqueRule"),
                () -> "the group operand must convert: " + authored);
        assertEquals(authored, RENDER.render(converted.condition()),
                "the converted operand must render back to the authored condition");
    }

    private static String inject(String modelJson, String rawCondition) {
        String escaped = rawCondition.replace("\\", "\\\\").replace("\"", "\\\"");
        String rule = "{ \"type\": \"Rule\", \"id\": \"/Order/UniqueRule\", \"name\": \"UniqueRule\","
                + " \"Rule\": { \"errorEntityRelPath\": \"../Reference\", \"errorCode\": \"U\","
                + " \"errorCondition\": \"" + escaped + "\", \"severity\": \"ERROR\","
                + " \"errorMessage\": [ { \"locale\": \"en_US\", \"text\": \"x\" } ] } }";
        int br = modelJson.indexOf('[', modelJson.indexOf("\"elements\""));
        return modelJson.substring(0, br + 1) + "\n" + rule + ",\n" + modelJson.substring(br + 1);
    }
}

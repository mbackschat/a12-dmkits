package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupListRef;

/**
 * KERNEL-FINDINGS §9 — {@code NumberOfValueInFields} over a <b>GROUP-scope operand</b>, closing the type-level
 * erasure recorded as {@code DG34}.
 *
 * <p><b>Why this file exists at all, and why it is a law test rather than only a factory.</b> The factory took
 * {@link io.github.mbackschat.a12.dm.rulekit.dsl.FieldOperand}s and had no group overload, so the shape was not
 * "refused" — it was <em>unrepresentable</em>, which reads as "not a thing" rather than "measured and rejected".
 * CLAUDE.md's rule is that a client-side refusal needs a measured kernel basis; a type-level erasure is the same
 * defect one layer earlier and harder to see, because there is no runtime error to find. The kernel's actual
 * verdict was measured first ({@code adapter.laws.GroupStarValueInFieldsDiffTest} asserts admission as its own
 * test, and {@code KF187} sweeps the carrier): it <b>ACCEPTS</b> the starred group operand and counts matches
 * across every row.
 *
 * <p><b>The operand type is the fix, not an overload.</b> {@code NumberOfValueInFields} is an entity-list
 * operator — the kernel routes it through the same {@code getRefList} as the aggregates — so its operand list is
 * an {@link io.github.mbackschat.a12.dm.rulekit.dsl.AggregateOperand} list, exactly as {@code ListAggregate}'s
 * already is. Modelling it as a {@code FieldOperand} list was the erasure itself, and adding a parallel record
 * would have preserved it under a second name.
 *
 * <p><b>Scope: the STARRED form only.</b> The unstarred group operand is deliberately not authored here. It has
 * no measurement on this carrier, and widening authoring past a measured basis is the same defect in the
 * opposite direction — the rule is "never broader than its measured basis", and "the sibling carrier admits it"
 * is an analogy, not a measurement.
 */
class ValueInFieldsGroupOperandLawsTest {

    /**
     * An all-STRING repeatable group. String rather than number because the constant compared against is a
     * string here, and a kind mismatch inside the expansion would fire before any operand-shape gate could be
     * observed — the same masking that forced {@code AggregateGroupOperandLawsTest} onto its own fixture.
     */
    private static final String ORDER_JSON = DocumentModelDraft.named("order").locale("en_US")
            .rootGroup("Order", g -> {
                g.stringField("Reference", f -> { });
                g.group("Lines", l -> l.repeatable(10).stringField("Code", f -> { }).stringField("Note", f -> { }));
            }).build().toJson();

    private static final BoundModel ORDER = BoundModel.ofJson(ORDER_JSON);

    private static GroupListRef lines() {
        return ORDER.flattenGroup("/Order/Lines*");
    }

    /** Guard the count so the probe rule references its own error field ({@code MVK_ERROR_FIELD_NOT_REFERENCED}). */
    private static String guarded(Condition c) {
        return new DslRenderer().render(Dsl.fieldFilled(ORDER.field("/Order/Reference")).and(c));
    }

    /** The kernel admits the starred group operand — the measurement the erasure had no basis to contradict. */
    @Test
    void aStarredGroupScopeIsAdmittedAsTheWholeOperandList() {
        KernelLaws.acceptsModel(ORDER_JSON, "/Order", "../Reference",
                guarded(Dsl.numberOfValueInFields("X", lines()).gt(0)));
    }

    /**
     * The group slot satisfies the arity gate <b>alone</b>, exactly as a {@code FieldListRef} does — the same
     * split {@code KF187} measures for the fill quantifiers, where arity reads the authored slots while the kind
     * gate reads the expansion. The explicit two-field list is the control: it is what the caller had to write
     * before this overload existed, and it must stay admitted.
     */
    @Test
    void theExplicitTwoFieldListStaysAdmittedBesideIt() {
        KernelLaws.acceptsModel(ORDER_JSON, "/Order", "../Reference",
                guarded(Dsl.numberOfValueInFields("X",
                        ORDER.flatten("/Order/Lines*/Code"), ORDER.flatten("/Order/Lines*/Note")).gt(0)));
    }

    /**
     * The typed factory renders the condition the kernel accepted, character for character. Without this the
     * two rows above could both pass while the builder emitted some <em>other</em> admitted condition — the
     * check that a hand-authored probe and the typed surface are measuring one shape.
     */
    @Test
    void theTypedFactoryRendersTheAcceptedCondition() {
        assertEquals("FieldFilled(/Order/Reference) And NumberOfValueInFields(\"X\" In /Order/Lines*) > 0",
                guarded(Dsl.numberOfValueInFields("X", lines()).gt(0)));
    }

    /**
     * The boolean and integer constants take the same operand, so the widening is not string-only. Rendered
     * rather than kernel-probed: the constant's own kind against the expansion's is a separate gate this file
     * does not measure, and asserting acceptance here would silently claim a row it never ran.
     */
    @Test
    void theBooleanAndNumericConstantsTakeAGroupScopeToo() {
        assertEquals("FieldFilled(/Order/Reference) And NumberOfValueInFields(3 In /Order/Lines*) > 0",
                guarded(Dsl.numberOfValueInFields(3L, lines()).gt(0)));
        assertEquals("FieldFilled(/Order/Reference) And NumberOfValueInFields(True In /Order/Lines*) > 0",
                guarded(Dsl.numberOfValueInFields(true, lines()).gt(0)));
    }
}

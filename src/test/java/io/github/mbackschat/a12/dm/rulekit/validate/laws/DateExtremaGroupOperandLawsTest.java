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
 * KERNEL-FINDINGS {@code KF187} — the DATE extrema over a <b>GROUP-scope operand</b>, closing the first half of
 * {@code DG35}.
 *
 * <p><b>The erasure and why it took a record change.</b> {@code Dsl.latestOf}/{@code earliestOf} and their
 * TIME/DATETIME siblings accepted only a {@link io.github.mbackschat.a12.dm.rulekit.dsl.FieldListRef}, so the
 * kernel-admitted group shape was unrepresentable. Unlike {@code DG34}, adding an overload was not enough:
 * {@code DateListAggregate.kind()} <em>derived</em> the aggregate's kind from {@code scope.kind()}, and a
 * {@link GroupListRef} carries no kind at all — a group's kind lives in its expansion, which
 * {@code AggregateOperand} deliberately delegates to the kernel rather than re-implementing the extent.
 *
 * <p><b>So the kind became explicit, supplied by the factory.</b> That is not a workaround but the accurate
 * model: each factory already names the kind it means — {@code latestOf} is the DATE one, {@code latestTimeOf}
 * the TIME one — so the information was always present at the call site and merely re-derived from the operand
 * afterwards. The field arm keeps its equality check, so a DATE list handed to {@code latestTimeOf} still fails
 * fast rather than mis-typing the aggregate.
 *
 * <p><b>Scope: the STARRED form only.</b> `KF187` measures both repetition shapes as ACCEPT for this carrier, but
 * the unstarred group is left out until a runtime row exists for it — widening authoring past what the evaluator
 * is measured to answer is the mistake {@code DG33} recorded, and the rule is "never broader than its measured
 * basis" in both directions.
 */
class DateExtremaGroupOperandLawsTest {

    /** An all-DATE repeatable group: the kind gate reads the EXPANSION, so a mixed group would mask the row. */
    private static final String CLAIM_JSON = DocumentModelDraft.named("claim").locale("en_US")
            .rootGroup("Claim", g -> {
                g.stringField("Reference", f -> { });
                g.dateField("ClaimedOn", f -> { });
                g.group("Inspections", i -> i.repeatable(10)
                        .dateField("VisitedOn", f -> { })
                        .dateField("ClearedOn", f -> { }));
            }).build().toJson();

    private static final BoundModel CLAIM = BoundModel.ofJson(CLAIM_JSON);

    private static GroupListRef inspections() {
        return CLAIM.flattenGroup("/Claim/Inspections*");
    }

    private static String render(Condition c) {
        return new DslRenderer().render(c);
    }

    /** The kernel admits the starred DATE group under both extrema — the measurement the erasure contradicted. */
    @Test
    void bothDateExtremaTakeAStarredGroupScope() {
        KernelLaws.acceptsModel(CLAIM_JSON, "/Claim", "../Reference",
                render(Dsl.fieldFilled(CLAIM.field("/Claim/Reference"))
                        .and(Dsl.latestOf(inspections()).after(CLAIM.date("/Claim/ClaimedOn")))));
        KernelLaws.acceptsModel(CLAIM_JSON, "/Claim", "../Reference",
                render(Dsl.fieldFilled(CLAIM.field("/Claim/Reference"))
                        .and(Dsl.earliestOf(inspections()).after(CLAIM.date("/Claim/ClaimedOn")))));
    }

    /**
     * The typed factory renders the condition the kernel accepted, character for character. Without it the row
     * above could pass while the builder emitted some other admitted condition.
     */
    @Test
    void theTypedFactoryRendersTheAcceptedCondition() {
        assertEquals("FieldFilled(/Claim/Reference) And MaxValue(/Claim/Inspections*) > [/Claim/ClaimedOn]",
                render(Dsl.fieldFilled(CLAIM.field("/Claim/Reference"))
                        .and(Dsl.latestOf(inspections()).after(CLAIM.date("/Claim/ClaimedOn")))));
    }

    /**
     * The FIELD arm keeps its kind check — the control that making the kind explicit did not disable the
     * fast failure a wrong-kind field list used to get.
     */
    @Test
    void aFieldListOfTheWrongKindStillFailsFast() {
        var thrown = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> Dsl.latestTimeOf(CLAIM.flatten("/Claim/Inspections*/VisitedOn")));
        org.junit.jupiter.api.Assertions.assertTrue(thrown.getMessage().contains("TIME")
                        || thrown.getMessage().contains("DATE"),
                () -> "a DATE list handed to the TIME factory must say so: " + thrown.getMessage());
    }
}

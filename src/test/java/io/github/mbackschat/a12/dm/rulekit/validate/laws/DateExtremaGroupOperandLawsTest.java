package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
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
 * afterwards. The field arm keeps ONE kind check, and it has narrowed twice: from kind EQUALITY to the temporal
 * CLASS (2026-08-24, because the kernel admits a DATE {@code yyyy} beside a DateFragment {@code yyyy}), and
 * then to the bare ROUTING question — is this operand date-like at all — because the class the kernel's gate
 * reads is the DECLARATION's and a {@code ValueKind} cannot see one, so a date-declared TIME field is a legal
 * member of a DATE list ([KF271], 2026-09-07). What remains is the only temporal question a kind answers; the
 * class and the component set are the kernel's ([KF193],
 * {@code TemporalAggregateComponentSetLawsTest}).
 *
 * <p><b>Scope: the STARRED form only.</b> `KF187` measures both repetition shapes as ACCEPT for this carrier, but
 * the unstarred group is left out until a runtime row exists for it — widening authoring past what the evaluator
 * is measured to answer is the mistake {@code DG33} recorded, and the rule is "never broader than its measured
 * basis" in both directions.
 */
class DateExtremaGroupOperandLawsTest {

    /** {@code Inspections} is all-DATE: the group arm reads the EXPANSION, so a mixed group would mask the row. */
    private static final String CLAIM_JSON = DocumentModelDraft.named("claim").locale("en_US")
            .rootGroup("Claim", g -> {
                g.stringField("Reference", f -> { });
                g.dateField("ClaimedOn", f -> { });
                g.timeField("InspectedAt", f -> { });
                g.group("Inspections", i -> i.repeatable(10)
                        .dateField("VisitedOn", f -> { })
                        .dateField("ClearedOn", f -> { }));
                // Its own group, so `Inspections` stays all-DATE and the group-scope rows keep measuring
                // an expansion the kernel admits.
                g.group("Inspectors", i -> i.repeatable(10)
                        .stringField("InspectorName", f -> { }));
            }).build().toJson();

    private static final BoundModel CLAIM = BoundModel.ofJson(CLAIM_JSON);

    private static GroupListRef inspections() {
        return CLAIM.flattenGroup("/Claim/Inspections*");
    }

    private static String render(Condition c) {
        return KernelLaws.render(c);
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
     * The control that making the kind explicit did not disable the fast failure a wrong-kind field list used to
     * get — <b>narrowed to what a kind can actually decide.</b> A STRING list under a date-like factory is not
     * date-like at all, which is the routing question, and it still fails fast.
     */
    @Test
    void aFieldListOfANonDateLikeKindStillFailsFast() {
        var thrown = Assertions.assertThrows(IllegalArgumentException.class,
                () -> Dsl.latestTimeOf(CLAIM.flatten("/Claim/Inspectors*/InspectorName")));
        Assertions.assertTrue(thrown.getMessage().contains("STRING"),
                () -> "a STRING list handed to a date-like factory must say so: " + thrown.getMessage());
    }

    /**
     * …and the class boundary is <b>delegated</b>, with the kernel row that grounds the delegation beside it. A
     * DATE list under the TIME factory used to throw here; it cannot, because the same check would have to
     * refuse the date-declared TIME field the kernel admits in a DATE list ([KF271]). So the factory builds it
     * and the kernel reports it — which is also the only way the author sees the kernel's own code, and the
     * code it reports is the COMPARISON's ({@code MVK_INVALID_COMPARE_TO_DATE}, the coarse direct-comparison
     * gate) rather than the operand gate's: the one-element list is internally consistent, and what disagrees
     * is the date it computes against the clock declaration it is compared to.
     */
    @Test
    void aDateListUnderTheTimeFactoryIsBuiltAndRefusedByTheKernel() {
        Condition crossing = Dsl.fieldFilled(CLAIM.field("/Claim/Reference"))
                .and(Dsl.latestTimeOf(CLAIM.flatten("/Claim/Inspections*/VisitedOn"))
                        .eq(CLAIM.time("/Claim/InspectedAt")));
        assertEquals("FieldFilled(/Claim/Reference) And MaxValue(/Claim/Inspections*/VisitedOn)"
                + " == [/Claim/InspectedAt]", render(crossing));
        KernelLaws.rejectsModel(CLAIM_JSON, "/Claim", "../Reference", render(crossing),
                "MVK_INVALID_COMPARE_TO_DATE");
    }
}

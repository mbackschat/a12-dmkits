package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupListRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr;

/**
 * KERNEL-FINDINGS §9 — the <b>GROUP-scope operand</b> of the entity-list aggregates, measured 2026-08-12 on
 * kernel 30.8.1. The kernel expands a group operand to its contained fields ({@code ExpandService},
 * KERNEL-FINDINGS {@code kf12}) and applies the ordinary entity-list gates to the expansion.
 *
 * <p><b>What is carrier-independent and what is not</b>, because an earlier draft of this javadoc said
 * "identical for" the whole family and that is wrong for one row. The <i>structural</i> gates — the param law,
 * the wildcard requirement, the two duplicate arms — are measured across carriers and agree. The <b>kind</b>
 * gate does not: it reports the carrier's own code for the same operand ({@code MVK_NO_NUMBER} under
 * {@code Sum}, {@code MVK_NOT_SORTABLE} under {@code MaxValue},
 * {@code MVK_STRING_ENUM_AND_NON_STRING_ENUM} under {@code NumberOfDifferentValues}), so it is the expansion's
 * kinds judged against <i>this</i> aggregate's admitted set, not one shared verdict. Read each row below for
 * the carriers it actually names.
 *
 * <p><b>The measured set is the design</b> — it is what decided which gates {@code AggregateOperand}
 * pre-checks and which it delegates:
 *
 * <table>
 *   <tr><th>shape</th><th>verdict</th></tr>
 *   <tr><td>one starred group, alone</td><td>ACCEPT — satisfies the param law like a repetition list</td></tr>
 *   <tr><td>the same starred group twice</td><td>ACCEPT — the wildcard skip in checkDuplicatesInDirectRefs</td></tr>
 *   <tr><td>a starred group + an unrelated field</td><td>ACCEPT</td></tr>
 *   <tr><td>nested stars {@code Lines*}{@code /Fees*}</td><td>ACCEPT — both stars survive</td></tr>
 *   <tr><td>a group + a descendant of itself</td><td>{@code MVK_DUPLICATE_PARAM2}</td></tr>
 *   <tr><td>a REPEATABLE group, unstarred</td><td>{@code MVK_NO_WILDCARD}</td></tr>
 *   <tr><td>a NON-repeatable group, unstarred</td><td>ACCEPT</td></tr>
 *   <tr><td>a NON-repeatable group, STARRED</td><td>{@code MVK_INVALID_WILDCARD} — measured 2026-08-12, which
 *       is also what grounds {@code BoundModel.flattenGroup}'s own pre-check</td></tr>
 *   <tr><td>an expansion of the wrong kind</td><td>{@code MVK_NO_NUMBER} / {@code MVK_NOT_SORTABLE} / …</td></tr>
 * </table>
 *
 * <p>The two rejections that are <b>not</b> pre-checked are the interesting ones, and each has its reason on
 * {@code AggregateOperand}: the kind gate applies to the EXPANSION, whose extent recurses into nested
 * subgroups, and the indirect overlap is a relation between two operands the direct duplicate check never
 * sees. Both stay kernel-arbitrated (F4) rather than re-implemented, which is also what
 * {@code GroupListOperand} already does for the same family.
 *
 * <p>Runtime behaviour over the same operand — what the expansion actually feeds the combiner, the poison
 * arm, and the {@code Having} refusal — is {@code adapter.laws.GroupStarValueAggregateDiffTest}.
 */
class AggregateGroupOperandLawsTest {

    private static final BoundModel ORDER = Fixtures.order();

    /**
     * An all-NUMBER repeatable group with a nested repeatable subgroup. Needed because the shared order
     * fixture's {@code Items} contains a string, so its kind gate fires first and masks every other answer.
     */
    private static final String INVOICE_JSON = DocumentModelDraft.named("invoice").locale("en_US")
            .rootGroup("Invoice", g -> {
                g.stringField("Reference", f -> { });
                g.numberField("Total", f -> f.fractionDigits(0, 2));
                g.group("Lines", l -> {
                    l.repeatable(10).numberField("Amount", f -> f.fractionDigits(0, 2));
                    l.group("Fees", e -> e.repeatable(10).numberField("Fee", f -> f.fractionDigits(0, 2)));
                });
            }).build().toJson();

    private static final BoundModel INVOICE = BoundModel.ofJson(INVOICE_JSON);

    private static String cond(Condition c) {
        return new DslRenderer().render(c);
    }

    /** Guard the aggregate so the probe rule references its own error field (MVK_ERROR_FIELD_NOT_REFERENCED). */
    private static String guardedInvoice(NumberExpr aggregate) {
        return cond(Dsl.fieldFilled(INVOICE.field("/Invoice/Reference")).and(aggregate.gt(0)));
    }

    private static String guardedOrder(NumberExpr aggregate) {
        return cond(Dsl.fieldFilled(ORDER.field("/Order/Quantity")).and(aggregate.gt(0)));
    }

    private static GroupListRef lines() {
        return INVOICE.flattenGroup("/Invoice/Lines*");
    }

    /**
     * The unstarred non-repeatable group scope as a literal, and the same condition with the star this file
     * could not previously author. Kept as one pair of constants so the two probes differ in exactly the star.
     */
    private static final String NON_REPEATABLE_GROUP_SCOPE =
            "FieldFilled(/Order/Quantity) And NumberOfDifferentValues(/Order/ShippingAddress) > 0";

    private static final String STARRED_NON_REPEATABLE_GROUP_SCOPE =
            NON_REPEATABLE_GROUP_SCOPE.replace("ShippingAddress", "ShippingAddress*");

    /** Every aggregate over the same group scope, so the shared-gate claim is measured and not assumed. */
    private static NumberExpr[] overLines() {
        return new NumberExpr[] {
            Dsl.sum(lines()), Dsl.maxValue(lines()), Dsl.minValue(lines()),
            Dsl.numberOfDifferentValues(lines()),
        };
    }

    // ── the accepted shapes ───────────────────────────────────────────────────────────────────────

    /** A single starred group is a complete operand list on its own, for every aggregate in the family. */
    @Test
    void aStarredGroupScopeSatisfiesTheParamLawAlone() {
        for (NumberExpr aggregate : overLines()) {
            KernelLaws.acceptsModel(INVOICE_JSON, "/Invoice", "../Reference", guardedInvoice(aggregate));
        }
    }

    /**
     * The same starred group may occupy several slots — the kernel's {@code checkDuplicatesInDirectRefs}
     * continues past a wildcarded ref, exactly as it does for a flattened repetition list. This is why
     * {@code GroupListRef} contributes no dedup identity in {@code ListAggregate.checkNoDuplicates}.
     */
    @Test
    void aStarredGroupScopeMayBeListedMoreThanOnce() {
        KernelLaws.acceptsModel(INVOICE_JSON, "/Invoice", "../Reference",
                guardedInvoice(Dsl.sum(lines(), lines())));
        KernelLaws.acceptsModel(INVOICE_JSON, "/Invoice", "../Reference",
                guardedInvoice(Dsl.numberOfDifferentValues(lines(), lines())));
    }

    /** A group scope mixes with ordinary field operands, and nested stars both survive the round trip. */
    @Test
    void aGroupScopeMixesWithFieldsAndCarriesNestedStars() {
        KernelLaws.acceptsModel(INVOICE_JSON, "/Invoice", "../Reference",
                guardedInvoice(Dsl.sum(lines(), INVOICE.field("/Invoice/Total"))));
        KernelLaws.acceptsModel(INVOICE_JSON, "/Invoice", "../Reference",
                guardedInvoice(Dsl.sum(INVOICE.flattenGroup("/Invoice/Lines*/Fees*"))));
    }

    /** A NON-repeatable group needs no star, and the typed surface renders it without one. */
    @Test
    void aNonRepeatableGroupScopeIsLegalUnstarred() {
        KernelLaws.acceptsOrder("/Order", "../Quantity", guardedOrder(
                Dsl.numberOfDifferentValues(ORDER.flattenGroup("/Order/ShippingAddress"))));
    }

    // ── the builder-refused shape, measured on the kernel anyway ───────────────────────────────────

    /**
     * The hand-authored spelling of the row above, asserted accepted <b>before</b> the starred probe below uses
     * the same spelling. Without this control a rejection there could equally be a typo in a string the typed
     * surface never produced, which is the standing hazard of authoring a condition by hand.
     */
    @Test
    void theHandAuthoredSpellingOfTheUnstarredFormIsTheSameConditionTheBuilderRenders() {
        KernelLaws.acceptsOrder("/Order", "../Quantity", NON_REPEATABLE_GROUP_SCOPE);
        assertEquals(NON_REPEATABLE_GROUP_SCOPE, guardedOrder(
                Dsl.numberOfDifferentValues(ORDER.flattenGroup("/Order/ShippingAddress"))),
                "the literal must be character-identical to the rendered form, else the starred probe below "
                        + "measures a different condition than the accepted control");
    }

    /**
     * <b>Starring a NON-repeatable group</b> — the row this file previously carried as "not measured", closed
     * 2026-08-12. {@code BoundModel.flattenGroup} refuses the shape itself, citing {@code MVK_INVALID_WILDCARD}
     * in its message, so the kernel never sees it through the typed surface: it must therefore be authored as a
     * literal, which is the only legitimate reason to hand-write a condition in this estate.
     *
     * <p><b>Why the builder's basis was not already sufficient.</b> {@code MVK_INVALID_WILDCARD} was measured on
     * {@code NumberOfFilledGroups(/Order*)} — a different carrier, and a root group (KERNEL-FINDINGS §9). This
     * file has just established that the <em>kind</em> gate reports the carrier's own code, so transferring a
     * code across carriers by analogy is exactly the reasoning the "a client-side refusal needs a measured
     * kernel basis" rule forbids. The star gate turns out to be carrier-independent after all — but that is now
     * an observation rather than an inference.
     */
    @Test
    void aStarredNonRepeatableGroupScopeIsRejected() {
        KernelLaws.rejectsOrder("/Order", "../Quantity", STARRED_NON_REPEATABLE_GROUP_SCOPE,
                "MVK_INVALID_WILDCARD");
    }

    /** …and the builder refuses the same shape locally, so the pre-check and the kernel agree. */
    @Test
    void theBuilderRefusesTheSameShapeItsMessageNames() {
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> ORDER.flattenGroup("/Order/ShippingAddress*"));
        assertTrue(thrown.getMessage().contains("MVK_INVALID_WILDCARD"),
                "the pre-check must cite the code the kernel actually reports: " + thrown.getMessage());
    }

    // ── the delegated rejections ──────────────────────────────────────────────────────────────────

    /**
     * A REPEATABLE group must carry its star. The typed surface can still author the unstarred form —
     * {@code flattenGroup} accepts a bare path because the group-list quantifiers legitimately take one — so
     * this is a kernel refusal rather than a builder refusal, and it is the defect that
     * {@code Dsl.numberOfFilledFields(GroupRef…)} used to ship: its renderer starred a repeatable group for
     * {@code NumberOfFilledGroups} only.
     */
    @Test
    void anUnstarredRepeatableGroupScopeIsRejected() {
        KernelLaws.rejectsModel(INVOICE_JSON, "/Invoice", "../Reference",
                guardedInvoice(Dsl.sum(INVOICE.flattenGroup("/Invoice/Lines"))), "MVK_NO_WILDCARD");
        KernelLaws.rejectsOrder("/Order", "../Quantity",
                guardedOrder(Dsl.numberOfFilledFields(ORDER.flattenGroup("/Order/Items"))), "MVK_NO_WILDCARD");
    }

    /** …and the starred form of that same operand is accepted, so the law is not vacuous. */
    @Test
    void theStarredFormOfTheSameOperandIsAccepted() {
        KernelLaws.acceptsOrder("/Order", "../Quantity",
                guardedOrder(Dsl.numberOfFilledFields(ORDER.flattenGroup("/Order/Items*"))));
    }

    /**
     * The kind gate applies to the group's EXPANSION, not to the group: {@code Items} contains a string, so
     * {@code Sum} refuses it while {@code NumberOfDifferentValues} refuses an all-string group only for
     * mixing. Delegated rather than pre-checked — owning this would mean owning the expansion's extent.
     */
    @Test
    void theKindGateAppliesToTheGroupsExpansion() {
        KernelLaws.rejectsOrder("/Order", "../Quantity",
                guardedOrder(Dsl.sum(ORDER.flattenGroup("/Order/Items*"))), "MVK_NO_NUMBER");
        KernelLaws.rejectsOrder("/Order", "../Quantity",
                guardedOrder(Dsl.maxValue(ORDER.flattenGroup("/Order/Items*"))), "MVK_NOT_SORTABLE");
        KernelLaws.rejectsOrder("/Order", "../Quantity",
                guardedOrder(Dsl.numberOfDifferentValues(ORDER.flattenGroup("/Order/Items*"))),
                "MVK_STRING_ENUM_AND_NON_STRING_ENUM");
    }

    /**
     * A group beside a DESCENDANT of itself is the indirect-overlap arm — the one duplicate gate a wildcard
     * does not escape, and the reason the typed dedup check cannot simply treat a group as wildcarded and
     * stop thinking about it.
     */
    @Test
    void aGroupScopeBesideItsOwnDescendantIsRejected() {
        KernelLaws.rejectsModel(INVOICE_JSON, "/Invoice", "../Reference",
                guardedInvoice(Dsl.sum(lines(), INVOICE.flattenGroup("/Invoice/Lines*/Fees*"))),
                "MVK_DUPLICATE_PARAM2");
    }

    /**
     * The same pair with the operands REVERSED — descendant first. `ListAggregate`'s public javadoc says the
     * containment arm refuses the pair "whichever side carries it", and until this row that symmetry was
     * asserted on parent-first measurements only (a cold review, 2026-09-04). A relation-based gate is the
     * likelier implementation, but likelier is not measured, and the sentence is a public contract.
     */
    @Test
    void theContainmentRefusalDoesNotDependOnWhichOperandComesFirst() {
        KernelLaws.rejectsModel(INVOICE_JSON, "/Invoice", "../Reference",
                guardedInvoice(Dsl.sum(INVOICE.flattenGroup("/Invoice/Lines*/Fees*"), lines())),
                "MVK_DUPLICATE_PARAM2");
    }
}

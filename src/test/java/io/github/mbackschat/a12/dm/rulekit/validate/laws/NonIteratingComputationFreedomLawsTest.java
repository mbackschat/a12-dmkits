package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Calculation;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberLiteral;
import io.github.mbackschat.a12.dm.rulekit.validate.PrecheckException;

/**
 * KERNEL-FINDINGS — <b>a computation that cannot ITERATE is constrained on neither side.</b> Its declaring
 * group is free of the computed field, and its operands are free of the declaring group and of each other.
 * The companion of {@link ComputationDeclaringGroupLawsTest}, which measures the gate when iteration IS
 * derived; this one measures what is left when nothing derives it.
 *
 * <p><b>The load-bearing negative: a STAR AGGREGATE is not a per-row operand.</b> That is the discriminator
 * between the two accounts of {@code MVK_ERROR_FIELD_NOT_IN_RULEGROUP} — "a repeatable group appears in the
 * operand path" versus "the operand reads one row at a time". Only the second predicts that
 * {@code Sum(Lots*&#47;Quantity)} into a fixed target is admitted from the starred group itself, which is the
 * placement the first account most clearly forbids. Reported as {@code SPEC-2026-08-29-03} by a12-kernel-lean
 * and measured here rather than adopted.
 *
 * <p><b>Every table pairs its subject with a bare-constant control at the same placements.</b> A constant is
 * the shape that provably derives no iteration, so an operand class matching it row for row has been shown to
 * derive none either — which a table of admissions alone cannot show, since it is also what a degraded
 * validator would print. The refusal rows in {@link #containmentIsPathContainmentAndNotRootRelative} and
 * {@link #theWildcardGatePreEmptsPlacementOnlyFromANonRepeatableDeclaringGroup} are this class's positive
 * control that {@code checkConsistency} actually arbitrated.
 *
 * <p>Own domain (a parts catalogue with a separate audit root).
 */
class NonIteratingComputationFreedomLawsTest {

    private static final String NOT_IN_RULEGROUP = "MVK_ERROR_FIELD_NOT_IN_RULEGROUP";

    /**
     * Two ROOT groups, because the widest available separation of "containment" from "same root" needs a
     * second root to cross. Under the catalogue root: a fixed pricing group holding the fixed target, a fixed
     * unrelated group, two repeatable extents that differ only in whether they DECLARE an index field, and a
     * repeatable group holding the repeatable target.
     */
    private static final String CATALOGUE = DocumentModelDraft.named("catalogue").locale("en_US")
            .rootGroup("Catalogue", c -> c
                    .group("Pricing", p -> p
                            .numberField("ListPrice", n -> n.fractionDigits(0))
                            .numberField("BasePrice", n -> n.fractionDigits(0)))
                    .group("Terms", t -> t.numberField("TermMarker", n -> n.fractionDigits(0)))
                    .group("Batches", b -> b.repeatable(20).indexField("BatchKey")
                            .field("BatchKey", FieldKind.STRING)
                            .numberField("Quantity", n -> n.fractionDigits(0)))
                    .group("Lots", l -> l.repeatable(20)
                            .field("LotKey", FieldKind.STRING)
                            .numberField("Quantity", n -> n.fractionDigits(0)))
                    .group("Slots", s -> s.repeatable(20)
                            .numberField("SlotTotal", n -> n.fractionDigits(0))))
            .rootGroup("Audit", a -> a
                    .group("Ledger", l -> l.numberField("LedgerMarker", n -> n.fractionDigits(0)))
                    .group("Trail", t -> t.repeatable(20).numberField("TrailUnits", n -> n.fractionDigits(0))))
            .build().toJson();

    private static final BoundModel M = BoundModel.ofJson(CATALOGUE);

    private static final String FIXED_TARGET = "/Catalogue/Pricing/ListPrice";
    private static final String REPEATABLE_TARGET = "/Catalogue/Slots/SlotTotal";

    /** Every declaring-group relation a fixed target can stand in, inside its own root. */
    private static final String[] PLACEMENTS = {
        "/Catalogue/Pricing", "/Catalogue", "/Catalogue/Terms", "/Catalogue/Lots", "/Catalogue/Batches",
    };

    private static final String[] PLACEMENT_LABELS = {
        "the target's own fixed group", "the root ancestor", "an unrelated fixed sibling",
        "the starred source's own repeatable group", "an unrelated repeatable group",
    };

    private static String verdict(String computedField, String group, Calculation operation) {
        return KernelLaws.computationVerdict(CATALOGUE, M.computation(M.num(computedField))
                .name("CatalogueProbe")
                .group(M.group(group))
                .calc(operation)
                .message(Dsl.en("Catalogue placement probe."))
                .build());
    }

    /** The five placements of one operation, labelled — the shape every table here compares. */
    private static Map<String, String> across(String computedField, Calculation operation) {
        Map<String, String> row = new LinkedHashMap<>();
        for (int i = 0; i < PLACEMENTS.length; i++) {
            row.put(PLACEMENT_LABELS[i], verdict(computedField, PLACEMENTS[i], operation));
        }
        return row;
    }

    private static Map<String, String> allAdmitted() {
        Map<String, String> expected = new LinkedHashMap<>();
        for (String label : PLACEMENT_LABELS) {
            expected.put(label, KernelLaws.ADMITTED);
        }
        return expected;
    }

    /**
     * A star aggregate into a FIXED target carries no placement restriction at all — including from the very
     * group it stars, which is the placement a "repeatable group in the operand path" account forbids.
     */
    @Test
    void aStarAggregateIntoAFixedTargetIsAdmittedFromEveryPlacement() {
        Calculation starAggregate = Dsl.sum(M.flatten("/Catalogue/Lots*/Quantity")).expression();
        assertEquals(allAdmitted(), across(FIXED_TARGET, starAggregate),
                "an aggregate reads no row, so nothing derives iteration and nothing gates placement");
    }

    /** The control the table above is only meaningful against: the shape that provably cannot iterate. */
    @Test
    void aBareConstantIntoAFixedTargetMatchesTheStarAggregateRowForRow() {
        assertEquals(allAdmitted(), across(FIXED_TARGET, NumberLiteral.of(-1)),
                "the constant's five verdicts are what the star aggregate is claimed to reproduce");
    }

    /**
     * The OPERAND side is unconstrained too, which the placement tables cannot show: the two sources sit in
     * different groups from each other and from the declaring group, at every placement.
     */
    @Test
    void twoOperandsNeedShareNoGroupWithTheDeclaringGroupOrWithEachOther() {
        Calculation twoGroups = Dsl.firstFilledNumber(
                M.num("/Catalogue/Pricing/BasePrice").field(), M.num("/Catalogue/Terms/TermMarker").field())
                .expression();
        assertEquals(allAdmitted(), across(FIXED_TARGET, twoGroups),
                "containment constrains neither side of a shape that cannot iterate");
    }

    /**
     * The operand side is not gate-free — it just has no CONTAINMENT gate. Reading the target itself is
     * refused whatever the placement.
     *
     * <p><b>This row does not reach the kernel, and saying so is the point.</b> The refusal is
     * {@code Computation.Builder.build()}'s own pre-check, which throws before a draft exists; its kernel
     * basis is the measured set recorded on the {@code MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD} row of the
     * MVK-LEDGER, not this assertion. What arbitrates that {@code checkConsistency} really ran for the tables
     * above is the refusal rows in the two tests below.
     */
    @Test
    void aSelfReferencingOperandIsStillRefused() {
        Calculation readsItsOwnTarget = Dsl.firstFilledNumber(
                M.num(FIXED_TARGET).field(), M.num("/Catalogue/Terms/TermMarker").field()).expression();
        PrecheckException refusal = assertThrows(PrecheckException.class,
                () -> verdict(FIXED_TARGET, "/Catalogue/Pricing", readsItsOwnTarget),
                "the operand side has a gate; it is just not a containment one");
        assertTrue(refusal.getMessage().contains("MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD"), refusal::getMessage);
    }

    /**
     * The widest separation available: containment is PATH containment, with no root-relative component. A
     * fixed target is admitted from the other root, and a repeatable one is refused there exactly as it is
     * from a non-containing sibling inside its own root — with its own ancestor admitted as the control that
     * keeps the refusal about containment rather than about the target.
     */
    @Test
    void containmentIsPathContainmentAndNotRootRelative() {
        Calculation constant = NumberLiteral.of(-1);
        Calculation otherRootStar = Dsl.sum(M.flatten("/Audit/Trail*/TrailUnits")).expression();
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("fixed target, constant, declared in the OTHER root",
                verdict(FIXED_TARGET, "/Audit/Ledger", constant));
        actual.put("fixed target, star over the OTHER root's extent, declared at home",
                verdict(FIXED_TARGET, "/Catalogue/Pricing", otherRootStar));
        actual.put("repeatable target, constant, declared in the OTHER root",
                verdict(REPEATABLE_TARGET, "/Audit/Ledger", constant));
        actual.put("repeatable target, constant, declared at a same-root non-containing sibling",
                verdict(REPEATABLE_TARGET, "/Catalogue/Terms", constant));
        actual.put("repeatable target, constant, declared at its own ancestor — control",
                verdict(REPEATABLE_TARGET, "/Catalogue", constant));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("fixed target, constant, declared in the OTHER root", KernelLaws.ADMITTED);
        expected.put("fixed target, star over the OTHER root's extent, declared at home", KernelLaws.ADMITTED);
        expected.put("repeatable target, constant, declared in the OTHER root", NOT_IN_RULEGROUP);
        expected.put("repeatable target, constant, declared at a same-root non-containing sibling",
                NOT_IN_RULEGROUP);
        expected.put("repeatable target, constant, declared at its own ancestor — control", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "crossing a root boundary is not itself a refusal; failing containment is");
    }

    /**
     * Which gate answers an UNSTARRED read of a repeatable field depends on the declaring group's own
     * repeatability, so the two refusals are not interchangeable: {@code MVK_NO_WILDCARD} pre-empts placement
     * only from a non-repeatable declaring group; from the repeatable group it reads, the same operand reaches
     * the placement gate instead.
     *
     * <p><b>The index declaration is controlled, and it turns out to move the code</b> — which is why the
     * qualifier above is about which gate PRE-EMPTS rather than about which code a fixed declaring group
     * always yields. {@code Batches} and {@code Lots} differ in nothing but the declaration, and from the same
     * fixed declaring group the non-indexed extent reports {@code MVK_NO_WILDCARD} while the indexed one
     * reports {@code MVK_ERROR_FIELD_NO_INDEX}. So "an unstarred repeatable read from a fixed declaring group
     * is MVK_NO_WILDCARD" holds only for a non-index-keyed extent; a row measured on an indexed group alone
     * would have generalized the wrong code. (a12-kernel-lean's {@code SPEC-2026-08-29-03} states the
     * unqualified form; this is the control it did not hold.) The invariant that survives both is the second
     * column: from the repeatable group it reads, either extent reaches the placement gate.
     */
    @Test
    void theWildcardGatePreEmptsPlacementOnlyFromANonRepeatableDeclaringGroup() {
        Calculation lotRow = M.num("/Catalogue/Lots/Quantity").value();
        Calculation batchRow = M.num("/Catalogue/Batches/Quantity").value();
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("non-indexed extent, from a FIXED declaring group",
                verdict(FIXED_TARGET, "/Catalogue/Pricing", lotRow));
        actual.put("non-indexed extent, from the repeatable group it reads",
                verdict(FIXED_TARGET, "/Catalogue/Lots", lotRow));
        actual.put("indexed extent, from a FIXED declaring group",
                verdict(FIXED_TARGET, "/Catalogue/Pricing", batchRow));
        actual.put("indexed extent, from the repeatable group it reads",
                verdict(FIXED_TARGET, "/Catalogue/Batches", batchRow));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("non-indexed extent, from a FIXED declaring group", "MVK_NO_WILDCARD");
        expected.put("non-indexed extent, from the repeatable group it reads", NOT_IN_RULEGROUP);
        expected.put("indexed extent, from a FIXED declaring group", "MVK_ERROR_FIELD_NO_INDEX");
        expected.put("indexed extent, from the repeatable group it reads", NOT_IN_RULEGROUP);
        assertEquals(expected, actual,
                "the declaring group decides whether placement is reached; the extent's keying decides which gate answers");
    }
}

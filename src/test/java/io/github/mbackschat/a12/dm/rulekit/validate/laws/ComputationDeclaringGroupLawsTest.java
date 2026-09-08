package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.EntityPath;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Calculation;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberLiteral;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * KERNEL-FINDINGS — <b>{@code MVK_ERROR_FIELD_NOT_IN_RULEGROUP} fires exactly when iteration is DERIVED and the
 * written field does not lie AT OR BELOW the declaring group.</b> The demand is containment, not parenthood: an
 * ancestor of the target satisfies it, an unrelated sibling does not. Iteration is derived from either of two
 * sources, independently — a per-row operand of a repeatable declaring group, or <b>the written field's own
 * repeatable scope</b>, which needs no operand at all.
 *
 * <p><b>This class asserted a false universal for one day, and the second source is why.</b> It read "placement
 * alone never refuses"; every table in it pinned the written field NON-REPEATABLE, so with that variable held
 * the operand was the only thing left that could vary, and the matrix could not see the other source. The
 * counterexample — a bare string constant into a repeatable target from an unrelated fixed group, refused with
 * this code and no operand anywhere — came from a12-kernel-lean ({@code SPEC-2026-08-29-01}, 2026-08-29) and is
 * reproduced here as this repo's own measurement in
 * {@link #aRepeatableTargetDerivesIterationBySelfAndReimposesContainment}. The earlier tables are kept, now
 * saying only what they measure: with a non-repeatable target, no iteration is derived, so no placement refuses.
 *
 * <p><b>The two constructs are NOT symmetric on the second source</b>, though they share the gate. A rule's error
 * field must be REFERENCED by its condition ({@code MVK_ERROR_FIELD_NOT_REFERENCED}), so a repeatable error field
 * is necessarily also a per-row operand and the two sources collapse into one; only a computation can pair a
 * constant operand with a repeating target. That asymmetry is
 * {@link #aRuleCannotSeparateTheTwoDerivationSourcesBecauseItsErrorFieldMustBeReferenced}, and it is why the
 * counterexample could only have been found on the computation side.
 *
 * <p><b>One variable per row.</b> Each table varies the declaring group while holding the target field and the
 * operand fixed, and the two axes that turned out to matter — operand kind, and target repeatability — each get
 * their own table. A constant operation is what isolates the target axis: an operand reference brings its own
 * scope, which is {@link ComputationOperandScopeLawsTest}'s law.
 *
 * <p><b>Accept/reject only.</b> {@code checkConsistency} settles whether the model LOADS. Where a cross-group
 * computation then fires, and at which context, is a runtime question this tier cannot answer; it is
 * {@code adapter/.../laws/CrossGroupConstantComputeScopeDiffTest}'s.
 *
 * <p>Own domain (an e-commerce depot with a separate policy container).
 */
class ComputationDeclaringGroupLawsTest {

    private static final String NOT_IN_RULEGROUP = "MVK_ERROR_FIELD_NOT_IN_RULEGROUP";
    private static final String NO_INDEX = "MVK_ERROR_FIELD_NO_INDEX";

    /**
     * A depot whose stock levels, a policy container, and a repeatable shipment log are three separate
     * subtrees under one root — so a declaring group can be the field's own parent, an ancestor, a
     * non-repeatable sibling subtree, or a repeatable one, without changing anything else.
     */
    private static final String DEPOT = DocumentModelDraft.named("depot").locale("en_US")
            .rootGroup("Depot", d -> d
                    .stringField("DepotRef", s -> { })
                    .group("Stock", s -> s
                            .numberField("StockLevel", n -> n.fractionDigits(0))
                            .numberField("ReorderPoint", n -> n.fractionDigits(0)))
                    .group("Policy", p -> p
                            .numberField("PolicyMarker", n -> n.fractionDigits(0)))
                    .group("Shipments", sh -> sh.repeatable(20).indexField("ShipmentKey")
                            .field("ShipmentKey", FieldKind.STRING)
                            .numberField("Units", n -> n.fractionDigits(0)))
                    // The index control. Shipments DECLARES its key field as the group's index, and this
                    // repo has already measured that the declaration alone flips a neighbouring verdict
                    // (ComputationOperandScopeLawsTest.aSiblingRepeatableOperandIsDecidedByTheIndexFieldNames),
                    // so a per-row table built only on Shipments measures siblinghood and index-keying at once.
                    // Returns carries the same shape WITHOUT the declaration.
                    .group("Returns", r -> r.repeatable(20)
                            .field("ReturnKey", FieldKind.STRING)
                            .numberField("Units", n -> n.fractionDigits(0))))
            .build().toJson();

    private static final BoundModel M = BoundModel.ofJson(DEPOT);

    private static final String STOCK_LEVEL = "/Depot/Stock/StockLevel";
    private static final String PER_ROW_FIELD = "/Depot/Shipments/Units";

    private static String computationVerdict(String group, Calculation operation) {
        return computationVerdict(STOCK_LEVEL, group, operation);
    }

    private static String computationVerdict(String computedField, String group, Calculation operation) {
        return KernelLaws.computationVerdict(DEPOT, M.computation(M.num(computedField))
                .name("StockProbe")
                .group(M.group(group))
                .calc(operation)
                .message(Dsl.en("Declaring-group probe."))
                .build());
    }

    /**
     * The rule twin: the same (group, target field) pair and the same operand axis, carried by a rule.
     *
     * <p>The error path is derived with the same {@code relativize} the computation arm uses, which would be
     * circular if nothing pinned the strings — {@link #theRuleArmsDerivedErrorPathsAreTheExpectedWalks} pins
     * all four, so a relativize that changed would fail there rather than silently re-defining both arms.
     */
    private static String ruleVerdict(String group, String condition) {
        return ruleVerdict(STOCK_LEVEL, group, condition);
    }

    private static String ruleVerdict(String errorField, String group, String condition) {
        ValidationResult r = KernelLaws.checkModel(DEPOT, group, errorRelPath(group, errorField), condition);
        return r.ok() ? KernelLaws.ADMITTED : r.diagnostics().stream()
                .map(d -> d.code().orElse("(no code: " + d.summary() + ")"))
                .collect(Collectors.joining(","));
    }

    private static String errorRelPath(String group) {
        return errorRelPath(group, STOCK_LEVEL);
    }

    private static String errorRelPath(String group, String errorField) {
        return EntityPath.of(group + "/ProbeRule").relativize(EntityPath.of(errorField));
    }

    /** Pins what the rule arm actually hands the kernel, so its verdicts are not read off a derived unknown. */
    @Test
    void theRuleArmsDerivedErrorPathsAreTheExpectedWalks() {
        Map<String, String> actual = new LinkedHashMap<>();
        for (String group : new String[] {"/Depot/Stock", "/Depot", "/Depot/Policy", "/Depot/Shipments"}) {
            actual.put(group, errorRelPath(group));
        }

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("/Depot/Stock", "../StockLevel");
        expected.put("/Depot", "../Stock/StockLevel");
        expected.put("/Depot/Policy", "../../Stock/StockLevel");
        expected.put("/Depot/Shipments", "../../Stock/StockLevel");
        assertEquals(expected, actual);
    }

    /**
     * The law the {@code computation add} {@code group} key rests on: with a constant operation, every
     * declaring-group relation to the computed field is admitted.
     *
     * <p><b>An all-{@code ADMITTED} table cannot see a missing kernel.</b> Without a backend
     * {@code RuleValidator} degrades to {@code STRUCTURAL_ONLY} and {@code ok()} stays true, so every row here
     * would read {@code ADMITTED} for the wrong reason. The refusal tables below are this class's positive
     * control — they can only report their codes if {@code checkConsistency} actually arbitrated — which is
     * why the accept rows are never asserted alone.
     */
    @Test
    void aConstantComputationIsAcceptedWhereverItIsDeclared() {
        Calculation constant = NumberLiteral.of(-1);
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("the computed field's own parent — control", computationVerdict("/Depot/Stock", constant));
        actual.put("an ancestor of the computed field", computationVerdict("/Depot", constant));
        actual.put("a non-repeatable sibling subtree", computationVerdict("/Depot/Policy", constant));
        actual.put("a repeatable sibling subtree", computationVerdict("/Depot/Shipments", constant));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("the computed field's own parent — control", KernelLaws.ADMITTED);
        expected.put("an ancestor of the computed field", KernelLaws.ADMITTED);
        expected.put("a non-repeatable sibling subtree", KernelLaws.ADMITTED);
        expected.put("a repeatable sibling subtree", KernelLaws.ADMITTED);
        assertEquals(expected, actual,
                "with a NON-REPEATABLE target, no iteration is derived, so no placement is refused");
    }

    /**
     * The row that falsifies "placement alone never refuses" — <b>iteration has a SECOND source</b>, and every
     * table above is blind to it because all of them pin the target non-repeatable.
     *
     * <p>The operation here is a constant: no operand, nothing crossing a group, nothing iterating in the
     * condition. The iteration is derived from the COMPUTED FIELD's own repeatable scope, through the generated
     * rule anchored at that field — so an unrelated declaring group is refused with the same code an operand
     * would have drawn. The root ancestor is admitted, which is the other half: the demand is CONTAINMENT, not
     * parenthood, so any group the target lies at or below satisfies it.
     *
     * <p><b>Provenance.</b> Reported as a counterexample by a12-kernel-lean (`SPEC-2026-08-29-01`, 2026-08-29)
     * against exactly the over-general claim this class used to assert, and reproduced here rather than adopted
     * — all four rows below are this repo's own kernel measurement.
     */
    @Test
    void aRepeatableTargetDerivesIterationBySelfAndReimposesContainment() {
        Calculation constant = NumberLiteral.of(-1);
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("the target's own repeatable parent", computationVerdict(PER_ROW_FIELD, "/Depot/Shipments", constant));
        actual.put("an ancestor of the repeatable target", computationVerdict(PER_ROW_FIELD, "/Depot", constant));
        actual.put("an unrelated FIXED subtree", computationVerdict(PER_ROW_FIELD, "/Depot/Policy", constant));
        actual.put("an unrelated REPEATABLE subtree", computationVerdict(PER_ROW_FIELD, "/Depot/Returns", constant));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("the target's own repeatable parent", KernelLaws.ADMITTED);
        expected.put("an ancestor of the repeatable target", KernelLaws.ADMITTED);
        expected.put("an unrelated FIXED subtree", NOT_IN_RULEGROUP);
        expected.put("an unrelated REPEATABLE subtree", NOT_IN_RULEGROUP);
        assertEquals(expected, actual,
                "a repeatable target derives iteration with no operand at all; containment then decides");
    }

    /**
     * The same gate reached through an <b>operand</b>, on the route a12-kernel-lean's {@code SPEC-2026-08-29-02}
     * corrects: a star aggregate over a sibling repeatable group, computed into a repeatable target. The earlier
     * account of that shape (KERNEL-FINDINGS §11's sibling-star entry) measured only the target's own parent,
     * and the sentence it produced — "declared at its target's own parent group" — reads as the sole admitted
     * placement. It is not.
     *
     * <p><b>The four verdicts are cell-for-cell the bare constant's above, and that identity is the finding.</b>
     * A star aggregate contributes no per-row read, so it derives no iteration of its own; the iteration still
     * comes from the target's own repeatable scope, and an aggregating operand changes nothing about which
     * placements survive. Read together with the constant table, the pair separates the two candidate accounts
     * of the admission — "the star operand earns the ancestor" versus "containment alone decides" — because
     * only the second predicts a row-for-row match.
     */
    @Test
    void aStarAggregateOperandLeavesTheContainmentGateExactlyWhereTheConstantPutIt() {
        Calculation starAggregate = Dsl.sum(M.flatten("/Depot/Returns*/Units")).expression();
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("the target's own repeatable parent",
                computationVerdict(PER_ROW_FIELD, "/Depot/Shipments", starAggregate));
        actual.put("an ancestor of the repeatable target",
                computationVerdict(PER_ROW_FIELD, "/Depot", starAggregate));
        actual.put("an unrelated FIXED subtree", computationVerdict(PER_ROW_FIELD, "/Depot/Policy", starAggregate));
        actual.put("the starred source's own repeatable group",
                computationVerdict(PER_ROW_FIELD, "/Depot/Returns", starAggregate));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("the target's own repeatable parent", KernelLaws.ADMITTED);
        expected.put("an ancestor of the repeatable target", KernelLaws.ADMITTED);
        expected.put("an unrelated FIXED subtree", NOT_IN_RULEGROUP);
        expected.put("the starred source's own repeatable group", NOT_IN_RULEGROUP);
        assertEquals(expected, actual, "an aggregating operand neither adds nor removes a placement");
    }

    /**
     * The rule twin, and it shows the two constructs are <b>not symmetric on this axis</b> — which is worth a
     * test of its own, because the natural move after the row above is to assume they are.
     *
     * <p>A rule cannot exhibit the second derivation source independently: the kernel demands its error field be
     * REFERENCED by the condition ({@code MVK_ERROR_FIELD_NOT_REFERENCED}, measured 2026-08-29 by writing this
     * test the other way first), so a repeatable error field is necessarily also a per-row operand. The two
     * sources collapse into one. A computation has no such demand — its operand may be a bare constant while its
     * computed field repeats — which is exactly why the counterexample above is a computation and could not have
     * been found on the rule side.
     */
    @Test
    void aRuleCannotSeparateTheTwoDerivationSourcesBecauseItsErrorFieldMustBeReferenced() {
        // FieldFilled, not FieldNotFilled: a negative condition inside an iteration is its own kernel
        // refusal (MVK_NEG_CONDITION_IN_ITERATION) and would mask the gate under test.
        String ownField = "FieldFilled(" + PER_ROW_FIELD + ")";
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("error field not referenced by the condition",
                ruleVerdict(PER_ROW_FIELD, "/Depot/Shipments", "FieldNotFilled(" + STOCK_LEVEL + ")"));
        actual.put("referenced, declared in its own repeatable parent",
                ruleVerdict(PER_ROW_FIELD, "/Depot/Shipments", ownField));
        actual.put("referenced, declared in an unrelated FIXED subtree",
                ruleVerdict(PER_ROW_FIELD, "/Depot/Policy", ownField));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("error field not referenced by the condition", "MVK_ERROR_FIELD_NOT_REFERENCED");
        expected.put("referenced, declared in its own repeatable parent", KernelLaws.ADMITTED);
        expected.put("referenced, declared in an unrelated FIXED subtree", NOT_IN_RULEGROUP);
        assertEquals(expected, actual, "a rule's error field is always an operand, so the sources cannot split");
    }

    /**
     * The same four placements for a rule, with a condition that does not iterate — admitted throughout, which
     * is what falsifies "the error field must be inside the rule group" as a statement about geometry.
     *
     * <p>Worth knowing at the CLI boundary: {@code rule add} refuses the two sibling rows client-side
     * ({@code EntityPaths.isStrictlyInside}), so it is stricter here than the kernel it delegates to.
     */
    @Test
    void aNonIteratingRuleIsAlsoAcceptedWhereverItIsDeclared() {
        String plain = "FieldNotFilled(" + STOCK_LEVEL + ")";
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("the error field's own parent — control", ruleVerdict("/Depot/Stock", plain));
        actual.put("an ancestor of the error field", ruleVerdict("/Depot", plain));
        actual.put("a non-repeatable sibling subtree", ruleVerdict("/Depot/Policy", plain));
        actual.put("a repeatable sibling subtree", ruleVerdict("/Depot/Shipments", plain));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("the error field's own parent — control", KernelLaws.ADMITTED);
        expected.put("an ancestor of the error field", KernelLaws.ADMITTED);
        expected.put("a non-repeatable sibling subtree", KernelLaws.ADMITTED);
        expected.put("a repeatable sibling subtree", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "a rule's group/error-field geometry is not what the code checks either");
    }

    /**
     * Switch ONE thing — the operand becomes a per-row field of the repeatable group — and the same four
     * placements split, identically for both constructs. That identity is the finding: the two rows carrying
     * {@code MVK_ERROR_FIELD_NOT_IN_RULEGROUP} are the ones declared IN the repeatable group they read, with
     * the written field outside it; the others cannot reach a row at all and report
     * {@code MVK_ERROR_FIELD_NO_INDEX} instead.
     */
    @Test
    void aPerRowOperandReimposesContainmentOnRuleAndComputationAlike() {
        String perRow = "FieldNotFilled(" + PER_ROW_FIELD + ")";
        Calculation rowValue = M.num(PER_ROW_FIELD).value();
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("rule @ the repeatable group it reads", ruleVerdict("/Depot/Shipments", perRow));
        actual.put("computation @ the repeatable group it reads", computationVerdict("/Depot/Shipments", rowValue));
        actual.put("rule @ an ancestor of that group", ruleVerdict("/Depot", perRow));
        actual.put("computation @ an ancestor of that group", computationVerdict("/Depot", rowValue));
        actual.put("rule @ the target's own parent", ruleVerdict("/Depot/Stock", perRow));
        actual.put("computation @ the target's own parent", computationVerdict("/Depot/Stock", rowValue));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("rule @ the repeatable group it reads", NOT_IN_RULEGROUP);
        expected.put("computation @ the repeatable group it reads", NOT_IN_RULEGROUP);
        expected.put("rule @ an ancestor of that group", NO_INDEX);
        expected.put("computation @ an ancestor of that group", NO_INDEX);
        expected.put("rule @ the target's own parent", NO_INDEX);
        expected.put("computation @ the target's own parent", NO_INDEX);
        assertEquals(expected, actual, "iteration is what imposes containment, and it does so for both constructs");
    }

    /**
     * The index control for the table above: {@code Returns} differs from {@code Shipments} in exactly one
     * thing — it does not DECLARE its key field as the group's index — and the per-row rows are re-measured
     * on it.
     *
     * <p>Without this the table's codes are equally well explained by "the group is index-keyed", which this
     * repo has already measured to be decisive on a neighbouring axis. Whatever it reports, the containment
     * row is what the finding rests on, so it is the one that must survive dropping the declaration.
     */
    @Test
    void theContainmentRowSurvivesDroppingTheIndexDeclaration() {
        String perRow = "FieldNotFilled(/Depot/Returns/Units)";
        Calculation rowValue = M.num("/Depot/Returns/Units").value();
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("rule @ the non-indexed repeatable group it reads", ruleVerdict("/Depot/Returns", perRow));
        actual.put("computation @ the non-indexed repeatable group it reads",
                computationVerdict("/Depot/Returns", rowValue));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("rule @ the non-indexed repeatable group it reads", NOT_IN_RULEGROUP);
        expected.put("computation @ the non-indexed repeatable group it reads", NOT_IN_RULEGROUP);
        assertEquals(expected, actual, "the containment demand is the ITERATION's, not the index declaration's");
    }

    /**
     * THE OTHER HALF of that control, and the table above never had it. The index control re-measures only
     * the two CONTAINMENT rows; both rows reporting {@code MVK_ERROR_FIELD_NO_INDEX} were taken on
     * {@code Shipments} alone, so "the group declares an index" is constant across every row that carries
     * that code — and the code's own name, and the ledger's law for it ("the error-field path needs an index
     * field in an ancestor group"), are exactly what that constant would explain.
     *
     * <p>Re-measured on {@code Returns}, which differs in that one declaration: <b>they do not survive.</b>
     * All four report {@code MVK_NO_WILDCARD} instead — without an index there is no way to name a row at
     * all, so an earlier gate fires and the error-site analysis is never reached. The ledger's law for
     * {@code MVK_ERROR_FIELD_NO_INDEX} therefore holds in a sharper form than it states: the code is what an
     * INDEX-KEYED ancestor reports when the site cannot reach a row, and a non-keyed one never reports it.
     * A client-side pre-check for it must condition on the declaration, not merely on placement.
     */
    @Test
    void theNoIndexRowsDoNotSurviveDroppingTheIndexDeclaration() {
        String perRow = "FieldNotFilled(/Depot/Returns/Units)";
        Calculation rowValue = M.num("/Depot/Returns/Units").value();
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("rule @ an ancestor of the non-indexed group", ruleVerdict("/Depot", perRow));
        actual.put("computation @ an ancestor of the non-indexed group", computationVerdict("/Depot", rowValue));
        actual.put("rule @ the target's own parent", ruleVerdict("/Depot/Stock", perRow));
        actual.put("computation @ the target's own parent", computationVerdict("/Depot/Stock", rowValue));

        Map<String, String> expected = new LinkedHashMap<>();
        String noWildcard = "MVK_NO_WILDCARD";
        expected.put("rule @ an ancestor of the non-indexed group", noWildcard);
        expected.put("computation @ an ancestor of the non-indexed group", noWildcard);
        expected.put("rule @ the target's own parent", noWildcard);
        expected.put("computation @ the target's own parent", noWildcard);
        assertEquals(expected, actual, "without an index declaration an earlier gate fires and NO_INDEX is unreachable");
    }

    /** The stored consequence of the accepted placements: the reference written into the model. */
    @Test
    void theStoredReferenceWalksOutOfTheDeclaringGroupAndBackDown() {
        Map<String, String> actual = new LinkedHashMap<>();
        for (String group : new String[] {"/Depot/Stock", "/Depot", "/Depot/Policy", "/Depot/Shipments"}) {
            actual.put(group, M.computation(M.num(STOCK_LEVEL)).name("StockProbe").group(M.group(group))
                    .calc(NumberLiteral.of(-1)).message(Dsl.en("x")).build().computedFieldRelPath());
        }

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("/Depot/Stock", "../StockLevel");
        expected.put("/Depot", "../Stock/StockLevel");
        expected.put("/Depot/Policy", "../../Stock/StockLevel");
        expected.put("/Depot/Shipments", "../../Stock/StockLevel");
        assertEquals(expected, actual, "the reference is relative to the computation ELEMENT, so the first '..'"
                + " climbs off the element itself");
    }
}

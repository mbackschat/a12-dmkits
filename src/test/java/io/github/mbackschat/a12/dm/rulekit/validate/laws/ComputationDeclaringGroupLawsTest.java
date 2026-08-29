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
 * KERNEL-FINDINGS — <b>{@code MVK_ERROR_FIELD_NOT_IN_RULEGROUP} is about ITERATION, not about placement.</b>
 * A computation's declaring group and a rule's group are both free of the field the construct writes to or
 * attaches its error to; containment is imposed only once the construct ITERATES, i.e. its group is a
 * repeatable group whose per-row fields it reads.
 *
 * <p><b>This was measured against the opposite expectation, twice.</b> The obvious reading — a rule's error
 * field must lie inside its rule group, a computation's computed field need not, so the two constructs differ —
 * is what this file was written to lock, and the kernel refused it in both directions: a non-iterating rule
 * whose error field sits in a sibling subtree is ADMITTED, and an iterating COMPUTATION whose computed field
 * leaves its declaring group is refused with the very same code. So the axis that decides is the operand, and
 * the two constructs behave alike on it. The tables below are kept in the shape that showed this, because a
 * single-row accept would have re-published the wrong mechanism.
 *
 * <p><b>One variable per row.</b> Each table holds the target field fixed at {@link #STOCK_LEVEL} and varies the
 * declaring group; the operand axis is varied explicitly and separately (a constant vs a per-row reference),
 * because that is the variable that turned out to matter. A constant operation is what isolates placement:
 * an operand reference brings its own scope, which is {@link ComputationOperandScopeLawsTest}'s law.
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
        return KernelLaws.computationVerdict(DEPOT, M.computation(M.num(STOCK_LEVEL))
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
        ValidationResult r = KernelLaws.checkModel(DEPOT, group, errorRelPath(group), condition);
        return r.ok() ? KernelLaws.ADMITTED : r.diagnostics().stream()
                .map(d -> d.code().orElse("(no code: " + d.summary() + ")"))
                .collect(Collectors.joining(","));
    }

    private static String errorRelPath(String group) {
        return EntityPath.of(group + "/ProbeRule").relativize(EntityPath.of(STOCK_LEVEL));
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
        assertEquals(expected, actual, "placement alone never refuses a computation");
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

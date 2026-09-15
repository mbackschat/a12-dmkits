package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.ModelDrafts;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * WHICH reads count as reading the computed field — the half {@link ComputedTargetProjectedReadLawsTest}
 * leaves open, because every row there reads the field itself.
 *
 * <p>The kernel's predicate is not "names the computed field". {@code
 * NormalisierteBerechnungsFormHelper.feldReferenziertOhneKontextAngegeben} asks
 * {@code isDescendantOrEqual(calculatedField, referencedEntity)}, so a reference to any <b>ancestor</b> of
 * the computed field is a read of it. A checker keyed on path equality would admit those, which is why the
 * group rows below are the family's whole risk.
 *
 * <p>Two exemptions are carved into that walk and both are measured here rather than transcribed:
 * {@code GroupFilled} — the kernel's single-entity {@code AllGroupsFilled}, its "context specified" escape —
 * and a {@code CurrentRepetition} operand, which is not traversed at all. The near-miss is what makes them
 * exemptions rather than a rule about groups: the same group named by {@code GroupNotFilled} is refused.
 *
 * <p>Own domain (a routing sheet), and every rejected row sits beside a control that moves one thing.
 */
class ComputationSelfReadScopeLawsTest {

    private static final RuleValidator VALIDATOR = new RuleValidator();
    private static final String PLAIN = "MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD";

    private static final String ROUTING = ModelDrafts.routing().toJson();

    /**
     * The complete emitted code list for one computation authored as DSL text.
     *
     * <p>The size assertion is the same guard {@link ComputedTargetProjectedReadLawsTest} carries: a kernel
     * notification with no {@code MVK_*} token would otherwise be invisible to an "empty" row.
     */
    private static List<String> codes(String group, String relTarget, String operation, String precondition) {
        DraftComputation comp = new DraftComputation(group, "LegComp", relTarget, null,
                List.of(new DraftComputation.Alternative(precondition, operation)), List.of());
        ValidationResult result = VALIDATOR.check(ROUTING, comp);
        List<String> codes = result.diagnostics().stream().flatMap(d -> d.code().stream()).toList();
        assertEquals(result.diagnostics().size(), codes.size(),
                () -> "a diagnostic without an MVK code would be invisible to this row: " + result.diagnostics());
        return codes;
    }

    /**
     * A guard over the computed field's own GROUP, which is a read of the field by the kernel's predicate.
     *
     * <p>The operation is the constant {@code 1} throughout, whose derived scale matches {@code Distance}'s
     * declared zero — so the scale gate [KF181] puts ahead of this class cannot fire and each row's verdict
     * is the guard's alone.
     */
    @Test
    void aGuardOverTheComputedFieldsOwnGroupIsAReadOfIt() {
        Map<String, List<String>> actual = new LinkedHashMap<>();
        actual.put("no guard at all — the liveness control", legs(null));
        actual.put("a SIBLING field of the computed one", legs("FieldFilled(LegRef)"));
        actual.put("the computed field itself", legs("FieldFilled(Distance)"));
        actual.put("GroupNotFilled over its own group", legs("GroupNotFilled(/Routing/Legs)"));
        actual.put("NumberOfFilledGroups over its own group",
                legs("NumberOfFilledGroups(/Routing/Legs*) > 0"));
        actual.put("GroupFilled over its own group — the context escape",
                legs("GroupFilled(/Routing/Legs)"));
        actual.put("CurrentRepetition of its own group", legs("CurrentRepetition(/Routing/Legs) == 1"));
        // The kernel exempts its `AllGroupsFilled` only at arity ONE, so whether this catalog's spelling of
        // it can carry a second entity at all decides whether that arity test is reachable from here.
        actual.put("GroupFilled over TWO groups, one of them its own",
                legs("GroupFilled(/Routing/Legs, /Routing/Depots)"));
        actual.put("GroupFilled over a group that contains nothing of it — the control",
                legs("GroupFilled(/Routing/Depots)"));

        Map<String, List<String>> expected = new LinkedHashMap<>();
        expected.put("no guard at all — the liveness control", List.of());
        expected.put("a SIBLING field of the computed one", List.of());
        expected.put("the computed field itself", List.of(PLAIN));
        // The ancestor half: naming the GROUP refuses, so the predicate is containment and not equality.
        expected.put("GroupNotFilled over its own group", List.of(PLAIN));
        expected.put("NumberOfFilledGroups over its own group", List.of(PLAIN));
        // …and the two shapes it exempts, which keep the rows above from reading as "any mention of the
        // group". A checker taking either half alone is wrong in one direction.
        expected.put("GroupFilled over its own group — the context escape", List.of());
        expected.put("CurrentRepetition of its own group", List.of());
        // The arity gate answers ahead of the calculation walk, so the kernel's own `size() == 1` test on
        // its exemption is unreachable through this catalog's spelling — a checker needs no arity branch.
        expected.put("GroupFilled over TWO groups, one of them its own", List.of("MVK_PARAMSIZE_INVALID1"));
        expected.put("GroupFilled over a group that contains nothing of it — the control", List.of());
        assertEquals(expected, actual, "which reads of the computed field's group count as reading it");
    }

    /**
     * The ROOT group is an ancestor of every field, so the containment predicate reaches it too.
     *
     * <p>It draws this class, and the third row is what attributes that to the calculation walk rather than
     * to anything about naming a root group: the identical predicate in an ordinary RULE is ADMITTED, so no
     * parse-time gate speaks about it and there is no competing class for the self-read one to pre-empt. The
     * expectation written first said {@code MVK_ROOT_GROUP_REFERENCED}, transferred from the operand
     * families where a root group IS refused — that class belongs to entity LISTS, and a group predicate is
     * not one.
     */
    @Test
    void theRootAncestorDrawsTheSelfReadClassToo() {
        Map<String, List<String>> actual = new LinkedHashMap<>();
        actual.put("GroupNotFilled over the ROOT", legs("GroupNotFilled(/Routing)"));
        actual.put("GroupNotFilled over the nested group — the control",
                legs("GroupNotFilled(/Routing/Legs)"));
        actual.put("the same root read in an ordinary RULE", rootInRule());

        Map<String, List<String>> expected = new LinkedHashMap<>();
        expected.put("GroupNotFilled over the ROOT", List.of(PLAIN));
        expected.put("GroupNotFilled over the nested group — the control", List.of(PLAIN));
        expected.put("the same root read in an ordinary RULE", List.of());
        assertEquals(expected, actual, "the root ancestor against the nested one");
    }

    /** The same root-group read outside a computation, so the rows above are the calculation walk's own. */
    private static List<String> rootInRule() {
        ValidationResult result = VALIDATOR.check(ROUTING, new DraftRule("/Routing", "LegRule",
                "../PlannedLeg", "GroupNotFilled(/Routing)", "R1", "ERROR"));
        return result.diagnostics().stream().flatMap(d -> d.code().stream()).toList();
    }

    /**
     * The same law on a target with no scale at all, which is the domain a checker can carry unaided.
     *
     * <p>[KF181] puts the implicit target-scale comparison ahead of this class and [KF182] puts it ahead of
     * the projected one too, so on a NUMBER target neither is the first rejection until the derived scale is
     * known. A string target has no such comparison, and these rows measure that the class is otherwise
     * identical there rather than assuming the gate was the only difference.
     */
    @Test
    void aTargetWithNoScaleCarriesTheSameLaw() {
        Map<String, List<String>> actual = new LinkedHashMap<>();
        actual.put("a string target reading itself", plannedLeg("[/Routing/PlannedLeg]", null));
        actual.put("a string target reading a peer — the control", plannedLeg("[/Routing/PreferredLeg]", null));
        actual.put("a string target guarded by a read of itself",
                plannedLeg("[/Routing/PreferredLeg]", "FieldFilled(PlannedLeg)"));

        Map<String, List<String>> expected = new LinkedHashMap<>();
        expected.put("a string target reading itself", List.of(PLAIN));
        expected.put("a string target reading a peer — the control", List.of());
        expected.put("a string target guarded by a read of itself", List.of(PLAIN));
        assertEquals(expected, actual, "the self-read law on a target the scale gate cannot reach");
    }

    private static List<String> legs(String precondition) {
        return codes("/Routing/Legs", "../Distance", "1", precondition);
    }

    private static List<String> plannedLeg(String operation, String precondition) {
        return codes("/Routing", "../PlannedLeg", operation, precondition);
    }
}

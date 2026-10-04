package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * Which group-LIST guards over a group containing the computed field count as reading it (a12-kernel-lean
 * SPEC-2026-10-03-05). {@link ComputationSelfReadScopeLawsTest} settles the single-group predicates: containment
 * makes a group a read of every field below it, and {@code GroupFilled} is carved out. This class measures the list
 * quantifiers, where one more form is carved out: {@code AtLeastOneGroupFilled} over a sole ROOT group. The same
 * quantifier over a sole non-root group, every multi-group list, the negative {@code NoGroupFilled} over the root and
 * the starred forms all draw the self-read class.
 *
 * <p>Own domain (a fleet yard). Every guard computes the constant {@code 1} into a scale-0 Number, so the scale gate
 * that answers ahead of this class (KF181) cannot fire and each row's verdict is the guard's alone.
 */
class GroupListSelfReadLawsTest {

    private static final RuleValidator VALIDATOR = new RuleValidator();
    private static final String PLAIN = "MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD";

    private static final String FLEET = DocumentModelDraft.named("fleet-yard").locale("en_US")
            .rootGroup("Fleet", root -> root
                    .numberField("Total", n -> n.fractionDigits(0))
                    .group("Hub", g -> g.numberField("Load", n -> n.fractionDigits(0)))
                    .group("Yard", g -> g.numberField("Spare", n -> n.fractionDigits(0)))
                    .group("Bays", g -> g.repeatable(3).numberField("Fill", n -> n.fractionDigits(0))))
            .build().toJson();

    private static List<String> codes(String group, String relTarget, String precondition) {
        DraftComputation comp = new DraftComputation(group, "YardComp", relTarget, null,
                List.of(new DraftComputation.Alternative(precondition, "1")), List.of());
        ValidationResult result = VALIDATOR.check(FLEET, comp);
        List<String> codes = result.diagnostics().stream().flatMap(d -> d.code().stream()).toList();
        assertEquals(result.diagnostics().size(), codes.size(),
                () -> "a diagnostic without an MVK code would be invisible to this row: " + result.diagnostics());
        return codes;
    }

    private static List<String> hub(String precondition) {
        return codes("/Fleet/Hub", "../Load", precondition);
    }

    @Test
    void onlyThePositiveRootFormIsCarvedOutOfTheListQuantifiers() {
        Map<String, List<String>> actual = new LinkedHashMap<>();
        actual.put("GroupFilled over its own group — the control", hub("GroupFilled(/Fleet/Hub)"));
        actual.put("AtLeastOneGroupFilled over its own group alone", hub("AtLeastOneGroupFilled(/Fleet/Hub)"));
        actual.put("NoGroupFilled over its own group alone", hub("NoGroupFilled(/Fleet/Hub)"));
        actual.put("AllGroupsFilled over its own group and a sibling", hub("AllGroupsFilled(/Fleet/Hub, /Fleet/Yard)"));
        actual.put("AtLeastOneGroupFilled over its own group and a sibling",
                hub("AtLeastOneGroupFilled(/Fleet/Hub, /Fleet/Yard)"));
        actual.put("NotAllGroupsFilled over its own group and a sibling",
                hub("NotAllGroupsFilled(/Fleet/Hub, /Fleet/Yard)"));
        actual.put("NoGroupFilled over its own group and a sibling", hub("NoGroupFilled(/Fleet/Hub, /Fleet/Yard)"));
        actual.put("AtLeastOneGroupFilled over the root, target in the root",
                codes("/Fleet", "../Total", "AtLeastOneGroupFilled(/Fleet)"));
        actual.put("AtLeastOneGroupFilled over the root, target below it", hub("AtLeastOneGroupFilled(/Fleet)"));
        actual.put("NoGroupFilled over the root", codes("/Fleet", "../Total", "NoGroupFilled(/Fleet)"));
        actual.put("AtLeastOneGroupFilled over its starred group",
                codes("/Fleet/Bays", "../Fill", "AtLeastOneGroupFilled(/Fleet/Bays*)"));
        actual.put("NoGroupFilled over its starred group",
                codes("/Fleet/Bays", "../Fill", "NoGroupFilled(/Fleet/Bays*)"));
        actual.put("AtLeastOneGroupFilled over a sibling alone — the control", hub("AtLeastOneGroupFilled(/Fleet/Yard)"));

        Map<String, List<String>> expected = new LinkedHashMap<>();
        expected.put("GroupFilled over its own group — the control", List.of());
        expected.put("AtLeastOneGroupFilled over its own group alone", List.of(PLAIN));
        expected.put("NoGroupFilled over its own group alone", List.of(PLAIN));
        expected.put("AllGroupsFilled over its own group and a sibling", List.of(PLAIN));
        expected.put("AtLeastOneGroupFilled over its own group and a sibling", List.of(PLAIN));
        expected.put("NotAllGroupsFilled over its own group and a sibling", List.of(PLAIN));
        expected.put("NoGroupFilled over its own group and a sibling", List.of(PLAIN));
        expected.put("AtLeastOneGroupFilled over the root, target in the root", List.of());
        expected.put("AtLeastOneGroupFilled over the root, target below it", List.of());
        expected.put("NoGroupFilled over the root", List.of(PLAIN));
        expected.put("AtLeastOneGroupFilled over its starred group", List.of(PLAIN));
        expected.put("NoGroupFilled over its starred group", List.of(PLAIN));
        expected.put("AtLeastOneGroupFilled over a sibling alone — the control", List.of());
        assertEquals(expected, actual, "which group-list guards read the computed field");
    }
}

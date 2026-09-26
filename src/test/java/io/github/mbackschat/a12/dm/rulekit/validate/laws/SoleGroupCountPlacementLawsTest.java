package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * KERNEL-FINDINGS [KF185] — <b>a sole unstarred repeatable group counted by {@code NumberOfFilledGroups} draws
 * the count's arity class, {@code MVK_PARAMSIZE_INVALIDGN}, wherever the rule's error field lies in that
 * group</b>, whichever group declares the rule. [KF185]'s own rows put the error field inside from the root and
 * from the counted group itself; the rows here move the DECLARING group and the counted level, which is what a
 * rival tying the class to the rule's group or to the outermost repeatable level predicts differently.
 *
 * <p>The control is the same operand with the error field outside the counted group, which draws
 * {@code MVK_NO_WILDCARD}, so the placement rather than the operand selects the class.
 *
 * <p>Reported by a12-kernel-lean as {@code SPEC-2026-09-26-05}; measured here on our own fixture, not carried
 * on their grid. Own domain (a claims portfolio).
 */
class SoleGroupCountPlacementLawsTest {

    /** A fixed group above a repeatable one, a second repeatable level below it, and a subgroup below that. */
    private static final String PORTFOLIO = DocumentModelDraft.named("count-placement").locale("en_US")
            .rootGroup("Portfolio", p -> p
                    .field("Ref", FieldKind.STRING)
                    .group("Branch", b -> b
                            .group("Policies", po -> po.repeatable(9)
                                    .field("PolicyNo", FieldKind.STRING)
                                    .group("Claims", c -> c.repeatable(9)
                                            .field("ClaimNo", FieldKind.STRING)
                                            .group("Assessor", a -> a.field("Name", FieldKind.STRING))))))
            .build().toJson();

    private static final String FLEET = DocumentModelDraft.named("count-placement-root").locale("en_US")
            .rootGroup("Fleet", f -> f.repeatable(9).field("Plate", FieldKind.STRING))
            .build().toJson();

    @Test
    void theErrorFieldInTheCountedGroupSelectsTheArityClassWhereverTheRuleIsDeclared() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("control: error field outside, rule at the root",
                KernelLaws.verdictModel(PORTFOLIO, "/Portfolio", "../Ref",
                        "FieldFilled(Ref) And NumberOfFilledGroups(Branch/Policies) > 0"));
        actual.put("rule at the counted group",
                KernelLaws.verdictModel(PORTFOLIO, "/Portfolio/Branch/Policies", "../PolicyNo",
                        "NumberOfFilledGroups(/Portfolio/Branch/Policies) > 0"));
        actual.put("rule at the root",
                KernelLaws.verdictModel(PORTFOLIO, "/Portfolio", "../Branch/Policies/PolicyNo",
                        "NumberOfFilledGroups(Branch/Policies) > 0"));
        actual.put("rule at the fixed group between",
                KernelLaws.verdictModel(PORTFOLIO, "/Portfolio/Branch", "../Policies/PolicyNo",
                        "NumberOfFilledGroups(Policies) > 0"));
        actual.put("second level, rule at the enclosing repeatable group",
                KernelLaws.verdictModel(PORTFOLIO, "/Portfolio/Branch/Policies", "../Claims/ClaimNo",
                        "NumberOfFilledGroups(Claims) > 0"));
        actual.put("second level, rule at the root",
                KernelLaws.verdictModel(PORTFOLIO, "/Portfolio", "../Branch/Policies/Claims/ClaimNo",
                        "NumberOfFilledGroups(Branch/Policies/Claims) > 0"));
        actual.put("a group holding a subgroup, error field in the subgroup",
                KernelLaws.verdictModel(PORTFOLIO, "/Portfolio/Branch/Policies/Claims", "../Assessor/Name",
                        "NumberOfFilledGroups(/Portfolio/Branch/Policies/Claims) > 0"));
        actual.put("a repeatable root",
                KernelLaws.verdictModel(FLEET, "/Fleet", "../Plate", "NumberOfFilledGroups(/Fleet) > 0"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("control: error field outside, rule at the root", "MVK_NO_WILDCARD");
        expected.put("rule at the counted group", "MVK_PARAMSIZE_INVALIDGN");
        expected.put("rule at the root", "MVK_PARAMSIZE_INVALIDGN");
        expected.put("rule at the fixed group between", "MVK_PARAMSIZE_INVALIDGN");
        expected.put("second level, rule at the enclosing repeatable group", "MVK_PARAMSIZE_INVALIDGN");
        expected.put("second level, rule at the root", "MVK_PARAMSIZE_INVALIDGN");
        expected.put("a group holding a subgroup, error field in the subgroup", "MVK_PARAMSIZE_INVALIDGN");
        expected.put("a repeatable root", "MVK_PARAMSIZE_INVALIDGN");
        assertEquals(expected, actual, "the error field's place, not the rule's group, selects the class");
    }
}

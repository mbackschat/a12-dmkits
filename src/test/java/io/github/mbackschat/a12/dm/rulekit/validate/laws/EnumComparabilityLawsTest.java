package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import io.github.mbackschat.a12.dm.ModelDrafts;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Kernel 31.1.1 compares a String or Enumeration field with an Enumeration whatever either declares as display
 * labels (KERNEL-FINDINGS KF655). Kernel 30.8.1 refused a text-bearing enumeration against a String or a textless
 * one, and two text-bearing ones whose token/label maps conflicted; 31.1.0 deleted that gate
 * ({@code FieldUtils.isEnumWithDisplayValues} has no caller left at {@code a12-kernel} {@code eec9134}).
 *
 * <p>The pairs are the old law's every arm, admitted and refused alike, so a gate surviving on any one of them
 * fails here.
 */
class EnumComparabilityLawsTest {

    private static final String ENUMS = ModelDrafts.enumComparability().toJson();

    @Test
    void labelsDecideNoComparison() {
        List<String[]> pairs = List.of(
                new String[] {"Str", "NoText", "=="},
                new String[] {"WithText", "WithText2", "=="},
                new String[] {"Str", "IdentityText", "!="},
                new String[] {"IdentityText", "NoText", "=="},
                new String[] {"WithText", "ForwardConflict", "=="},
                new String[] {"WithText", "ReverseConflict", "=="},
                new String[] {"IdentityMixed", "WithText", "=="},
                new String[] {"WithText", "PartialCompatible", "=="},
                new String[] {"WithText", "DisjointCompatible", "=="},
                new String[] {"Str", "WithText", "=="},
                new String[] {"NoText", "WithText", "=="});
        for (String[] pair : pairs) {
            KernelLaws.acceptsModel(ENUMS, "/Doc", "../" + pair[0],
                    "AllFieldsFilled(" + pair[0] + ", " + pair[1] + ") And [" + pair[0] + "] " + pair[2]
                            + " [" + pair[1] + "]");
        }
    }
}

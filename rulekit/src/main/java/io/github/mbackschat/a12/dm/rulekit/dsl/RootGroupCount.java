package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.core.KernelCode;
import io.github.mbackschat.a12.dm.core.KernelCodeRefusal;
import io.github.mbackschat.a12.dm.core.Refusals;
import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * {@code NumberOfFilledGroups(/RootGroup*)} over a <em>repeatable root group</em> — the count of its
 * filled repetitions. The kernel models this as a distinct operation ({@code INumberOfRootGroupsOperation},
 * "AnzahlAngegebenerVordrucke") because root groups are banned as operands of the nested-group
 * {@link CountAggregate} ({@code MVK_ROOT_GROUP_REFERENCED}); it shares the {@code NumberOfFilledGroups}
 * keyword. In scope for a single document model whose root group is a list — distinct from the
 * multi-form/USB Vordruck <em>conditions</em>, which stay out of scope. Build via
 * {@link Dsl#numberOfFilledRootGroups}.
 */
public record RootGroupCount(GroupRef rootGroup) implements Calculation {

    public RootGroupCount {
        try {
            requireRepeatable(rootGroup);
        } catch (KernelCodeRefusal refusal) {
            // An empty root answers MVK_ROOT_GROUP_IS_EMPTY first, which a GroupRef does not show (KERNEL-FINDINGS
            // KF458); BoundModel#numberOfFilledRootGroups reads the root and cites.
            throw refusal.withheld();
        }
    }

    /** The lone-group param law, measured on a root that holds a field ({@code validate.laws.EntityListOperandReadLawsTest}). */
    static void requireRepeatable(GroupRef rootGroup) {
        if (!rootGroup.repeating()) {
            throw Refusals.of(KernelCode.MVK_PARAMSIZE_INVALIDGN, "NumberOfFilledGroups over a root group needs a"
                    + " repeatable root group: " + rootGroup.path());
        }
    }

    @Override
    public ValueKind kind() {
        return ValueKind.NUMBER;
    }

    public String operatorId() {
        return "NumberOfFilledGroups";
    }
}

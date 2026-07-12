package io.github.mbackschat.a12.dm.rulekit.dsl;

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
        if (!rootGroup.repeating()) {
            throw new IllegalArgumentException("NumberOfFilledGroups over a root group needs a repeatable root"
                    + " group: " + rootGroup.path());
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

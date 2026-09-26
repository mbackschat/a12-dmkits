package io.github.mbackschat.a12.dm.adapter.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;

/**
 * A count's group operand is profiled once, however it was spelled (DEV-API-GAPS DG77). The profile keyed a
 * {@code GroupRef} operand with a trailing slash and a scope's starred group without one, and
 * {@code SynthesisProfile} reads the scanned chain from the key's parent, so the scope spelling left the group's
 * own rows out of the scan capacity. Own domain: an order shipped in parcels.
 */
class ConditionComplexityCountOperandTest {

    private static final BoundModel M = BoundModel.ofJson(DocumentModelDraft.named("shop").locale("en_US")
            .rootGroup("Order", o -> o
                    .field("Note", FieldKind.STRING)
                    .group("Parcels", g -> g.repeatable(5).field("Weight", FieldKind.NUMBER)))
            .build().toJson());

    @Test
    void aRepeatableGroupIsProfiledAlikeAsAGroupAndAsItsScope() {
        assertEquals(ConditionComplexity.of(Dsl.numberOfFilledFields(M.group("/Order/Parcels")).gt(0)),
                ConditionComplexity.of(Dsl.numberOfFilledFields(M.flattenGroup("/Order/Parcels*")).gt(0)));
    }

    /** The trailing slash is the key form of the group-list quantifiers' scope and the root-group count too. */
    @Test
    void aStarredGroupScopeNamesTheGroupItselfAsTheScannedCollection() {
        assertEquals(Set.of("/Order/Parcels/"),
                ConditionComplexity.of(Dsl.sum(M.flattenGroup("/Order/Parcels*")).gt(0)).collectionPaths());
    }
}

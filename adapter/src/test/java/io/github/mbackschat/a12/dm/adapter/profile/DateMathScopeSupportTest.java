package io.github.mbackschat.a12.dm.adapter.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldInfo;

/**
 * A scope offers the DATE_MATH construct exactly when the construct can then be built in it. Both answers read the
 * field's declared class ({@link FieldInfo#temporalClass()}, DEV-API-GAPS DG132), because the typed date handles
 * admit by that class alone: a DATE field declared {@code HH:mm:ss} has no date handle, and a TIME field declared
 * {@code yyyy-MM-dd} has one. The two cross-declared fields are the rows that separate the declared class from the
 * kind. Own domain (a travel insurance claim).
 */
class DateMathScopeSupportTest {

    private static final BoundModel CLAIM = BoundModel.ofJson(DocumentModelDraft.named("travel-claim").locale("en_US")
            .rootGroup("Claim", c -> c
                    .group("Delay", d -> d.dateField("DepartedAt", f -> f.format("HH:mm:ss")))
                    .group("Stay", s -> s.timeField("CheckIn", f -> f.format("yyyy-MM-dd"))))
            .build().toJson());

    @Test
    void aScopeOffersDateMathExactlyWhereItCanBuildIt() {
        Map<String, List<FieldInfo>> byGroup = new LinkedHashMap<>();
        CLAIM.fields().forEach(field -> byGroup.computeIfAbsent(field.groupPath(), ignored -> new java.util.ArrayList<>())
                .add(field));
        Map<String, List<Boolean>> observed = new LinkedHashMap<>();
        for (String scope : List.of("/Claim/Delay", "/Claim/Stay")) {
            observed.put(scope, List.of(
                    RuleModelSynthesizer.scopeSupports("DATE_MATH", scope, byGroup, Map.of(), Set.of()),
                    RuleModelSynthesizer.dateMathCondition(CLAIM, scope, byGroup) != null));
        }
        assertEquals(Map.of("/Claim/Delay", List.of(false, false), "/Claim/Stay", List.of(true, true)), observed);
    }
}

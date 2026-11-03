package io.github.mbackschat.a12.dm.adapter;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupInfo;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;

/**
 * A stored `SumOfTerms` reads back through the kernel into the typed condition it was authored as (KERNEL-FINDINGS
 * KF670), on every iteration shape the converter moves separately: plain, filtered with a `$` read in the filter,
 * nested, and iterated by the rule. A read that came back {@code Opaque} or rendered differently would make a legal
 * model unreadable to the typed surface rather than wrong, which is why each shape needs its own row.
 */
class SumOfTermsReadBackTest {

    private static final DslRenderer RENDER = new DslRenderer();

    private final BoundModel model = BoundModel.ofJson(DocumentModelDraft.named("policy-sum-of-terms-read").locale("en_US")
            .rootGroup("Policy", policy -> policy
                    .stringField("Holder", field -> { })
                    .numberField("Threshold", field -> { })
                    .group("Claims", claims -> claims
                            .repeatable(4)
                            .numberField("Amount", field -> { })
                            .group("Parts", parts -> parts.repeatable(3).numberField("Qty", field -> { }))))
            .build().toJson());
    private final GroupInfo policy = model.groupAt("/Policy").orElseThrow();
    private final GroupInfo claims = model.groupAt("/Policy/Claims").orElseThrow();
    private final GroupInfo parts = model.groupAt("/Policy/Claims/Parts").orElseThrow();

    @Test
    void everyIterationShapeReadsBackAsAuthored() {
        Condition held = policy.str("Holder").filled();
        Map<String, Rule> authored = new LinkedHashMap<>();
        authored.put("Plain", rule("Plain", policy.field("Holder"),
                held.and(Dsl.sumOfTerms(policy.flattenGroup("Claims*"), claims.num("Amount").asNumber()).gt(100))));
        authored.put("Filtered", rule("Filtered", policy.field("Holder"),
                held.and(Dsl.sumOfTerms(policy.flattenGroup("Claims*"),
                        claims.num("Amount").gt(policy.num("Threshold").outer()), claims.num("Amount").asNumber()).gt(100))));
        authored.put("Nested", rule("Nested", policy.field("Holder"),
                held.and(Dsl.sumOfTerms(policy.flattenGroup("Claims*/Parts*"), parts.num("Qty").asNumber()).gt(5))));
        authored.put("PerClaim", rule("PerClaim", claims.field("Amount"), claims.num("Amount").filled()
                .and(Dsl.sumOfTerms(claims.flattenGroup("Parts*"), parts.num("Qty").asNumber()).gt(5))));
        BoundModel stored = authored.values().stream().reduce(model, BoundModel::add, (left, right) -> right);
        KernelAdapter adapter = KernelAdapter.of(stored);

        Map<String, String> expected = new LinkedHashMap<>();
        Map<String, String> actual = new LinkedHashMap<>();
        authored.forEach((name, rule) -> {
            expected.put(name, RENDER.render(rule.condition()));
            actual.put(name, adapter.read("/Policy/" + name) instanceof ReadResult.Converted converted
                    ? RENDER.render(converted.condition()) : "not converted");
        });
        assertEquals(expected, actual);
    }

    private Rule rule(String name, FieldRef errorField, Condition condition) {
        return model.rule(policy.ref())
                .name(name)
                .errorField(errorField)
                .when(condition)
                .code(name)
                .severity(Severity.ERROR)
                .message(en("The claims do not add up."))
                .build();
    }
}

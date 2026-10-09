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
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;

/**
 * Two shapes a12-kernel-lean's caller capture found reading back unconverted: a first-filled value compared with
 * another first-filled value, and a range checked against a starred group's ranges. Each is authored, injected
 * into the kernel model and converted back; the read must equal the authored object.
 *
 * <p>Own domain (an insurance policy's claims).
 */
class AggregatePairAndGroupOverlapReadTest {

    private static final String POLICY = DocumentModelDraft.named("policy-aggregate-pair").locale("en_US")
            .rootGroup("Policy", p -> p
                    .stringField("Holder", f -> { })
                    .stringField("Alias", f -> { })
                    .dateRangeField("Cover", r -> r.format("yyyy-MM-dd").separator("/"))
                    .group("Claims", c -> c.repeatable(5)
                            .stringField("Note", f -> { })
                            .numberField("Amount", n -> n.fractionDigits(0))
                            .group("Windows", w -> w
                                    .dateRangeField("Window", r -> r.format("yyyy-MM-dd").separator("/")))))
            .build().toJson();

    @Test
    void bothShapesReadBackAsAuthored() {
        BoundModel m = BoundModel.ofJson(POLICY);
        Condition held = m.str("/Policy/Holder").filled();
        Map<String, Condition> authored = new LinkedHashMap<>();
        authored.put("FirstAgainstFirst", held.and(Dsl.firstFilledString(
                m.flatten("/Policy/Claims*/Note").having(m.num("/Policy/Claims/Amount").gt(0)))
                .eq(Dsl.firstFilledString(m.field("/Policy/Holder"), m.field("/Policy/Alias")))));
        authored.put("RangeAgainstGroup", held.and(Dsl.atLeastOneDateRangeOverlaps(
                m.field("/Policy/Cover"), m.flattenGroup("/Policy/Claims*/Windows"))));
        BoundModel withRules = m;
        for (Map.Entry<String, Condition> rule : authored.entrySet()) {
            withRules = withRules.add(m.rule(m.group("/Policy")).name(rule.getKey())
                    .errorField(m.field("/Policy/Holder")).when(rule.getValue())
                    .code("C").severity(Severity.ERROR).message(en("Check the policy.")).build());
        }
        KernelAdapter adapter = KernelAdapter.of(withRules);
        Map<String, Object> read = new LinkedHashMap<>();
        authored.keySet().forEach(name -> read.put(name, adapter.read("/Policy/" + name) instanceof
                ReadResult.Converted converted ? converted.condition() : adapter.read("/Policy/" + name)));
        assertEquals(authored, read);
    }
}

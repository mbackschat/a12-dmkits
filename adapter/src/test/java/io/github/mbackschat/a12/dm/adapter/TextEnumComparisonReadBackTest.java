package io.github.mbackschat.a12.dm.adapter;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.EnumRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;
import io.github.mbackschat.a12.dm.rulekit.dsl.StringRef;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;

/**
 * A stored comparison of a text field with an enumeration reads back into the typed condition it was authored as,
 * in both operand orders and for a labelled and a textless enumeration (KERNEL-FINDINGS KF655). A read that came back
 * unconverted would leave a legal model unreadable to the typed surface.
 */
class TextEnumComparisonReadBackTest {

    private static final DslRenderer RENDER = new DslRenderer();

    private final BoundModel model = BoundModel.ofJson(DocumentModelDraft.named("subscription-read").locale("en_US")
            .rootGroup("Subscription", s -> s
                    .stringField("RequestedPlan", f -> { })
                    .enumField("Plan", e -> e
                            .value("BASIC", l -> l.label("en_US", "Basic plan"))
                            .value("PRO", l -> l.label("en_US", "Pro plan")))
                    .enumField("Channel", e -> e.value("WEB").value("STORE")))
            .build().toJson());

    @Test
    void everyOperandOrderReadsBackAsAuthored() {
        StringRef requested = model.str("/Subscription/RequestedPlan");
        EnumRef plan = model.enumOf("/Subscription/Plan");
        EnumRef channel = model.enumOf("/Subscription/Channel");
        Map<String, Rule> authored = new LinkedHashMap<>();
        authored.put("EnumFirst", rule("EnumFirst", requested.filled().and(plan.eq(requested))));
        authored.put("TextFirst", rule("TextFirst", requested.filled().and(requested.ne(plan))));
        authored.put("Textless", rule("Textless", requested.filled().and(channel.eq(requested))));
        BoundModel stored = authored.values().stream().reduce(model, BoundModel::add, (left, right) -> right);
        KernelAdapter adapter = KernelAdapter.of(stored);
        Map<String, String> expected = new LinkedHashMap<>();
        Map<String, String> actual = new LinkedHashMap<>();
        authored.forEach((name, rule) -> {
            expected.put(name, RENDER.render(rule.condition()));
            actual.put(name, adapter.read("/Subscription/" + name) instanceof ReadResult.Converted converted
                    ? RENDER.render(converted.condition()) : "not converted");
        });
        assertEquals(expected, actual);
    }

    private Rule rule(String name, Condition condition) {
        return model.rule(model.groupAt("/Subscription").orElseThrow().ref())
                .name(name)
                .errorField(model.field("/Subscription/RequestedPlan"))
                .when(condition)
                .code(name)
                .severity(Severity.ERROR)
                .message(en("The requested plan disagrees."))
                .build();
    }
}

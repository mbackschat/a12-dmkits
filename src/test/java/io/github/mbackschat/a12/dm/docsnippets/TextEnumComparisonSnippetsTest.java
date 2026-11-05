package io.github.mbackschat.a12.dm.docsnippets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.EnumRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.StringRef;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;

/**
 * A text field compared with an enumeration, in either operand order — A12 admits it whether or not the
 * enumeration carries display labels since kernel 31.0 (A12K-3969; KERNEL-FINDINGS KF655, its admission rows in
 * {@code validate.laws.StringEnumComparisonAgreementLawsTest}). The tagged region is mirrored into
 * {@code docs/API-GUIDE.md}; every rendered condition is put to the kernel. Own domain (a subscription request).
 */
class TextEnumComparisonSnippetsTest {

    private static final String JSON = DocumentModelDraft.named("subscription").locale("en_US")
            .rootGroup("Subscription", s -> s
                    .stringField("RequestedPlan", f -> { })
                    .enumField("Plan", e -> e
                            .value("BASIC", l -> l.label("en_US", "Basic plan"))
                            .value("PRO", l -> l.label("en_US", "Pro plan")))
                    .enumField("Channel", e -> e.value("WEB").value("STORE")))
            .build().toJson();

    private static final BoundModel m = BoundModel.ofJson(JSON);

    @Test
    void textEnumComparisonSnippet() {
        // tag::text-enum-comparison[]
        // A text field and an enumeration compare by the enumeration's stored value, never its label, in
        // either operand order, whether or not the enumeration carries display labels.
        StringRef requested = m.str("/Subscription/RequestedPlan");
        EnumRef plan = m.enumOf("/Subscription/Plan");
        Condition mismatch = requested.filled().and(plan.ne(requested));
        Condition match = requested.eq(plan);
        // end::text-enum-comparison[]
        EnumRef channel = m.enumOf("/Subscription/Channel");
        Map<Condition, String> rendered = Map.of(
                mismatch, "FieldFilled(/Subscription/RequestedPlan) And [/Subscription/Plan] != [/Subscription/RequestedPlan]",
                match, "[/Subscription/RequestedPlan] == [/Subscription/Plan]",
                channel.eq(requested), "[/Subscription/Channel] == [/Subscription/RequestedPlan]",
                requested.ne(channel), "[/Subscription/RequestedPlan] != [/Subscription/Channel]");
        rendered.forEach((condition, text) -> {
            assertEquals(text, new DslRenderer().render(condition));
            var result = new RuleValidator().check(JSON, new DraftRule(
                    "/Subscription", "GuideSnippet", "../RequestedPlan", text, "GUIDE", "ERROR"));
            assertTrue(result.ok(), () -> "kernel rejected '" + text + "': " + result.diagnostics());
        });
    }
}

package io.github.mbackschat.a12.dm.adapter;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.de;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/**
 * The kernel-authoritative condition formatter (FEATURE-GAPS). {@link KernelAdapter#canonicalConditionText(String)}
 * already round-trips a condition through the kernel's own formatter; its {@code (…, Locale)} peer re-emits
 * with the other keyword language (EN↔DE) via the kernel's {@code formatAndTranslateConditionText}. The
 * canonical form is the engine's, not ours — the oracle the {@code rule format}/{@code computation format}
 * verbs surface. ({@code And} ↔ {@code Und} is the observable keyword translation; field paths stay put.)
 */
class KernelAdapterFormatTest {

    private static final String RULE_PATH = "/Subscription/Addons/FeeCapRule";

    /** A model whose rule uses a compound (And) condition — a keyword that differs EN↔DE, so the translation shows. */
    private static String modelWithCompoundRule() {
        BoundModel m = BoundModel.ofJson(TestResources.text("models/subscription.dm.json"));
        NumberRef fee = m.num("/Subscription/Addons/MonthlyFee");
        Rule rule = m.rule(m.group("/Subscription/Addons"))
                .name("FeeCapRule")
                .errorField(fee)
                .when(fee.gt(1000).and(fee.lt(5000)))
                .code("FEE_CAP").severity(Severity.ERROR)
                .message(en("Fee out of band."), de("Gebühr außerhalb des Bandes."))
                .build();
        return m.add(rule).toJson();
    }

    @Test
    void translatesTheCanonicalConditionBetweenEnglishAndGerman() {
        KernelAdapter adapter = KernelAdapter.ofJson(modelWithCompoundRule());

        String english = adapter.canonicalConditionText(RULE_PATH, ConditionLanguage.EN);
        String german = adapter.canonicalConditionText(RULE_PATH, ConditionLanguage.DE);

        assertNotNull(english);
        assertNotNull(german);
        assertTrue(english.contains("And") && !english.contains("Und"),
                () -> "the EN canonical keeps the English keyword, got: " + english);
        assertTrue(german.contains("Und") && !german.contains("And"),
                () -> "the DE canonical uses the German keyword, got: " + german);
        assertNotEquals(english, german, "translation produces different text for a keyword-bearing condition");
    }

    @Test
    void formatOnlyKeepsTheModelsOwnLanguage() {
        KernelAdapter adapter = KernelAdapter.ofJson(modelWithCompoundRule());
        String canonical = adapter.canonicalConditionText(RULE_PATH);   // no locale → the model's own (EN) language
        assertTrue(canonical.contains("And"), () -> "format-only keeps the model's EN keywords, got: " + canonical);
    }
}

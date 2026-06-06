package io.github.mbackschat.a12.dm.adapter;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * F10 tail close-out — the last two comparison shapes: <b>category-vs-category</b>
 * ({@code [a -> Cat] != [b -> Cat]}) and <b>constant-on-the-left</b> ({@code "X" != [field]}). The
 * former adds a {@code CategoryRef}-to-{@code CategoryRef} comparison; the latter is normalized to the
 * field-first form rulekit's handles always produce (commutative, meaning-preserving). With these the
 * kernel-mm addressable tail is fully converted (only host-defined CustomConditions stay opaque).
 */
class ComparisonTailIngestTest {

    private static final DslRenderer RENDER = new DslRenderer(ConditionLanguage.EN);

    /** Two enum fields each declaring the same category — for category-vs-category comparison. */
    private static BoundModel compare() {
        return BoundModel.ofJson(DocumentModelDraft.named("compare").locale("en_US")
                .rootGroup("Cmp", c -> c
                        .enumField("Left", e -> e.value("X").value("Y").category("Tier", "LOW", "HIGH"))
                        .enumField("Right", e -> e.value("X").value("Y").category("Tier", "LOW", "HIGH")))
                .build().toJson());
    }

    @Test
    void roundTripsCategoryVsCategory() {
        BoundModel m = compare();
        Condition c = m.enumOf("/Cmp/Left").category("Tier").isNot(m.enumOf("/Cmp/Right").category("Tier"));

        Rule r = m.rule(m.group("/Cmp")).name("CatPairRule").errorField(m.field("/Cmp/Left"))
                .when(c).code("C").severity(Severity.ERROR).message(en("x")).build();
        KernelAdapter adapter = KernelAdapter.of(m.add(r));

        assertTrue(RENDER.render(c).contains("[/Cmp/Left -> Tier] != [/Cmp/Right -> Tier]"), RENDER.render(c));
        assertEquals(RENDER.render(c), RENDER.render(converted(adapter.ingest("/Cmp/CatPairRule"))));
    }

    @Test
    void categoryVsCategoryIsKernelValid() {
        BoundModel m = compare();
        Condition c = m.enumOf("/Cmp/Left").category("Tier").isNot(m.enumOf("/Cmp/Right").category("Tier"));
        ValidationResult r = new RuleValidator().check(m.toJson(),
                new DraftRule("/Cmp", "CatPairRule", "../Left", RENDER.render(c), "C", "ERROR"));
        assertTrue(r.ok(), () -> "expected kernel acceptance, got: " + r.diagnostics());
    }

    @Test
    void constantOnTheLeftIsKernelValidAndNormalizedOnIngest() {
        // rulekit handles are field-first, so a constant-left comparison can't be DSL-authored; the kernel
        // accepts it, and the converter normalizes "X" != [field] → [field] != "X" (proven end-to-end by
        // the kernel-mm coverage harness, where the SMM_FieldTypeChecks rules use this form).
        ValidationResult r = new RuleValidator().check(read("models/order.dm.json"),
                new DraftRule("/Order", "ConstLeftRule", "../ProductCode",
                        "\"BANNED\" != [/Order/ProductCode]", "C", "ERROR"));
        assertTrue(r.ok(), () -> "expected kernel acceptance of constant-on-left, got: " + r.diagnostics());
    }

    private static Condition converted(Ingest result) {
        return assertInstanceOf(Ingest.Converted.class, result,
                () -> "expected a converted ingest, got: " + result).condition();
    }

    private static String read(String path) {
        try (InputStream in = ComparisonTailIngestTest.class.getClassLoader().getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

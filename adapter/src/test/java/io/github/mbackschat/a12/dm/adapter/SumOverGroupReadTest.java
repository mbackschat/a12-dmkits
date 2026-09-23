package io.github.mbackschat.a12.dm.adapter;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * F10 read tail — an aggregate over a <em>group</em> wildcard, {@code Sum(Tally*)}, where the entity is a
 * group (not a field). The group operand is preserved as a group.
 *
 * <p><b>This file used to assert the opposite</b>, and the reversal is the CG15 finding rather than a change of
 * taste: the converter rebuilt the group as the explicit list of its descendant NUMBER fields, and that rewrite
 * cannot represent a nested repeatable subgroup (it emits the inner group unstarred, which the kernel refuses
 * {@code MVK_NO_WILDCARD}), cannot represent a legal unstarred non-repeatable group, and erases every
 * non-number kind. The expansion belongs to the kernel's {@code ExpandService}; {@code GroupListRef} is the
 * operand that says so. The four symptoms are locked in {@code GroupScopeAggregateRoundTripTest}.
 */
class SumOverGroupReadTest {

    private static final DslRenderer RENDER = new DslRenderer(ConditionLanguage.EN);

    private static String tallyJson() {
        return DocumentModelDraft.named("tally").locale("en_US")
                .rootGroup("Sheet", s -> s
                        .numberField("Total", n -> { })
                        .group("Tally", g -> g.repeatable(50).numberField("Amount", n -> { })))
                .build().toJson();
    }

    @Test
    void readsAGroupScopedSumAsTheGroupTheAuthorWrote() {
        String rule = "AtLeastOneFieldFilled(Tally*/Amount) And [Total] != Sum(Tally*)";
        KernelAdapter adapter = KernelAdapter.ofJson(inject(tallyJson(), rule));
        ReadResult result = adapter.read("/Sheet/SumRule");
        var converted = assertInstanceOf(ReadResult.Converted.class, result,
                () -> "Sum(Tally*) should convert, got: " + result);
        assertTrue(RENDER.render(converted.condition()).contains("Sum(/Sheet/Tally*)"),
                RENDER.render(converted.condition()));
    }

    @Test
    void theGroupScopedAndExplicitFormsAreBothKernelValid() {
        String model = tallyJson();
        for (String dsl : new String[] {
                "AtLeastOneFieldFilled(Tally*/Amount) And [Total] != Sum(Tally*)",
                "AtLeastOneFieldFilled(Tally*/Amount) And [Total] != Sum(Tally*/Amount)" }) {
            ValidationResult r = new RuleValidator().check(model,
                    new DraftRule("/Sheet", "R", "../Total", dsl, "C", "ERROR"));
            assertTrue(r.ok(), () -> "expected kernel acceptance for: " + dsl + " — got: " + r.diagnostics());
        }
    }

    /**
     * A group with SEVERAL number fields is still one operand. The explicit expansion this used to assert — a
     * Sum over the flatten of {@code Multi*} then {@code A} and then {@code B} — remains kernel-valid, asserted
     * below, so the two forms are equivalent <i>on this fixture</i>; the reason to keep the group is the
     * fixtures where they are not.
     */
    @Test
    void aMultiNumberFieldGroupScopeIsStillOneGroupOperand() {
        String model = DocumentModelDraft.named("multi").locale("en_US")
                .rootGroup("Sheet", s -> s
                        .numberField("Total", n -> { })
                        .group("Multi", g -> g.repeatable(50)
                                .numberField("A", n -> { }).numberField("B", n -> { })))
                .build().toJson();
        var converted = assertInstanceOf(ReadResult.Converted.class,
                KernelAdapter.ofJson(inject(model, "AtLeastOneFieldFilled(Multi*/A) And [Total] != Sum(Multi*)"))
                        .read("/Sheet/SumRule"),
                "Sum over a multi-number-field group should convert");
        String rendered = RENDER.render(converted.condition());
        assertTrue(rendered.contains("Sum(/Sheet/Multi*)"), rendered);
        assertTrue(new RuleValidator().check(model, new DraftRule("/Sheet", "R2", "../Total",
                "AtLeastOneFieldFilled(Multi*/A) And [Total] != Sum(Multi*/A, Multi*/B)", "C", "ERROR")).ok(),
                "the explicit expansion stays kernel-valid — it is equivalent here, not wrong");
    }

    /** Inject a raw-text rule into the /Sheet group (the group-scoped form can't be built via the typed DSL). */
    private static String inject(String modelJson, String rawCondition) {
        return new RuleValidator().inject(modelJson,
                new DraftRule("/Sheet", "SumRule", "../Total", rawCondition, "C", "ERROR"));
    }
}

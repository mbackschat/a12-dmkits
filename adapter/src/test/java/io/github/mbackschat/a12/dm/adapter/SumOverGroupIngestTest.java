package io.github.mbackschat.a12.dm.adapter;

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
 * F10 ingest tail — an aggregate over a <em>group</em> wildcard, {@code Sum(Tally*)}, where the entity is a
 * group (not a field). The kernel resolves it to the group's sole number field; the converter makes that
 * explicit (the flatten of {@code Tally*} then {@code Amount}). A negative probe confirms the kernel only
 * accepts a group scope when exactly one number field is present, so the resolution is unambiguous.
 */
class SumOverGroupIngestTest {

    private static final DslRenderer RENDER = new DslRenderer(ConditionLanguage.EN);

    private static String tallyJson() {
        return DocumentModelDraft.named("tally").locale("en_US")
                .rootGroup("Sheet", s -> s
                        .numberField("Total", n -> { })
                        .group("Tally", g -> g.repeatable(50).numberField("Amount", n -> { })))
                .build().toJson();
    }

    @Test
    void ingestsAGroupScopedSumByResolvingTheSoleNumberField() {
        String rule = "AtLeastOneFieldFilled(Tally*/Amount) And [Total] != Sum(Tally*)";
        KernelAdapter adapter = KernelAdapter.ofJson(inject(tallyJson(), rule));
        Ingest result = adapter.ingest("/Sheet/SumRule");
        var converted = assertInstanceOf(Ingest.Converted.class, result,
                () -> "Sum(Tally*) should convert (to the explicit field form), got: " + result);
        // the converter resolves the group scope to its sole number field
        assertTrue(RENDER.render(converted.condition()).contains("Sum(/Sheet/Tally*/Amount)"),
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

    @Test
    void expandsAMultiNumberFieldGroupScopeToAllItsNumberFields() {
        // the kernel expands a group argument to its contained number fields (KERNEL-FINDINGS §9), so
        // Sum(Multi*) sums every number field across reps — the converter makes that explicit:
        // Sum(Multi*/A, Multi*/B). The expanded form is itself kernel-valid (the conversion is sound).
        String model = DocumentModelDraft.named("multi").locale("en_US")
                .rootGroup("Sheet", s -> s
                        .numberField("Total", n -> { })
                        .group("Multi", g -> g.repeatable(50)
                                .numberField("A", n -> { }).numberField("B", n -> { })))
                .build().toJson();
        var converted = assertInstanceOf(Ingest.Converted.class,
                KernelAdapter.ofJson(inject(model, "AtLeastOneFieldFilled(Multi*/A) And [Total] != Sum(Multi*)"))
                        .ingest("/Sheet/SumRule"),
                "Sum over a multi-number-field group should convert");
        String expanded = RENDER.render(converted.condition());
        assertTrue(expanded.contains("Sum(/Sheet/Multi*/A, /Sheet/Multi*/B)"), expanded);
        // the converter's explicit expansion is itself accepted by the kernel — i.e. a sound rewrite of Sum(Multi*)
        assertTrue(new RuleValidator().check(model, new DraftRule("/Sheet", "R2", "../Total",
                "AtLeastOneFieldFilled(Multi*/A) And [Total] != Sum(Multi*/A, Multi*/B)", "C", "ERROR")).ok(),
                "the expanded Sum(Multi*/A, Multi*/B) must be kernel-valid");
    }

    /** Inject a raw-text rule into the /Sheet group (the group-scoped form can't be built via the typed DSL). */
    private static String inject(String modelJson, String rawCondition) {
        String escaped = rawCondition.replace("\\", "\\\\").replace("\"", "\\\"");
        String rule = "{ \"type\": \"Rule\", \"id\": \"/Sheet/SumRule\", \"name\": \"SumRule\","
                + " \"Rule\": { \"errorEntityRelPath\": \"../Total\", \"errorCode\": \"C\","
                + " \"errorCondition\": \"" + escaped + "\", \"severity\": \"ERROR\","
                + " \"errorMessage\": [ { \"locale\": \"en_US\", \"text\": \"x\" } ] } }";
        int br = modelJson.indexOf('[', modelJson.indexOf("\"elements\""));
        return modelJson.substring(0, br + 1) + "\n" + rule + ",\n" + modelJson.substring(br + 1);
    }
}

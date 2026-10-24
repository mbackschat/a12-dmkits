package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.KernelCodeRefusal;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;

/**
 * A typed rule can carry A12's scale-gate waiver, the condition's leading {@code @SuppressWarning} directive the
 * editor calls "Allow Differing Decimal Places" (KERNEL-FINDINGS KF75; DEV-API-GAPS DG140).
 *
 * <p>The kernel decides every row. The unwaived twin is the control that the directive is what admits the rule,
 * and the German row that it is spelled in the model's condition language, which refuses the other language's
 * keyword ({@code validate.laws.ScaleWaiverLanguageLawsTest}).
 */
class ScaleWaiverRuleTest {

    private static String model(String conditionLanguage) {
        String json = DocumentModelDraft.named("policy-scale").locale("en_US")
                .rootGroup("Policy", p -> p
                        .numberField("Units", n -> n.fractionDigits(0))
                        .numberField("Rate", n -> n.fractionDigits(2)))
                .build().toJson();
        return ModelDocument.read(json).setConditionLanguage(conditionLanguage).toJson();
    }

    private static final BoundModel M = BoundModel.ofJson(model("en_US"));
    private static final NumberRef UNITS = M.num("/Policy/Units");
    private static final NumberRef RATE = M.num("/Policy/Rate");

    private static Rule rule(boolean waived) {
        var builder = M.rule(M.group("/Policy")).name("UnitsAtRate").errorField(UNITS.field())
                .when(RATE.filled().and(UNITS.eq(RATE.asNumber().allowingDifferingDecimals())))
                .code("UNITS_AT_RATE").message(Dsl.en("Units equal the rate."));
        return (waived ? builder.allowDifferingDecimals() : builder).build();
    }

    private static List<String> kernelCodes(String modelJson, Rule rule, ConditionLanguage language) {
        return new RuleValidator().check(modelJson, rule.draft(language)).diagnostics().stream()
                .map(d -> d.code().orElse("(no code)")).toList();
    }

    @Test
    void anUnwaivedCrossScaleEqualityIsStillRefusedWhenItIsBuilt() {
        KernelCodeRefusal refusal = assertThrows(KernelCodeRefusal.class, () -> UNITS.eq(RATE));
        assertEquals("MVK_INVALID_COMPARE_DEC_PLACES", refusal.code().name());
    }

    @Test
    void aWaivedRuleRendersTheDirectiveAndA12AdmitsIt() {
        // tag::rule-scale-waiver[]
        Rule waived = M.rule(M.group("/Policy")).name("UnitsAtRate").errorField(UNITS.field())
                .when(RATE.filled().and(UNITS.eq(RATE.asNumber().allowingDifferingDecimals())))  // scales 0 and 2
                .allowDifferingDecimals()                     // A12's waiver, on the rule's condition
                .code("UNITS_AT_RATE").message(Dsl.en("Units equal the rate."))
                .build();
        waived.render();   // "@SuppressWarning(MVK_INVALID_COMPARE_DEC_PLACES) FieldFilled(Rate) And [Units] == [Rate]"
        // end::rule-scale-waiver[]
        assertEquals("@SuppressWarning(MVK_INVALID_COMPARE_DEC_PLACES) FieldFilled(Rate) And [Units] == [Rate]",
                waived.render());
        assertEquals(List.of(), kernelCodes(M.toJson(), waived, ConditionLanguage.EN));
        assertTrue(waived.allowsDifferingDecimals());
    }

    @Test
    void theSameRuleWithoutTheWaiverIsRefusedByA12() {
        Rule unwaived = rule(false);
        assertEquals("FieldFilled(Rate) And [Units] == [Rate]", unwaived.render());
        assertEquals(List.of("MVK_INVALID_COMPARE_DEC_PLACES"), kernelCodes(M.toJson(), unwaived, ConditionLanguage.EN));
    }

    @Test
    void aGermanModelReadsTheWaiverInItsOwnKeyword() {
        Rule waived = rule(true);
        assertTrue(waived.render(ConditionLanguage.DE).startsWith("@UnterdrueckeWarnung(MVK_INVALID_COMPARE_DEC_PLACES) "),
                waived.render(ConditionLanguage.DE));
        assertEquals(List.of(), kernelCodes(model("de_DE"), waived, ConditionLanguage.DE));
    }
}

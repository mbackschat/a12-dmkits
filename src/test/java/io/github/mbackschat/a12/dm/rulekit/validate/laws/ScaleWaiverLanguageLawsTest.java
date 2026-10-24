package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionDirectives;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Kernel laws — the scale-gate waiver's keyword is read in the MODEL's condition language (KERNEL-FINDINGS KF75).
 *
 * <p>Each language admits its own spelling and refuses the other's as {@code MVK_UNEXPECTED_TOKEN}, while the
 * unwaived comparison draws {@code MVK_INVALID_COMPARE_DEC_PLACES} in both, which is the control that the waiver
 * and nothing else lifts the gate. {@link ConditionDirectives} is the one place that spells it, so this class
 * locks the helper the CLI's {@code allowDifferingDecimals} writes through.
 *
 * <p>The comparison is condition text, spelled the same in both languages, because this class locks the text
 * helper; a typed rule carries the same waiver through {@code Rule.Builder.allowDifferingDecimals}
 * ({@code dsl.ScaleWaiverRuleTest}).
 */
class ScaleWaiverLanguageLawsTest {

    private static final String ORDER = Fixtures.orderJson();
    private static final String CROSS_SCALE = "[Quantity] == [UnitPrice]";

    @ParameterizedTest
    @EnumSource(ConditionLanguage.class)
    void eachLanguageAdmitsItsOwnKeywordAndRefusesTheOther(ConditionLanguage language) {
        String model = language == ConditionLanguage.DE
                ? ModelDocument.read(ORDER).setConditionLanguage("de_DE").toJson()
                : ORDER;
        ConditionLanguage other = language == ConditionLanguage.EN ? ConditionLanguage.DE : ConditionLanguage.EN;

        KernelLaws.rejectsModel(model, "/Order", "../Quantity", CROSS_SCALE, "MVK_INVALID_COMPARE_DEC_PLACES");
        KernelLaws.acceptsModel(model, "/Order", "../Quantity",
                ConditionDirectives.allowDifferingDecimals(language, CROSS_SCALE));
        KernelLaws.rejectsModel(model, "/Order", "../Quantity",
                ConditionDirectives.allowDifferingDecimals(other, CROSS_SCALE), "MVK_UNEXPECTED_TOKEN");
    }
}

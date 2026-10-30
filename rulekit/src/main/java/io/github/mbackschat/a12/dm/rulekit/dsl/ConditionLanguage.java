package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.predicates.LocalePredicatesKt;

/** The condition language to render into. EN is the default; the same AST renders to either (§5a). */
public enum ConditionLanguage {
    EN,
    DE;

    /**
     * The language a model's {@code content.modelConfig.conditionLanguage.code} selects, read as A12 reads it, by the
     * locale key its parser makes of the code: {@code de_DE} and {@code de-DE} are German, {@code en_US},
     * {@code en-US} and an absent code English ({@code adapter.laws.UnsupportedConditionLanguageStageDiffTest}). A12
     * refuses any other code outright ({@code validate.laws.ModelConfigCoercionLawsTest}), so reading it as English
     * here decides no model A12 evaluates.
     */
    public static ConditionLanguage ofModelCode(String code) {
        return code != null && "de_DE".equals(LocalePredicatesKt.a12LocaleKey(code)) ? DE : EN;
    }
}

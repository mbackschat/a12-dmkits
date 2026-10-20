package io.github.mbackschat.a12.dm.rulekit.dsl;

/** The condition language to render into. EN is the default; the same AST renders to either (§5a). */
public enum ConditionLanguage {
    EN,
    DE;

    /**
     * The language a model's {@code content.modelConfig.conditionLanguage.code} selects: {@code de_DE} is German,
     * {@code en_US} and an absent code English. A12 refuses any other code outright
     * ({@code validate.laws.ModelConfigCoercionLawsTest}), so reading it as English here decides no model A12 loads.
     */
    public static ConditionLanguage ofModelCode(String code) {
        return "de_DE".equals(code) ? DE : EN;
    }
}

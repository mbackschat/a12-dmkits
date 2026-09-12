package io.github.mbackschat.a12.dm.consistency.syntax

/**
 * The language a model's condition text is written in.
 *
 * A12 supports exactly two (`AbstractMultiLanguageHandler.SUPPORTED_CONDITION_LANGUAGES` is
 * `{de_DE, en_US}`) and a model declares its own in `content.modelConfig.conditionLanguage.code`. It is not
 * a presentation choice: the lexer resolves keywords against this language's terminals alone, so the same
 * condition text means different things under the two.
 */
enum class ConditionLanguage(val code: String) {
    EN("en_US"),
    DE("de_DE");

    companion object {
        /**
         * The language [code] names, defaulting to [EN] for an absent or unrecognized code — the same
         * fallback rulekit's `ModelWriter.conditionLanguage` applies when reading a model.
         */
        fun ofCode(code: String?): ConditionLanguage = entries.firstOrNull { it.code == code } ?: EN
    }
}

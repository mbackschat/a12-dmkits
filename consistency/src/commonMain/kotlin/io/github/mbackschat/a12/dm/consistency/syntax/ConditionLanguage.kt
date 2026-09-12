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
         * The language [code] names, or `null` when it names none.
         *
         * There is no fallback for an unrecognized code, because A12 has none: a model declaring `fr_FR` is
         * REFUSED outright ("Die im ValidierungsModus übergebene Sprache fr_FR wird nicht unterstützt",
         * uncoded, measured by `ModelConfigCoercionLawsTest`). Reading
         * `AbstractMultiLanguageHandler`'s constructor predicts a silent substitution of German instead;
         * that substitution exists but a gate ahead of it means no model ever reaches it.
         */
        fun of(code: String): ConditionLanguage? = entries.firstOrNull { it.code == code }

        /** The language for an ABSENT declaration: English, the default `DocumentModelConfig` carries. */
        val DEFAULT: ConditionLanguage = EN
    }
}

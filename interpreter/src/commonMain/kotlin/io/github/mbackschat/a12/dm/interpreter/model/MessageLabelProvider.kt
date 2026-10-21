package io.github.mbackschat.a12.dm.interpreter.model

import io.github.mbackschat.a12.dm.interpreter.isJavaBlank

/**
 * Consumer override for a validation message's `$field$` display name. A non-null result wins verbatim,
 * including the empty string; null asks the interpreter to continue with the model label and debug identity.
 */
fun interface MessageLabelProvider {
    fun label(field: FieldDef, locale: String): String?

    companion object {
        /** The dmtool-compatible policy used by the public constructor default: the localized label unless Java's
         *  `String.isBlank` reads it as blank, as the dmtool provider tests it, else the name. */
        val MODEL_LABEL_OR_NAME = MessageLabelProvider { field, locale ->
            field.labels[locale]?.takeUnless { it.isJavaBlank() }
                ?: field.path.trimEnd('/').substringAfterLast('/')
        }
    }
}

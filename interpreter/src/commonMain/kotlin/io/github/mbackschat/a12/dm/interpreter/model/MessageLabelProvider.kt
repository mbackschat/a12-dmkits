package io.github.mbackschat.a12.dm.interpreter.model

/**
 * Consumer override for a validation message's `$field$` display name. A non-null result wins verbatim,
 * including the empty string; null asks the interpreter to continue with the model label and debug identity.
 */
fun interface MessageLabelProvider {
    fun label(field: FieldDef, locale: String): String?

    companion object {
        /** The dmtool-compatible policy used by the public constructor default: localized nonblank label, else name. */
        val MODEL_LABEL_OR_NAME = MessageLabelProvider { field, locale ->
            field.labels[locale]?.takeIf { it.isNotBlank() }
                ?: field.path.trimEnd('/').substringAfterLast('/')
        }
    }
}

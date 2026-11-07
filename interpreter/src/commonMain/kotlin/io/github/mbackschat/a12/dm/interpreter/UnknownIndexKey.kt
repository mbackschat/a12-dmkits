package io.github.mbackschat.a12.dm.interpreter

/**
 * The key a semantic index found no row for, as A12 keeps it on a possibly-causing entity's pointer
 * (`PartiallyKnownDocumentMultiPointer.semanticIndexValueForUnknownIndexGroup`, KERNEL-FINDINGS KF683): the key
 * converted to the index field's basic type, whose own equality decides whether two members are one.
 */
internal sealed interface UnknownIndexKey {

    /** A text or enumeration index's key, compared as text. */
    data class Text(val value: String) : UnknownIndexKey

    /**
     * A number index's key in its plain form with its scale kept, so `7` and `7.0` differ as two `BigDecimal`s do
     * under `equals` (`adapter.laws.UnknownIndexKeyLawsTest`).
     */
    data class Decimal(val plain: String) : UnknownIndexKey
}

/** The value a [UnknownIndexKey.Decimal] is published as: A12's `BigDecimal` on the JVM, A12's number in JavaScript. */
internal expect fun publishedIndexValue(key: UnknownIndexKey.Decimal): Any

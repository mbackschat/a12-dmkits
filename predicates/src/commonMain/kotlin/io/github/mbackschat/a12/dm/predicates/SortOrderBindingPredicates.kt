package io.github.mbackschat.a12.dm.predicates

/**
 * How A12's model reader binds a `sortFields[].order` value to its `Order` enum (`ASC`, `DESC`): Jackson's standard
 * enum binding, which reads a constant's name or its ordinal ([KERNEL-FINDINGS KF680]).
 *
 * **[UNREADABLE] is not a third order**: the reader refuses the value and the model never loads. Kernel 31.3.1
 * builds the entry through a builder whose `order` is non-null, so an absent member or a JSON `null` is
 * [UNREADABLE] as well, which is the caller's to map; 31.1.1 read both. A boolean, an array or an object is
 * [UNREADABLE].
 *
 * Measured on this one member (`rulekit.validate.laws.GroupIndexAndSortReferenceLawsTest` for read or refuse,
 * `adapter.SortOrderBindingTest` for which constant an admitted spelling names). The checker and the Java kits read
 * through it.
 */
enum class A12SortOrderBinding { ASC, DESC, UNREADABLE }

/**
 * A JSON STRING's decoded content, trimmed as Java's `String.trim` trims (U+0000–U+0020 only), so a tab goes and an
 * em space stays. It then names a constant exactly, case-sensitively, or is the canonical text of an ordinal: `"1"`
 * binds, `"01"` refuses the reader.
 */
fun a12SortOrderOfString(value: String): A12SortOrderBinding =
    when (value.trim { it <= ' ' }) {
        "ASC", "0" -> A12SortOrderBinding.ASC
        "DESC", "1" -> A12SortOrderBinding.DESC
        else -> A12SortOrderBinding.UNREADABLE
    }

/**
 * A JSON NUMBER token's literal text, read as an ordinal of the two constants: an integer whose value is 0 or 1,
 * `-0` included. A fractional or exponent literal (`1.0`, `1e0`) and any other integer refuse the reader.
 */
fun a12SortOrderOfNumber(literal: String): A12SortOrderBinding =
    when (literal) {
        "0", "-0" -> A12SortOrderBinding.ASC
        "1" -> A12SortOrderBinding.DESC
        else -> A12SortOrderBinding.UNREADABLE
    }

package io.github.mbackschat.a12.dm.consistency

/*
 * Readings for text A12's own grammar has already confined to ASCII, spelled out so every target answers alike.
 *
 * A condition's digits and keywords reach A12 through its lexer's ASCII tokens (KERNEL-FINDINGS KF575), and an element
 * name through an ASCII alphabet. Kotlin's `toIntOrNull` and
 * `equals(ignoreCase = true)` read each platform's own tables instead — the JVM folds the long s onto `s` and the
 * dotless i onto `i` — so on that text they answer for characters A12 never lets through, and differently per target.
 */

/** An optional sign then ASCII digits, within `Int`'s range; null otherwise. */
internal fun String.asciiIntOrNull(): Int? =
    takeIf { text -> text.drop(if (text.startsWith('-') || text.startsWith('+')) 1 else 0).all { it in '0'..'9' } }
        ?.javaLongOrNull()?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

/** Equal when only the case of ASCII letters differs. */
internal fun String.equalsIgnoringAsciiCase(other: String): Boolean =
    length == other.length && indices.all { this[it].asciiLower() == other[it].asciiLower() }

/** Starts with [prefix] when only the case of ASCII letters differs. */
internal fun String.startsWithIgnoringAsciiCase(prefix: String): Boolean =
    length >= prefix.length && prefix.indices.all { this[it].asciiLower() == prefix[it].asciiLower() }

/** Only ASCII letters change case, so every target answers alike where Kotlin's `lowercase` reads its own tables. */
internal fun String.asciiLowercase(): String = buildString(length) { this@asciiLowercase.forEach { append(it.asciiLower()) } }

/** [asciiLowercase]'s other direction. */
internal fun String.asciiUppercase(): String =
    buildString(length) { this@asciiUppercase.forEach { append(if (it in 'a'..'z') it - ('a' - 'A') else it) } }

private fun Char.asciiLower(): Char = if (this in 'A'..'Z') this + ('a' - 'A') else this

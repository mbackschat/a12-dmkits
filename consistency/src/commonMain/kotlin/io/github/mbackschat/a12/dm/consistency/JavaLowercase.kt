package io.github.mbackschat.a12.dm.consistency

/**
 * This text lower-cased as Java's `String.toLowerCase(Locale.ROOT)` does, which is how A12 lower-cases the names it
 * derives (`DocumentModelServiceUtils.createGeneratedCodeId`, KERNEL-FINDINGS KF650).
 *
 * A full Unicode case mapping, where [asciiLowercase] is a fold for text A12's grammar confines to ASCII: `İ` (U+0130)
 * becomes two units, which moves the index A12 cuts a name at, and a final `Σ` becomes `ς`. It is a platform call on
 * each target rather than shared code, because Kotlin's common `lowercase` reads a different table per target
 * (`PlatformCharacterTableJvmTest`). The JVM target is Java's own mapping; JavaScript's `toLowerCase` applies the same
 * Unicode default mapping, so the two can differ only where their Unicode versions do. `JavaLowercaseTest` runs the
 * measured cases on both targets.
 */
internal expect fun String.javaLowercase(): String

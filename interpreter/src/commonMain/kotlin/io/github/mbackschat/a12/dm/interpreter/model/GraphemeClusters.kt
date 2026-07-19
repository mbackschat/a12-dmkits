package io.github.mbackschat.a12.dm.interpreter.model

/** Unicode extended grapheme clusters used only while validating a charset definition. */
internal expect fun graphemeClustersOf(value: String): List<String>

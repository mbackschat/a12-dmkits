package io.github.mbackschat.a12.dm.interpreter.model

@Suppress("UnsafeCastFromDynamic")
internal actual fun graphemeClustersOf(value: String): List<String> {
    val segmenter: dynamic = js("new Intl.Segmenter(undefined, { granularity: 'grapheme' })")
    val arrayFrom: (dynamic) -> Array<dynamic> = js("Array.from")
    return arrayFrom(segmenter.segment(value)).map { it.segment as String }
}

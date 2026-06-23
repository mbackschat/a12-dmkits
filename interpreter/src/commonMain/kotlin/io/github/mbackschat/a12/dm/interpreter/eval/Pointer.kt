package io.github.mbackschat.a12.dm.interpreter.eval

/**
 * Render a field-instance pointer the kernel team's readable way (the demo's `pretty()` over
 * `PartiallyKnownDocumentMultiPointer`): the path with the repetition **index on each GROUP segment**
 * (not the trailing field), e.g. `/Order[1]/Items[2]/Count`. [reps] is one 1-based index per path part
 * (the kernel's `repetitionIndexes()`). This is the kernel's *content* (path + indices) in a clean form
 * — never the internal `…MultiPointerImpl[…]` toString.
 */
fun formatPointer(path: String, reps: List<Int>): String {
    val segs = path.trim('/').split('/')
    val sb = StringBuilder()
    for (i in segs.indices) {
        sb.append('/').append(segs[i])
        if (i < segs.size - 1 && i < reps.size) {
            sb.append('[').append(reps[i]).append(']')
        }
    }
    return sb.toString()
}

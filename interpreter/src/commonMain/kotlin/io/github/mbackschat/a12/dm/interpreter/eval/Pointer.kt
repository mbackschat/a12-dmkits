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

/**
 * The same content rendered **losslessly** — every coordinate written, the terminal one included. This is a
 * COMPARISON KEY and nothing else: not an A12 format, not a display string, never stored, never published.
 *
 * <b>Why it exists (INTERPRETER-GAPS `IG145`).</b> [formatPointer] omits the terminal coordinate. On a FIELD
 * pointer that coordinate is the constant 1 and nothing is lost; on a pointer whose last segment is a
 * repeatable **group** it is a real row index, so the kernel's three distinct `[1,3,1]`, `[1,3,2]`, `[1,3,3]`
 * findings at `/Billing/Invoices/Periods` all render as one string. Every `adapter/laws` differential compares
 * that rendering, so an engine attributing such a finding to the wrong row would compare EQUAL — the loss is
 * in the instrument, not in any output. Here the same three become `…/Periods[1]`, `[2]`, `[3]`.
 *
 * A field pointer therefore gains a trailing `[1]`, which is noise for a reader and exactly right for a key:
 * both sides of a differential render through this one function, so the suffix cancels and only real
 * disagreement survives. Message SENTINELS in the terminal slot (`0`, a negative) are written out for the same
 * reason — a key that hid them could not tell two message channels apart.
 */
fun pointerComparisonKey(path: String, reps: List<Int>): String {
    val segs = path.trim('/').split('/')
    val sb = StringBuilder()
    for (i in segs.indices) {
        sb.append('/').append(segs[i])
        if (i < reps.size) {
            sb.append('[').append(reps[i]).append(']')
        }
    }
    return sb.toString()
}

package io.github.mbackschat.a12.dm.interpreter

/**
 * Formal-error detection — whether a raw field value is *well-formed for its kind*. The kernel runs this
 * BEFORE rule evaluation; a rule that references a formally-invalid field is **not evaluated** (the
 * operand is "unknown"), so the rule neither fires nor passes — it is *suppressed*. Codes are the
 * kernel's German internal identifiers (KERNEL-FINDINGS §finding "runtime formal-error codes"), returned
 * verbatim so a consumer can surface the same `formalErrorCode` the kernel reports.
 *
 * Scope today: the format checks the build-out needs + can verify — DATE (`datumFormatFalsch`) and
 * NUMBER (`fuehrendesBlank` / `zahlHatUngueltigeZeichen`). The scale check (`zahlFalscheAnzahl-
 * Nachkommastellen`) needs per-field scale metadata and the German-keyed message bundles are a separate
 * tier — both build-out residue (INTERPRETER-BUILDOUT.md).
 */
fun formalError(kind: Kind, raw: String): String? = when (kind) {
    Kind.DATE -> if (ISO_DATE.matches(raw)) null else "datumFormatFalsch"
    Kind.NUMBER -> when {
        raw != raw.trim() -> "fuehrendesBlank"
        DECIMAL.matches(raw) -> null
        else -> "zahlHatUngueltigeZeichen"
    }
    Kind.STRING, Kind.BOOLEAN, Kind.CONFIRM -> null
}

private val ISO_DATE = Regex("""\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\d|3[01])""")
private val DECIMAL = Regex("""-?\d+(\.\d+)?""")

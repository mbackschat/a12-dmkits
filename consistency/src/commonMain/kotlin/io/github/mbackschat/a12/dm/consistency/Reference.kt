package io.github.mbackschat.a12.dm.consistency

/**
 * What a bare reference in a condition or operation resolved to.
 *
 * The three arms exist because A12 rejects two of them **differently**: an undeclared name is
 * `MVK_INVALID_ENTITY` and an ambiguous one is `MVK_FIELDNAME_NOT_UNIQUE`, and the corrective differs with
 * the code — declare the field, versus qualify the reference so it names one. The interpreter's existing
 * static helper reports both as the same unresolved path, which is the gap this classification closes
 * (PORTABLE-CONSISTENCY-PROPOSAL §3.4).
 */
sealed interface Reference {

    /** The reference names exactly one declared element, at [path]. */
    data class Resolved(val path: String) : Reference

    /** Nothing declares the name. [candidate] is the path the declaring group would have given it. */
    data class Undeclared(val candidate: String) : Reference

    /** Several fields carry the short [name], so the model-wide fallback cannot choose between them. */
    data class Ambiguous(val name: String, val candidates: List<String>) : Reference
}

/**
 * Resolve a bare [name] written inside [declaringGroup] — the Kernel's `getEntity`-first rule.
 *
 * The declaring group is tried first and wins outright, so a field the group actually has is never displaced
 * by a model-wide candidate. Only when that misses does the model-wide short-name fallback apply, and only
 * when the model turns it on: `fieldRefByShortNameAllowed` gates the second tier alone, which is why the
 * flag changes the verdict for a name the declaring group does not have and changes nothing for one it does.
 *
 * The fallback indexes **fields**, not every element, matching the Kernel's own `CheckIdNameRefImpl`
 * (KERNEL-FINDINGS §10; the tiers are locked by `ShortNameTierLawsTest`).
 */
fun PreparedModel.resolve(declaringGroup: String, name: String): Reference {
    val candidate = "$declaringGroup/$name"
    if (elements.any { it.path == candidate }) {
        return Reference.Resolved(candidate)
    }
    if (!fieldRefByShortNameAllowed) {
        return Reference.Undeclared(candidate)
    }
    val matches = elements.filter { it.kind == FIELD_KIND && it.path.substringAfterLast('/') == name }
    return when (matches.size) {
        0 -> Reference.Undeclared(candidate)
        1 -> Reference.Resolved(matches.single().path)
        else -> Reference.Ambiguous(name, matches.map { it.path })
    }
}

private const val FIELD_KIND = "Field"

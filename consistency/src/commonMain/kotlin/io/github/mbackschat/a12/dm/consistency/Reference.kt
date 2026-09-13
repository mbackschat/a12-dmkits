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

    /**
     * [written] climbs above the model root, so it names no position at all rather than a missing element.
     * A12 says so with its own code (`MVK_TOO_MANY_PARENT_GROUPS`), which is why this is not [Undeclared]:
     * the corrective is to remove a `..`, not to declare anything.
     */
    data class AboveRoot(val written: String) : Reference
}

/**
 * Where a reference was written, which decides what A12 reports when it does not resolve.
 *
 * The roles are not interchangeable and the difference is measured, not stylistic: the same unresolvable
 * path draws `MVK_ERROR_FIELD_MISSING` as a rule's error target, no code at all as a computation's target
 * (the model cannot be built), and `MVK_INVALID_ENTITY` inside a condition.
 */
enum class ReferenceRole {
    /** A rule's `errorEntityRelPath`. */
    ERROR_TARGET,

    /** A computation's `computedFieldRelPath`. */
    COMPUTED_TARGET,

    /** An entity named inside condition or operation text. */
    CONDITION_ENTITY,

    /**
     * An entity named by a `$Name$` parameter of an error message.
     *
     * A separate role because A12 answers this surface with its OWN vocabulary: the same four outcomes that
     * draw `MVK_*` codes inside a condition draw unprefixed `FehlertextErrorType` members here, two of which
     * share a tail with the condition-side code and one of which does not resemble it at all
     * ([KERNEL-FINDINGS KF336]).
     */
    MESSAGE_PARAMETER,
}

/**
 * Resolve an entity reference [written] inside a condition declared in [declaringGroup].
 *
 * **A condition's relative path is resolved from the declaring GROUP, not from the declaring element** —
 * the opposite of `errorEntityRelPath`, which climbs from the rule itself. The two are one `..` apart and
 * both spellings look identical, so the asymmetry is measured rather than assumed: a rule in `/Order/Lines`
 * reaches `/Order/Code` as `../Code` in its condition and as `../../Code` in its error target.
 *
 * A leading `/` makes the path absolute. A single bare name keeps the two-tier treatment of [resolve] —
 * the declaring group first, then the model-wide field short name when the model allows it — while a
 * multi-segment path is positional and gets no fallback. A trailing `*` or `.*` is a repetition wildcard
 * rather than part of a name, so it is stripped before matching and kept in the reported text.
 *
 * **The floor is the ROOT GROUP, not the model root.** A `..` that would leave the outermost group has no
 * position to name — the model root holds groups, not fields — and A12 says so with a distinct code rather
 * than reporting a missing element: `../Code` is above the root for a rule declared in a root group and
 * perfectly ordinary one level down (measured through `ModelLoader.loadAndCheck`).
 */
fun PreparedModel.resolveEntity(
    declaringGroup: String,
    written: String,
    origin: DeclarationOrigin = DeclarationOrigin(""),
): Reference {
    // A mounted declaration still spells its provider's own root, which the mount replaced. Rehosting the
    // written path is what the Kernel does before integration, so the reference names the element that
    // moved with it rather than one the host happens to have (KF3).
    val hosted = origin.rehost(written)
    val steps = hosted.split('/').filter { it.isNotEmpty() }.map { it.withoutRepetitionSuffix() }
    val absolute = hosted.startsWith("/")
    // The two-tier short-name treatment is for a single BARE LEAF. A starred segment names a repeatable
    // group's iteration rather than a leaf, and the Kernel gates its fallback per `idNameRef`, so `Lines*`
    // must not reach a model-wide field index that could resolve it to an unrelated field of that name.
    if (!absolute && steps.size == 1 && steps.single() != UP && !hosted.contains('*')) {
        return resolve(declaringGroup, steps.single(), origin)
    }
    val path = if (absolute) mutableListOf() else declaringGroup.split('/').filter { it.isNotEmpty() }.toMutableList()
    var aboveRoot = false
    steps.forEach { step ->
        when {
            // `..` optionally carries a TURNING-POINT LABEL naming the ancestor it lands on
            // (`relativeUp: relUp? '..' idName?`). The label is not a path segment; A12 checks that it names
            // the right ancestor and this does not, which under-reports rather than inventing a rejection.
            step.startsWith(UP) -> if (path.size <= 1) aboveRoot = true else path.removeAt(path.size - 1)
            else -> path += step
        }
    }
    val candidate = "/" + path.joinToString("/")
    return when {
        aboveRoot -> Reference.AboveRoot(written)   // the AUTHORED text: that is what a reader has to edit
        resolvableElements.any { it.path == candidate } -> Reference.Resolved(candidate)
        else -> Reference.Undeclared(candidate)
    }
}

private const val UP = ".."

/** Drop a repetition wildcard (`Lines*`, `Lines.*`) without touching the `..` step, whose text ends in `.`. */
private fun String.withoutRepetitionSuffix(): String {
    val withoutStar = removeSuffix("*")
    return if (withoutStar == UP) withoutStar else withoutStar.removeSuffix(".")
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
fun PreparedModel.resolve(
    declaringGroup: String,
    name: String,
    origin: DeclarationOrigin = DeclarationOrigin(""),
): Reference {
    val candidate = "$declaringGroup/$name"
    if (resolvableElements.any { it.path == candidate }) {
        return Reference.Resolved(candidate)
    }
    // WHICH model's flag applies, and WHICH elements the fallback may see, are one question with two
    // answers. A mounted declaration whose own model turns the flag on is bound INSIDE that model before
    // integration and emerges fully qualified, so a host reusing the name cannot retarget it. Every other
    // reference — one in the entry model, or one from a provider that leaves the flag off, which travels
    // bare — is bound over the whole EXPANDED tree under the entry model's flag, so a mounted field of the
    // same name does make it ambiguous. Both halves measured; see `DeclarationOrigin.shortNameFallbackAllowed`.
    val boundInProvider = origin.isMounted && origin.shortNameFallbackAllowed
    if (!boundInProvider && !fieldRefByShortNameAllowed) {
        return Reference.Undeclared(candidate)
    }
    // The denominator is this MOUNT INSTANCE's subtree, not the provider's own declarations. The provider is
    // fully expanded before it is bound (KF3), so a field its own nested include contributed is inside its
    // index; and one provider mounted at two points is two separate indexes, so the second copy must not make
    // the first ambiguous. Keying on the authoring model instead got both cases wrong, in the direction that
    // rejects a model A12 accepts — measured 2026-09-12 through `ModelLoader.loadAndCheck` on both shapes.
    // Named ONCE and then narrowed, rather than as two arms each naming the list: written as a two-arm `if`,
    // one arm can silently stop honouring the variant deletion while the other keeps it, and a mutant doing
    // exactly that survived every control here (2026-09-13) because the provider-bound arm needs a mounted
    // model with its own flag on to be reached at all. One expression cannot diverge from itself.
    val scope = resolvableElements.let { visible ->
        if (boundInProvider) visible.filter { it.path.startsWith(origin.mountedPrefix + "/") } else visible
    }
    val matches = scope.filter { it.declaredKind == DeclaredKind.FIELD && it.path.substringAfterLast('/') == name }
    return when (matches.size) {
        0 -> Reference.Undeclared(candidate)
        1 -> Reference.Resolved(matches.single().path)
        else -> Reference.Ambiguous(name, matches.map { it.path })
    }
}



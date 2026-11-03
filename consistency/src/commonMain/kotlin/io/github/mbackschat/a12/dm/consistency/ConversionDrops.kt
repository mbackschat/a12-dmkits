package io.github.mbackschat.a12.dm.consistency

/**
 * The declarations A12's validation-model converter leaves out of the model it checks, and why
 * ([KERNEL-FINDINGS KF652]).
 *
 * Kernel 31.1.1's `ValidationModelConverter` reports these and goes on without the declaration where 30.8.1 threw:
 * a root group or element whose name is blank as Java's `isBlank` reads it, and a child whose name a kept sibling
 * already carries. Either leaves the declaration and everything beneath it out, and the check runs over the rest.
 * Only A12's basic sentences, which read the model as read, still see a dropped declaration.
 *
 * Both causes are shape facts (a path and a name), so the declaration-set shape every reader of this view is
 * charged already covers them. A computation whose computed field is missing is left out too, but that rests on a
 * body member and a resolution, so report assembly withholds it instead ([withheldComputations]).
 *
 * @property dropped every declaration the converter leaves out, its subtree included.
 * @property causes the declarations the converter names, in its walk order: the first dropped one on each path
 *     down the tree, never one beneath it.
 */
internal data class ConversionDrops(val dropped: Set<DeclarationKey>, val causes: List<ConversionCause>)

/** One declaration the converter names, with the sentence it states. */
internal data class ConversionCause(val key: DeclarationKey, val element: DeclaredElement, val reason: ConversionReason)

internal enum class ConversionReason {
    /** "Root group must have a name.", stated at the root group. */
    ROOT_GROUP_NAME_MISSING,

    /** "Element must have a name.", stated at the group that holds the element. */
    ELEMENT_NAME_MISSING,

    /** "Cannot have two child elements with the same name …", stated at the later child. */
    CHILD_NAME_DUPLICATE,
}

/**
 * The converter's walk over [keyed], which lists the declarations depth-first, so a declaration's subtree is the
 * run of later entries beneath its path.
 *
 * A blank name is judged before the name is compared with its siblings', so a blank-named element never collides,
 * and of two children with one name the FIRST is kept: `addChildEntityIfAbsent` adds only a name the group does not
 * hold yet (measured: `adapter.laws.CheckerConverterSentenceAgreementTest`).
 */
internal fun conversionDrops(keyed: Map<DeclarationKey, DeclaredElement>): ConversionDrops {
    val dropped = LinkedHashSet<DeclarationKey>()
    val causes = mutableListOf<ConversionCause>()
    val kept = HashSet<String>()
    var droppedSubtree: String? = null
    for ((key, element) in keyed) {
        val beneath = droppedSubtree
        if (beneath != null && element.path.startsWith(beneath)) {
            dropped += key
            continue
        }
        droppedSubtree = null
        val reason = when {
            element.name.isA12Blank() ->
                if (element.isRootGroup()) ConversionReason.ROOT_GROUP_NAME_MISSING else ConversionReason.ELEMENT_NAME_MISSING
            !kept.add(element.path) -> ConversionReason.CHILD_NAME_DUPLICATE
            else -> null
        } ?: continue
        dropped += key
        causes += ConversionCause(key, element, reason)
        droppedSubtree = element.path + "/"
    }
    return ConversionDrops(dropped, causes)
}

/** Whether [this] is a root group: its path is one slash followed by its name. */
private fun DeclaredElement.isRootGroup(): Boolean = path == "/$name"

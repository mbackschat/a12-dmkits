package io.github.mbackschat.a12.dm.consistency

/**
 * A12's sentence for an element id another element shares: "The id [..] of element on path '..' is not unique: Used by
 * [..]." ([KERNEL-FINDINGS KF661]), published under this checker's own code.
 *
 * A12's `IdUniqueRule` runs third in its rule list, after the two version sentences and before code generation, over
 * the model as read. It walks every element in document order (root groups, groups, fields, rules and computations,
 * a blank-named one the converter leaves out included) and states the sentence once for each element that shares
 * its id, at that element's path. An empty id is an id like any other, so two empty ids share one; type definitions
 * are not elements and never share. Neither does an element that arrived through a mount: one provider mounted twice,
and a host field reusing a provider field's id, draw nothing, while the mount group itself, written in the model,
shares its id like any element. Nothing is left out and nothing later is withheld: A12 goes on to judge every
 * sharer. All of this is measured on kernel 31.1.1 (`adapter.laws.CheckerDuplicateIdAgreementTest`).
 *
 * The ids come in the order A12's `HashMap` hands them out ([computeIfAbsentOrder]): the rule calls `computeIfAbsent`
 * at each repeat occurrence, and the sharers of one id in the order the walk met them. An element with no `id`
 * string is left out, which under-reports: what A12 makes of two absent ids was not measured.
 */
internal fun TrackedFactBase.elementIdFindings(): List<Finding> {
    val first = mutableMapOf<String, TrackedDeclaration>()
    val sharers = mutableMapOf<String, MutableList<TrackedDeclaration>>()
    val repeats = mutableListOf<String>()
    for (element in declarationsAsRead()) {
        if (element.origin.isMounted) continue
        val id = element.id ?: continue
        val earlier = first.getOrPut(id) { element }.takeUnless { it === element } ?: continue
        sharers.getOrPut(id) { mutableListOf(earlier) } += element
        repeats += id
    }
    return computeIfAbsentOrder(repeats).flatMap { id ->
        val shared = sharers.getValue(checkNotNull(id))
        shared.map { element ->
            Finding(
                FindingCategory.A12_CONSTRAINT,
                FindingSeverity.ERROR,
                RK_ELEMENT_ID_NOT_UNIQUE,
                "this ${element.kind}'s id `$id` is also the id of ${shared.filter { it !== element }.joinToString { "`${it.path}`" }}, " +
                    "and A12 requires every element's id to be unique — give each its own",
                element.path,
            )
        }
    }
}

internal const val RK_ELEMENT_ID_NOT_UNIQUE = "RK_ELEMENT_ID_NOT_UNIQUE"

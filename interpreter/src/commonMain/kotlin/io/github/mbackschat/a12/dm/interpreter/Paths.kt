package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.model.RuleDef

// Pure path / relative-path helpers shared by the I2 seam collaborators (IF117) — string algebra only,
// no model or document state.

/** The `../…`-form relative path FROM rule element [from] TO absolute [target], matching [resolveErrorPath]'s
 *  convention: one `../` per [from] segment above the two paths' common prefix, then [target]'s remaining tail.
 *  Correct whether [target] is a descendant of [from]'s group, a sibling subtree, or in another root. */
internal fun relativePath(from: String, target: String): String {
    val f = pathParts(from)
    val t = pathParts(target)
    var common = 0
    while (common < f.size && common < t.size && f[common] == t[common]) common++
    return "../".repeat(f.size - common) + t.drop(common).joinToString("/")
}

internal fun ancestorGroups(fieldPath: String): List<String> {
    val parts = fieldPath.trim('/').split('/')
    return (1 until parts.size).map { "/" + parts.take(it).joinToString("/") }
}

internal fun pathParts(path: String): List<String> = path.trim('/').split('/')

/** The field's immediate parent group, e.g. `/Order/Items/Count` → `/Order/Items`. */
internal fun parentGroup(path: String): String = "/" + pathParts(path).dropLast(1).joinToString("/")

/** The field's topmost group (its root), e.g. `/Order/Items/Count` → `/Order`. */
internal fun rootGroupOf(path: String): String = "/" + pathParts(path).first()

/**
 * Resolve the rule's error-entity relative path to an absolute path. The relpath is relative to the
 * rule **node** (`rule.path`), so `../DeliveryDate` on `/Order/DeliveryNotBeforeOrder` pops the node
 * name → `/Order`, then appends → `/Order/DeliveryDate` (a sibling field in the rule's own group).
 */
internal fun resolveErrorPath(rule: RuleDef): String =
    checkNotNull(resolveRelative(rule.path, rule.errorEntityRelPath)) {
        "errorEntityRelPath '${rule.errorEntityRelPath}' at '${rule.path}' walks above the model root"
    }

/**
 * [rel] resolved against the element [base]: each `..` pops one name, with or without a slash after it, and every
 * other step appends. A root target from a nested group ends in a bare `..` (`../..`, IF454). Null when a step
 * walks above the model root; popping to the top and naming another root reaches that root.
 */
internal fun resolveRelative(base: String, rel: String): String? {
    val names = base.split('/').filter { it.isNotEmpty() }.toMutableList()
    for (step in rel.split('/')) when (step) {
        "" -> {}
        ".." -> if (names.isEmpty()) return null else names.removeAt(names.lastIndex)
        else -> names.add(step)
    }
    return "/" + names.joinToString("/")
}


package io.github.mbackschat.a12.dm.predicates

/**
 * Whether a condition's reference to the entity at [referencePath] references the rule's error entity at
 * [targetPath], the question behind `MVK_ERROR_FIELD_NOT_REFERENCED`.
 *
 * Naming the target or any group enclosing it reaches it; naming something inside a target group does not.
 * A12 refuses a group target that only its own fields are read from, and admits it once an enclosing group is
 * named (KERNEL-FINDINGS KF324 and KF601, `rulekit.dsl.RuleGroupErrorEntityTest`), so the relation is the same
 * for a field and a group target.
 *
 * Paths are absolute and slash-separated. The boundary is a whole segment: `/Order/Item` does not reach
 * `/Order/Items`. Which references count at all is the caller's question, not this law's: A12 counts a read
 * inside a `Having` body only when it carries the `$` marker (KF488).
 */
fun referenceReaches(referencePath: String, targetPath: String): Boolean =
    referencePath == targetPath ||
        (targetPath.length > referencePath.length + 1 && targetPath.startsWith(referencePath) &&
            targetPath[referencePath.length] == '/')

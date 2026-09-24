package io.github.mbackschat.a12.dm.consistency

/**
 * What one reference DEPENDS ON — the key a change is matched against.
 *
 * It is a sum rather than a path string because the three referents are reached by different changes and one
 * of them is not an element at all. A consumer asking "does this edit reach that declaration?" has to compare
 * like with like, and a single `String` would have forced a prefix convention (`typedef:Money`) that nothing
 * could check.
 */
sealed interface DependencyOn {

    /**
     * A declared element, by path — the resolved one, or the path the reference WOULD have named.
     *
     * The unresolved case is carried deliberately: declaring an element there is what repairs the reference,
     * so a change at that position reaches this declaration even though nothing sits there yet.
     */
    data class Element(val path: String) : DependencyOn

    /** A `content.typeDefinitions` entry, by id — the one referent in this checker that is not an element. */
    data class TypeDefinition(val id: String) : DependencyOn

    /**
     * A bare field name resolved by the model-wide short-name fallback, so ANY element carrying that name can
     * rebind the reference.
     *
     * Emitted only where the fallback is what did the resolving — a bare name the declaring group owns is
     * resolved by the LOCAL tier first ([resolve]) and cannot be rebound by a namesake elsewhere. This is the
     * one dependency with no path to follow, and the reason a path-keyed analysis silently under-reports: a
     * namesake declared elsewhere retargets the reference while touching nothing it currently names, and both
     * models are perfectly valid, so no finding appears on either side either.
     */
    data class ShortName(val name: String) : DependencyOn

    /**
     * A reference that names no position at all, so nothing can be keyed on it.
     *
     * A relative path climbing above the model root is the measured case: it addresses nothing rather than
     * addressing something absent, which is why A12 answers it with a code of its own. It gets an edge
     * anyway, because the analysis has to be able to say it could not follow this dependency.
     */
    data object Unfollowable : DependencyOn
}

/**
 * One declaration's dependency on one referent.
 *
 * @param from the declaration that carries the reference.
 * @param member the member it was written in, so a report can name which of a computation's several texts
 *     has to be re-read rather than only the computation.
 * @param written the reference exactly as authored, which is what a reader has to find and edit.
 * @param on what a change must touch to reach this declaration through this reference.
 * @param resolved whether the reference currently resolves. An unresolved dependency is a limit of the
 *     ANALYSIS — what it cannot follow — separately from being a defect in the model, which is a finding.
 */
data class DependencyEdge(
    val from: String,
    val member: String,
    val written: String,
    val on: DependencyOn,
    val resolved: Boolean,
)

/**
 * Every dependency this model's declarations have, across all three reference carriers.
 *
 * **One reference can yield TWO edges**, and that is the point rather than an accident: a bare name resolved
 * by the model-wide fallback depends both on the element it currently names and on the NAME, which different
 * changes reach. Collapsing them would force a choice between missing a rebind and reporting every namesake.
 *
 * The carriers are the two structured members ([declaredReferences]), the DSL texts
 * ([conditionReferences]), and a field's type-definition binding. An error message's `$Name$` parameter is
 * deliberately NOT a fourth: A12 admits a parameter only when the declaration already reads what it names,
 * so a resolvable parameter can add no referent its carrier's own condition has not already contributed
 * ([messageParameterFindings] carries that measurement).
 */
fun PreparedModel.dependencyEdges(): List<DependencyEdge> = buildList {
    declaredReferences().forEach { reference ->
        add(
            DependencyEdge(
                from = reference.declaredAt,
                member = reference.member,
                written = reference.written,
                on = reference.target.asElementDependency(),
                resolved = reference.target is Reference.Resolved,
            ),
        )
    }
    conditionReferences().forEach { reference ->
        add(
            DependencyEdge(
                from = reference.declaredAt,
                member = reference.member,
                written = reference.written,
                on = reference.target.asElementDependency(),
                resolved = reference.target is Reference.Resolved,
            ),
        )
        reference.rebindableShortName()?.let { name ->
            add(DependencyEdge(reference.declaredAt, reference.member, reference.written,
                DependencyOn.ShortName(name), reference.target is Reference.Resolved))
        }
    }
    elements.filter { it.declaredKind == DeclaredKind.FIELD }.forEach { field ->
        val id = field.boundTypeDefinitionId() ?: return@forEach
        add(DependencyEdge(field.path, "fieldType", id, DependencyOn.TypeDefinition(id),
            id in declaredTypeDefinitionIds))
    }
}

/** The path a reference names, keyed so a change at that position reaches it. */
private fun Reference.asElementDependency(): DependencyOn = when (this) {
    is Reference.Resolved -> DependencyOn.Element(path)
    is Reference.Undeclared -> DependencyOn.Element(candidate)
    // The candidates are what it is torn between, and a change to any of them settles or moves it; the NAME
    // is the referent they have in common, so it is the key that covers all of them at once.
    is Reference.Ambiguous -> DependencyOn.ShortName(name)
    is Reference.AboveRoot -> DependencyOn.Unfollowable
    // Only the walked prefix can change the verdict — a declaration there turns an entity miss into the token
    // error — while an unwalked one is refused whatever the model declares.
    is Reference.MisplacedUpStep -> walkedPrefix?.asElementDependency() ?: DependencyOn.Unfollowable
}

/**
 * The bare name a namesake elsewhere could rebind, or null when no namesake can.
 *
 * A reference is rebindable exactly when it is one bare segment AND the declaring group is not what resolved
 * it — the local tier wins first, so a group's own field cannot be taken over by a namesake. A bare name that
 * resolves to NOTHING is rebindable too: declaring it anywhere is what would bind it.
 */
private fun ConditionReference.rebindableShortName(): String? {
    // `.*` before `*`, or the dot survives and the name matches nothing — a silent false negative.
    val name = written.removeSuffix(".*").removeSuffix("*")
    if ('/' in name || name.isEmpty()) {
        return null
    }
    val declaringGroup = declaredAt.substringBeforeLast('/', missingDelimiterValue = "")
    val locallyResolved = (target as? Reference.Resolved)?.path == "$declaringGroup/$name"
    return name.takeUnless { locallyResolved }
}

package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.ConditionLanguage
import kotlinx.serialization.json.JsonObject

/**
 * The fact base as a check family sees it: every read recorded, and the model itself out of reach.
 *
 * **The barrier is the TYPE, not a lint.** A family whose receiver is this class cannot read an untracked
 * fact, because the only route to one would be a member handing back the [PreparedModel] and none exists. The
 * design record originally assigned this invariant to a lint; the compiler enforces it strictly better, and
 * an untracked read is the one failure the recorded-reads design can still have
 * (`docs/PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN.md` "L-b is PARTITIONED"), so it is worth a type.
 *
 * **It carries the facts families actually read, and grows one member at a time.** Exposing the fact base's
 * whole surface up front would leave most accessors unexercised, and an unexercised accessor is an untested
 * one: a member recording the wrong [ModelFact] would key a cell on a fact it never read, which is a silent
 * wrong-key bug and precisely the class that makes reuse unsound. A family that needs a fact not here cannot
 * compile until it is added, which is the forcing function — so the missing member is caught by the compiler
 * rather than by care, and arrives with the test that exercises it.
 *
 * It is a view, not a copy: constructing one allocates no facts, and several may share a [PreparedModel].
 */
internal class TrackedFactBase(
    private val model: PreparedModel,
    private val transcript: ReadTranscript,
) {
    /**
     * Every declaration, as cursors that record what is read THROUGH them.
     *
     * Records the set's shape alone — [FactRead.DeclarationSetShape] carries why that separation is the
     * design rather than an optimization.
     */
    fun declarations(): List<TrackedDeclaration> {
        transcript.record(FactRead.DeclarationSetShape)
        return model.elements.map { TrackedDeclaration(it, model, transcript) }
    }

    /**
     * The declaration at [path], or null when nothing declares it.
     *
     * Charges the set's SHAPE and not the found declaration's content, which is the split that matters: a
     * family asking "is there a field here, and what type is it" depends on the set for the answer to the
     * first question and on that one declaration's body for the second. The body is charged if and when it
     * is read through the returned cursor, so a family that only checks existence does not pay for content.
     */
    fun declarationAt(path: String): TrackedDeclaration? {
        transcript.record(FactRead.DeclarationSetShape)
        return model.elements.firstOrNull { it.path == path }?.let { TrackedDeclaration(it, model, transcript) }
    }

    /**
     * Resolve a written entity path from [declaringGroup] — the reference reader, charged.
     *
     * **Resolution depends on the set's SHAPE and one model flag, and on no declaration's body**, which is
     * what makes it cheap to charge and harmless to granularity: it consults the by-path index and the
     * resolvable set, and reads `fieldRefByShortNameAllowed` for the short-name tier. No keystroke in a
     * condition or a message changes any of those, so a cell that resolved a reference survives every edit
     * that leaves the declarations where they are.
     *
     * Charges and then delegates, as [TrackedDeclaration.declaredReference] does: the barrier's job is to
     * record the dependency, not to hold a second copy of a resolver whose behaviour is measured elsewhere.
     */
    fun resolveEntity(
        declaringGroup: String,
        written: String,
        origin: DeclarationOrigin = DeclarationOrigin(""),
    ): Reference {
        transcript.record(FactRead.DeclarationSetShape)
        transcript.record(FactRead.ModelMember(ModelFact.FIELD_REF_BY_SHORT_NAME_ALLOWED))
        return model.resolveEntity(declaringGroup, written, origin)
    }

    val conditionLanguage: ConditionLanguage
        get() {
            transcript.record(FactRead.ModelMember(ModelFact.CONDITION_LANGUAGE))
            return model.conditionLanguage
        }

    val declaredLocales: List<String>
        get() {
            transcript.record(FactRead.ModelMember(ModelFact.DECLARED_LOCALES))
            return model.declaredLocales
        }
}

/**
 * One declaration, recording the reads that can distinguish it from another model's.
 *
 * **What is free here and what is charged is the alphabet's other half.** [path], [name], [kind], [id],
 * [origin] and [declaredKind] record nothing, because the set shape already covers them and a cursor obtained
 * from [TrackedFactBase.declarations] has paid for that shape. [body] charges this declaration's path,
 * because it is the member an edit changes.
 */
internal class TrackedDeclaration(
    private val element: DeclaredElement,
    /**
     * Held for [declaredReference] alone, which needs the model's memoized walk.
     *
     * The barrier's integrity does not rest on hiding it from this class — it rests on this class exposing
     * no member that hands it out, which is checkable in the twenty lines below. Building the reference
     * lookup eagerly instead would force that `by lazy` walk on every check whose families never ask for a
     * reference, and the walk reads every declaration's body.
     */
    private val model: PreparedModel,
    private val transcript: ReadTranscript,
) {
    val path: String get() = element.path
    val name: String get() = element.name
    val kind: String get() = element.kind
    val id: String? get() = element.id
    val origin: DeclarationOrigin get() = element.origin
    val declaredKind: DeclaredKind? get() = element.declaredKind

    /** The kind's payload as A12 binds it — see [DeclaredElement.body], whose three cases are not two. */
    val body: JsonObject?
        get() {
            transcript.record(FactRead.DeclarationContent(element.path))
            return element.body
        }

    /**
     * This declaration's own declared reference — a rule's error target or a computation's computed field.
     *
     * **A derived query, charged per declaration, which is the case the design record singles out.** The
     * whole-model `declaredReferences()` hands its caller every rule's and computation's reference at once,
     * so eighteen families reading it would each depend on every one of those declarations' bodies. This
     * entry point charges exactly what the answer depends on: THIS declaration's body, for the relative path
     * it spells, and the set's shape, because resolving that path consults the by-path index. Another
     * declaration's body cannot change this answer and is not charged for it.
     */
    fun declaredReference(): DeclaredReference? {
        transcript.record(FactRead.DeclarationContent(element.path))
        transcript.record(FactRead.DeclarationSetShape)
        return model.declaredReferenceList.firstOrNull { it.declaredAt == element.path }
    }
}

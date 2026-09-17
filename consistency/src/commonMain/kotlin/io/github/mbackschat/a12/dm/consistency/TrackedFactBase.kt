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
internal class TrackedFactBase private constructor(
    private val model: PreparedModel,
    private val transcript: ReadTranscript,
    private val scope: Scope?,
) {
    constructor(model: PreparedModel, transcript: ReadTranscript) : this(model, transcript, null)

    /** The one declaration a scoped fact base answers for, with the key its cell is filed under. */
    private class Scope(val at: DeclarationKey, val cursor: TrackedDeclaration)

    /**
     * Every declaration, as cursors that record what is read THROUGH them — or, on a SCOPED fact base, the
     * single declaration it was narrowed to.
     *
     * Records the set's shape alone — [FactRead.DeclarationSetShape] carries why that separation is the
     * design rather than an optimization. A scoped base records [FactRead.DeclarationRow] instead, which is
     * the whole point of the narrowing: the family's loop runs once, over its own declaration, and its cell
     * is keyed on that declaration rather than on the set. [scopedTo] says when the dispatcher does it.
     */
    fun declarations(): List<TrackedDeclaration> {
        val scoped = scope
        if (scoped != null) {
            transcript.record(FactRead.DeclarationRow(scoped.at.path, scoped.at.amongSamePath))
            return listOf(scoped.cursor)
        }
        transcript.record(FactRead.DeclarationSetShape)
        return cursors
    }

    /**
     * This fact base narrowed to the declaration at [at], for a family the dispatcher asks per declaration.
     *
     * **It exists so a family whose body is a WALK can be asked per declaration without being rewritten.**
     * Such a family iterates, filters by kind, and judges each declaration on its own facts — which is
     * exactly what one pass over one declaration does, so the concatenation over the walk is the list the
     * unscoped family produced, in the same order. Rewriting thirty bodies to take a parameter buys nothing
     * the scope does not, and every rewrite is a chance to change a verdict.
     *
     * **Only [declarations] narrows. Everything else answers for the whole model and charges the set shape**
     * — `declarationAt`, `isResolvable`, `resolveEntity`, `declaredReferences`. That is deliberate and it is
     * what keeps the gate mechanical: a family reaching any of them charges
     * [FactRead.DeclarationSetShape], and `FamilyChargeProfileTest` refuses a per-declaration cell that
     * charges the shape. Narrowing those instead would silently answer a set question with one declaration.
     *
     * It shares this base's model, transcript and cursor list, so a scope allocates a view and no facts.
     */
    fun scopedTo(at: DeclarationKey, cursor: TrackedDeclaration): TrackedFactBase =
        TrackedFactBase(model, transcript, Scope(at, cursor))

    /**
     * The cursors, built once per fact base rather than once per walk.
     *
     * A migrated family asks for the declarations from inside a per-reference loop — `iteratedLevels` and
     * `lowestRepetitionOf` are each asked once per operand — so rebuilding the list would allocate a cursor
     * per declaration per call, where the untracked code it replaces read a list it already held. The charge
     * deliberately stays outside this: [declarations] records the shape on EVERY call, because the transcript
     * is what a cell is keyed on and a memoized list must not hide a read.
     */
    private val cursors: List<TrackedDeclaration> by lazy {
        model.elements.map { TrackedDeclaration(it, model, transcript) }
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
        return cursors.firstOrNull { it.path == path }
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

    /**
     * Every declared reference in the model, assembled from the per-declaration cursor.
     *
     * **A family that genuinely reads every declaration gets a model-wide transcript, and that is the truth
     * rather than a defect.** What the cursor buys is ATTRIBUTION — the transcript says which declarations
     * were read, one entry each — and per-declaration *reuse* then comes from keying a cell per declaration
     * rather than from pretending the family read less than it did. Going through
     * [TrackedDeclaration.declaredReference] is what keeps that attribution; reading the model's own list
     * would give one opaque charge that no cell could ever narrow.
     */
    fun declaredReferences(): List<DeclaredReference> = everyDeclaration().mapNotNull { it.declaredReference() }

    /**
     * Every declaration, whatever this base is scoped to — the route a SET-level question takes.
     *
     * Kept apart from [declarations] so a scope cannot narrow an answer that is about the model: a scoped
     * [declaredReferences] would hand back one reference and charge one row, which reads like an answer and
     * is not one.
     */
    private fun everyDeclaration(): List<TrackedDeclaration> {
        transcript.record(FactRead.DeclarationSetShape)
        return cursors
    }

    /**
     * Whether [path] names a declaration a reference may resolve to.
     *
     * Named for the question rather than for the act, because two families already declare a LOCAL `resolves`
     * over their own operand and a member of that name would sit silently shadowed beside them.
     *
     * **Charged as the set's SHAPE, and that is the design rather than a convenience.** The resolvable set is
     * the declarations minus the ones the default variant deletes, and that filter sits in the shape on
     * purpose ([FactRead.DeclarationSetShape] says why) — charging it as content would hand every reader of
     * the resolvable set a dependency on every body. So resolution costs no granularity at all: a keystroke
     * in a condition or a message leaves this answer alone.
     */
    fun isResolvable(path: String): Boolean {
        transcript.record(FactRead.DeclarationSetShape)
        return path in model.resolvableByPath
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

    val hasBaseYear: Boolean
        get() {
            transcript.record(FactRead.ModelMember(ModelFact.HAS_BASE_YEAR))
            return model.hasBaseYear
        }

    val supportedCharacters: List<String>
        get() {
            transcript.record(FactRead.ModelMember(ModelFact.SUPPORTED_CHARACTERS))
            return model.supportedCharacters
        }

    val unreadableLocaleCodes: List<String>
        get() {
            transcript.record(FactRead.ModelMember(ModelFact.UNREADABLE_LOCALE_CODES))
            return model.unreadableLocaleCodes
        }

    val declaredTypeDefinitionIds: Set<String>
        get() {
            transcript.record(FactRead.ModelMember(ModelFact.DECLARED_TYPE_DEFINITION_IDS))
            return model.declaredTypeDefinitionIds
        }

    /**
     * Run [produce] with every read it makes charged to [key]'s own bucket.
     *
     * The transcript stays private, so this is the only route to a scope — a family cannot open one for
     * itself, and the dispatcher is the single place that decides who is producing.
     */
    fun <T> whileProducing(key: CellKey, produce: () -> T): T = transcript.whileProducing(key, produce)

    /** What the cell at [key] read out of this fact base — what that cell is then keyed on. */
    fun observedWhileProducing(key: CellKey): Set<FactRead> = transcript.observedWhileProducing(key)

    /**
     * Charge the declaration at [at] its own shape row, for the cell currently producing.
     *
     * The dispatcher calls it before asking a per-declaration family, so the cell records WHICH declaration
     * it answered about rather than only where it sat — [FactRead.DeclarationRow] says why the cursor's free
     * shape members leave that gap.
     */
    fun chargeDeclarationRow(at: DeclarationKey) {
        transcript.record(FactRead.DeclarationRow(at.path, at.amongSamePath))
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

    /**
     * Two cursors are equal when they stand for the same declaration — structural, like the element they wrap.
     *
     * **A latent trap, removed rather than documented.** An inherited identity `equals` would make two
     * cursors for the SAME declaration unequal, where the [DeclaredElement] data class they replace compares
     * equal — so a family migrated by a receiver change would silently answer differently, and nothing
     * catches it, because the families migrated so far all compare by `path` and that had to be established
     * by hand-reading eight files. Delegating to the element makes `==` mean what every reader of the
     * untracked code already assumed.
     *
     * [cursors] closes the same hole from the other side WITHIN one fact base, by handing out the same object
     * twice; this covers the case it cannot — cursors from two fact bases over one model, which is exactly
     * what a draft holds beside its baseline.
     *
     * Charges nothing: which declaration a cursor stands for is the set's shape, already paid by whoever
     * obtained it — the same reason [path] and [kind] record nothing.
     */
    override fun equals(other: Any?): Boolean = other is TrackedDeclaration && element == other.element

    override fun hashCode(): Int = element.hashCode()
}

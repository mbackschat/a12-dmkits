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
    private val views: Views,
    private val scope: Scope?,
) {
    constructor(model: PreparedModel, transcript: ReadTranscript) :
        this(model, transcript, Views(model, transcript), null)

    /** The one declaration a scoped fact base answers for, with the key its cell is filed under. */
    private class Scope(val at: DeclarationKey, val cursor: TrackedDeclaration)

    /**
     * The derived views every fact base of ONE check shares — the cursors and the two indexes over them.
     *
     * **They live here because [scopedTo] makes a new fact base per declaration, and a per-instance `lazy`
     * is then per DECLARATION.** With N of them over an N-element model each rebuilding its own cursor list
     * and index, the check is quadratic in declarations and allocates N cursors N times; `getStringHashCode`
     * under the index build alone was **15.6 % of an 800-program run, its share growing 88x** against the
     * 4x scale-up (measured 2026-09-18). `scopedTo`'s own contract said it "shares this base's model,
     * transcript and cursor list" — the first two were shared and the third was not, which is the kind of
     * claim [ScopedFactBaseSharesItsViewsTest] now holds to.
     *
     * The CHARGE deliberately stays outside: each accessor records on every call, because the transcript is
     * what a cell is keyed on and a shared view must not hide a read.
     */
    private class Views(private val model: PreparedModel, private val transcript: ReadTranscript) {

        /** Every declaration as a cursor, allocated once per check. */
        val cursors: List<TrackedDeclaration> by lazy {
            model.elements.map { TrackedDeclaration(it, model, transcript) }
        }

        /**
         * [cursors] keyed by path, so `declarationAt` is a lookup rather than a scan.
         *
         * FIRST occurrence wins, because `firstOrNull` is the answer it replaces — the same rule, and the
         * same reason, as `PreparedModel.resolvableByPath`. Measured: the scan was 29x growth over a 4x
         * scale-up (2026-09-18), being linear per call and called once per declaration.
         */
        val cursorsByPath: Map<String, TrackedDeclaration> by lazy {
            val byPath = LinkedHashMap<String, TrackedDeclaration>(cursors.size)
            cursors.forEach { cursor -> byPath.getOrPut(cursor.path) { cursor } }
            byPath
        }

        /**
         * The repeatable groups among [cursors], for the set questions that ask only about them.
         *
         * **Filtered over the UNTRACKED elements, and a memoized view has no other choice.** Asking
         * `TrackedDeclaration.isRepeatableGroup` opens each group's body AT THE BARRIER, and this value is
         * computed once per check — so those reads are charged to whichever producer happened to force it,
         * and every later producer is answered from the same list while recording nothing for it. That is a
         * charge narrower than what was read, which [ReadTranscript] and `isRepeatable` both name as the one
         * direction that makes reuse unsound. Indexed rather than keyed by path so two declarations sharing
         * a path cannot collapse into one.
         *
         * The dependency itself is not dropped, it MOVED: [repeatableGroups] charges
         * [ModelFact.REPEATABLE_GROUP_SET], whose value is this question's own answer.
         */
        val repeatable: List<TrackedDeclaration> by lazy {
            cursors.filterIndexed { index, _ -> model.elements[index].isRepeatableGroup }
        }
    }

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
        return views.cursors
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
     * **[declarations] and the ASSEMBLIES over it narrow; the set questions do not.** Narrowing reaches
     * `declarations`, `declaredReferences` and `conditionReferences` — the last two are built ON the walk, so
     * they narrow with the pass by construction and their scoped concatenation is the unscoped list.
     * [allDeclarations], `declarationAt`, `isResolvable` and `resolveEntity` answer for the whole model and
     * charge the set shape, because narrowing them would answer a set question with one declaration.
     *
     * **And no gate makes that mechanical — a helper reads whichever it was written against, however many
     * frames below the family's entry point it sits.** Corrected 2026-09-18, after a cold review measured
     * four families answering differently under the scope: three invented a verdict and one dropped one, all
     * through set helpers in `Reference.kt` that read `declarations()` two or three frames down
     * (`ScopedWalkBranchDiscriminatorsTest`). This paragraph previously claimed the gate WAS mechanical,
     * naming `FamilyChargeProfileTest` as the enforcement locus and listing `declaredReferences` as
     * non-narrowing; the first is not what that test asserts and the second is the opposite of what it does.
     * What actually catches this class is the equivalence differential, and only on a model that reaches the
     * branch — so a new set helper gets [allDeclarations] because it MEANS the set, not because a build step
     * will say so.
     *
     * It shares this base's model, transcript and derived [Views], so a scope allocates a view and no
     * facts — which is a property of the code rather than of the prose, because those views were per
     * INSTANCE until 2026-09-18 and this sentence was wrong about the third of the three.
     */
    fun scopedTo(at: DeclarationKey, cursor: TrackedDeclaration): TrackedFactBase =
        TrackedFactBase(model, transcript, views, Scope(at, cursor))

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
        return views.cursorsByPath[path]
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
     *
     * **It is an ASSEMBLY over [declarations], so it narrows with the pass** — like
     * [conditionReferencesOf]'s whole-model arm, and unlike [allDeclarations]. *Corrected 2026-09-18: it
     * briefly went through the unnarrowable route, on the reasoning that one reference "reads like an
     * answer and is not one". That mistook what the accessor is FOR: every family reading it loops over the
     * references OF the declaration it is judging, so one declaration's references are exactly the answer,
     * and the unnarrowable route made a scoped family repeat the whole model's list once per declaration.
     * A family wanting a genuine set question over the references has no caller today and would use
     * [allDeclarations] itself.*
     */
    fun declaredReferences(): List<DeclaredReference> =
        declarations().mapNotNull { it.declaredReference() }

    /**
     * Every declaration in the model, whatever this base is scoped to — the route a SET question takes.
     *
     * **The distinction it draws is the one that decides whether a family can be asked per declaration.**
     * [declarations] is "the declarations this pass is about"; this is "every declaration there is". A
     * family asking the set what an operand resolved to wants THIS one, and the idiom it used to reach for
     * — `declarations().firstOrNull { it.path == target }` — silently became a one-element search the
     * moment the dispatcher narrowed the pass. So the two questions are two members, and a scope narrows
     * only the first.
     *
     * It charges [FactRead.DeclarationSetShape], which is honest and cheap for the case reuse is for: a
     * keystroke in a condition moves no declaration's row and no resolvable path, so a cell that asked a
     * set question survives every edit that leaves the declarations where they are.
     */
    fun allDeclarations(): List<TrackedDeclaration> {
        transcript.record(FactRead.DeclarationSetShape)
        return views.cursors
    }

    /**
     * The REPEATABLE groups the model declares, charged as [ModelFact.REPEATABLE_GROUP_SET].
     *
     * **A fifth member of the access vocabulary, and it exists for cost rather than for meaning.** A caller
     * could filter [allDeclarations] and get the same list — that is what `crossedRepetitions` and
     * `repeatableGroupPaths` did — but the filter is linear and both are asked per operand, so on an
     * 800-program model they grew **16x and 39x** against the check's own 5.5x over a 4x scale-up (measured
     * 2026-09-18).
     *
     * **Its charge is this question's own answer, and it took a second pass to get right.** It first charged
     * [FactRead.DeclarationSetShape], on the reasoning that the answer depends on the set and nothing else.
     * That is wrong in the unsound direction: repeatability is a BODY member, the shape carries only path,
     * name, kind, id and origin, so an edit that stops a group repeating moves no shape member and a cell
     * answered from this list would be revalidated unchanged. The dedicated fact is also strictly more
     * precise than the shape — an unrelated declaration appearing no longer falls these cells. Locked by
     * `MemoizedViewChargeTest`, which fails on any memoized view that charges less in company than alone.
     */
    fun repeatableGroups(): List<TrackedDeclaration> {
        transcript.record(FactRead.ModelMember(ModelFact.REPEATABLE_GROUP_SET))
        return views.repeatable
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
     *
     * **Known limit, at a twinned path: the lookup is by PATH, so the second twin gets the first's
     * reference.** A path is not a unique key here — that is why the cell key carries an ordinal — and this
     * is the one place left that treats it as one. It is equivalence-preserving (the scoped concatenation
     * equals the unscoped list, before and after the narrowing) and charged correctly, so reuse stays sound;
     * what it can do is attribute one twin's error target to the other on a model A12 refuses anyway. Named
     * rather than fixed: the fix needs `declaredReferenceList` keyed by (path, ordinal), which is a change to
     * preparation rather than to the barrier (cold review, 2026-09-18).
     */
    fun declaredReference(): DeclaredReference? {
        transcript.record(FactRead.DeclarationContent(element.path))
        transcript.record(FactRead.DeclarationSetShape)
        return model.declaredReferenceByPath[element.path]
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
     * [Views.cursors] closes the same hole from the other side WITHIN one check, by handing out the same object
     * twice; this covers the case it cannot — cursors from two fact bases over one model, which is exactly
     * what a draft holds beside its baseline.
     *
     * Charges nothing: which declaration a cursor stands for is the set's shape, already paid by whoever
     * obtained it — the same reason [path] and [kind] record nothing.
     */
    override fun equals(other: Any?): Boolean = other is TrackedDeclaration && element == other.element

    override fun hashCode(): Int = element.hashCode()
}

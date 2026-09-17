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
        return model.elements.map { TrackedDeclaration(it, transcript) }
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
}

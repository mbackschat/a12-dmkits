package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Tri-engine differential — is the scalar `GroupNotFilled` LEAF observable at all when the group carries both
 * admitted content and an independent formal error? a12-kernel-lean's `SPEC-2026-09-02-03` changed their leaf
 * from unknown to false there and states, correctly, that rule-level non-firing cannot distinguish the two: a
 * silent rule is silent either way. Their acceptance asks us either to align or to supply a leaf-observable
 * witness, so this file goes looking for one rather than reasoning about whether one could exist.
 *
 * **The channel probed is the message's own POLARITY, not whether a rule fires.** A12's condition language has
 * no negation combinator, so a leaf can only occur positively and Kleene `And`/`Or` are monotone — a leaf moving
 * from false to unknown can never turn a silent rule into a firing one. What it might still change is the
 * omission-vs-value TYPE of a rule that fires for another reason. So every rule here is
 * `Or(GroupNotFilled(G), FieldNotFilled(Anchor))` with `Anchor` empty: the second disjunct fires all three, and
 * the type is read off the fired message.
 *
 * **Two of the three groups are the control that decides whether this channel discriminates at all**, and they
 * come first in the reading. `Clean` is filled and error-free, so the leaf is false under BOTH accounts;
 * `ContentFreeError` carries only rejected content, so the leaf is unknown under BOTH. If those two report the
 * same type, the channel cannot see the leaf and the third row settles nothing — which is a finding about the
 * instrument and is reported as one, rather than a silently vacuous pass.
 *
 * Own domain (insurance claim adjudication).
 */
class GroupNotFilledErroneousLeafDiffTest {

    private companion object {
        const val CASE = "/Case"
        const val ANCHOR = "/Case/Anchor"

        /** Filled note + malformed amount: admitted content AND an independent error. The entry's subject. */
        const val MIXED = "/Case/Mixed"
        const val MIXED_NOTE = "/Case/Mixed/Note"
        const val MIXED_AMOUNT = "/Case/Mixed/Amount"

        /** Filled and error-free — the leaf is definitely FALSE under both accounts. */
        const val CLEAN = "/Case/Clean"
        const val CLEAN_NOTE = "/Case/Clean/Note"
        const val CLEAN_AMOUNT = "/Case/Clean/Amount"

        /** Only rejected content — the leaf is UNKNOWN under both accounts. */
        const val ERRORED = "/Case/ContentFreeError"
        const val ERRORED_AMOUNT = "/Case/ContentFreeError/Amount"

        const val MALFORMED = "not-a-number"

        /** Pinned by the first run of this file; see the note at its assertion. */
        const val LEAF_TYPE = "OMISSION_ERROR"
    }

    private val base = DocumentModelDraft.named("claim-adjudication-leaf").locale("en_US")
        .rootGroup("Case") { case ->
            case.stringField("Anchor") { }
            for (group in listOf("Mixed", "Clean", "ContentFreeError")) {
                case.group(group) { g ->
                    g.stringField("Note") { }
                    g.numberField("Amount") { it.fractionDigits(0) }
                }
            }
        }.build().toJson()

    private val bound = BoundModel.ofJson(base)

    /**
     * `Or(GroupNotFilled(G), FieldNotFilled(Anchor))`. The second disjunct is what fires the rule — `Anchor` is
     * empty in the one document below — and it doubles as the error-field reference the kernel requires
     * (`MVK_ERROR_FIELD_NOT_REFERENCED`). So the leaf never decides whether the rule fires, only what it can
     * still contribute to the fired message's typing.
     */
    private fun rule(code: String, groupPath: String): Rule =
        bound.rule(bound.group(CASE)).name("${code}Rule").errorField(bound.str(ANCHOR))
            .`when`(Dsl.groupNotFilled(bound.group(groupPath)).or(Dsl.fieldNotFilled(bound.field(ANCHOR))))
            .code(code).severity(Severity.ERROR).message(Dsl.en(code)).build()

    private val rules = listOf(
        rule("CLEANLEAF", CLEAN),
        rule("ERRORLEAF", ERRORED),
        rule("MIXEDLEAF", MIXED),
    )

    private val modelJson = rules.fold(base) { json, r -> TypedRules.inject(json, r) }
    private val definition = ModelLoader.load(modelJson)
    private val staticEngine by lazy { RuntimeLaws.kernelJavaEngine(modelJson) }

    @Test
    fun theProbeModelIsAdmitted() =
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(modelJson),
            "a refused model would make every type reading below vacuous")

    /**
     * **The instrument's own control, and the result of this file.** `Clean` (leaf definitely false) and
     * `ContentFreeError` (leaf definitely unknown) must report DIFFERENT types for the polarity channel to see
     * the leaf at all. They do not: all three rules type identically, so a fired `Or` whose true disjunct is the
     * field predicate carries that disjunct's polarity and the group leaf contributes nothing observable.
     *
     * <p>That is the honest answer to `SPEC-2026-09-02-03`: this project found no leaf-observable witness on the
     * channel with the best chance of carrying one, so the two accounts remain indistinguishable through the
     * kernel and the choice is free on both sides — the same disposition their own `IF316`-shaped uniqueness row
     * records for a different operator. It is asserted as a THREE-WAY equality rather than described, so a kernel
     * upgrade that made the polarity leaf-sensitive would fail here and reopen the question.
     */
    @Test
    fun thePolarityChannelCannotSeeTheLeafSoTheAccountsStayIndistinguishable() {
        val types = triEngineTypes()
        assertEquals(
            setOf("CLEANLEAF", "ERRORLEAF", "MIXEDLEAF"), types.keys,
            "all three rules must fire, or the polarity reading has nothing to read",
        )
        // Pinned per code rather than as "they are all equal": an all-equal assertion would also hold if the
        // typing channel collapsed everything for an unrelated reason, and the VALUE the three agree on is what
        // a reader needs to see the leaf contributing nothing.
        assertEquals(
            mapOf("CLEANLEAF" to LEAF_TYPE, "ERRORLEAF" to LEAF_TYPE, "MIXEDLEAF" to LEAF_TYPE), types,
            "the control pair must differ for this channel to discriminate — it does not",
        )
    }

    /**
     * The formal error the mixed group carries is really there, and the clean group really carries none. Without
     * this the three groups above might be indistinguishable because the fixture never differed, which would
     * satisfy the equality assertion for the wrong reason.
     */
    @Test
    fun theFixtureReallyDiffersInTheWayTheEntryDescribes() {
        val formal = RuntimeLaws.messages(modelJson, kernelDocument())
            .filter { it.code() !in setOf("CLEANLEAF", "ERRORLEAF", "MIXEDLEAF") }
            .map { it.field() }.toSet()
        assertEquals(
            setOf("/Case[1]/Mixed[1]/Amount", "/Case[1]/ContentFreeError[1]/Amount"),
            formal,
            "exactly the mixed and content-free groups must carry a formal finding, and the clean group none",
        )
    }

    // ---- the instrument ----

    /** Each fired probe code → its message type, asserted equal across both kernel strategies and the interpreter. */
    private fun triEngineTypes(): Map<String, String> {
        val probes = setOf("CLEANLEAF", "ERRORLEAF", "MIXEDLEAF")
        val dynamic = RuntimeLaws.messages(modelJson, kernelDocument())
            .filter { it.code() in probes }.associate { it.code() to it.type().name }
        val static = staticEngine.messages(staticEngine.instance(kernelDocument()))
            .filter { it.code() in probes }.associate { it.code() to it.type().name }
        val interpreter = DmInterpreter(definition).validateFull(interpreterDocument())
            .filter { it.code in probes }
            .associate { it.code to if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR" }
        assertEquals(dynamic, static, "generated Java vs normative dynamic-Groovy")
        assertEquals(dynamic, interpreter, "interpreter vs normative dynamic-Groovy")
        return dynamic
    }

    /** `Anchor` empty (so every rule fires); Mixed = content + error, Clean = content, ContentFreeError = error only. */
    private fun kernelDocument(): RuntimeLaws.Doc = RuntimeLaws.doc().group(CASE, 1)
        .group(MIXED, 1, 1).field(MIXED_NOTE, "adjuster visited", 1, 1, 1)
        .field(MIXED_AMOUNT, MALFORMED, 1, 1, 1)
        .group(CLEAN, 1, 1).field(CLEAN_NOTE, "photos attached", 1, 1, 1)
        .field(CLEAN_AMOUNT, "500", 1, 1, 1)
        .group(ERRORED, 1, 1).field(ERRORED_AMOUNT, MALFORMED, 1, 1, 1)

    private fun interpreterDocument(): Document = definition.newDocument().group(CASE, 1)
        .group(MIXED, 1, 1).field(MIXED_NOTE, "adjuster visited", 1, 1, 1)
        .field(MIXED_AMOUNT, MALFORMED, 1, 1, 1)
        .group(CLEAN, 1, 1).field(CLEAN_NOTE, "photos attached", 1, 1, 1)
        .field(CLEAN_AMOUNT, "500", 1, 1, 1)
        .group(ERRORED, 1, 1).field(ERRORED_AMOUNT, MALFORMED, 1, 1, 1)
}

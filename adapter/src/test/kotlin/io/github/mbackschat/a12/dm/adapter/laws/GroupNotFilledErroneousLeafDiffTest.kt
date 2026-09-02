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
import org.junit.jupiter.api.TestInstance

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
 * omission-vs-value TYPE of a rule that fires for another reason, and the typing walk is a separate computation
 * over the same tree. So every rule here is `Or(GroupNotFilled(G), <other>)` with `<other>` true, and the type is
 * read off the fired message.
 *
 * **The design has TWO controls, on two different axes, and both are load-bearing.**
 *
 * <p><b>Across groups</b>, `Clean` is filled and error-free so its leaf is false under BOTH accounts, and
 * `ContentFreeError` carries only rejected content so its leaf is unknown under BOTH. Those two are the pair the
 * accounts already agree about: if they report the same type, the channel cannot see the leaf and the disputed
 * `Mixed` row settles nothing.
 *
 * <p><b>Across families</b>, the same three leaves are fired once from an omission-typed disjunct
 * (`FieldNotFilled(Anchor)`, `Anchor` empty) and once from a VALUE-typed one (`[Clean/Amount] > 100` over a
 * filled well-formed cell). Without this second axis the whole file would fire from one predicate and a uniform
 * `OMISSION_ERROR` would be a property of the fixture rather than a reading — the condition constant across
 * every row, which is the confound CLAUDE.md's oracle rules name. A cold review found exactly that on
 * 2026-09-03; the second family is the correction.
 *
 * <p>Measured: the type varies with the OTHER disjunct and never with the leaf. So the channel is demonstrably
 * live and demonstrably blind to the thing under dispute.
 *
 * Own domain (insurance claim adjudication).
 */
// One instance per CLASS: these files hold their model JSON, the loaded definition and a kernel Java
// engine as fields, and this Gradle task also carries a clock-reading assertion. Per-method lifecycle
// would redo codegen and model loading on every case for no gain (CLAUDE.md: a new test must not
// perturb a timing floor sharing its task).
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
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

        val PROBES = setOf(
            "CLEANLEAF", "ERRORLEAF", "MIXEDLEAF", "VCLEANLEAF", "VERRORLEAF", "VMIXEDLEAF",
        )
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

    /**
     * The same three leaves fired from a VALUE-typed disjunct instead — `[Clean/Amount] > 100` over a filled,
     * well-formed cell. Without this second family every row in the file would fire from one omission-typed
     * predicate, so `OMISSION_ERROR` everywhere would be a property of the fixture rather than a reading: the
     * condition held constant across the whole row set, which is the confound CLAUDE.md's oracle rules name and
     * which a cold review found here on 2026-09-03. The error field moves to the compared cell because the
     * kernel requires the condition to read it.
     */
    private fun valueTypedRule(code: String, groupPath: String): Rule =
        bound.rule(bound.group(CASE)).name("${code}Rule").errorField(bound.num(CLEAN_AMOUNT))
            .`when`(Dsl.groupNotFilled(bound.group(groupPath)).or(bound.num(CLEAN_AMOUNT).asNumber().gt(100)))
            .code(code).severity(Severity.ERROR).message(Dsl.en(code)).build()

    private val rules = listOf(
        rule("CLEANLEAF", CLEAN),
        rule("ERRORLEAF", ERRORED),
        rule("MIXEDLEAF", MIXED),
        valueTypedRule("VCLEANLEAF", CLEAN),
        valueTypedRule("VERRORLEAF", ERRORED),
        valueTypedRule("VMIXEDLEAF", MIXED),
    )

    private val modelJson = rules.fold(base) { json, r -> TypedRules.inject(json, r) }
    private val definition = ModelLoader.load(modelJson)
    private val staticEngine by lazy { RuntimeLaws.kernelJavaEngine(modelJson) }

    @Test
    fun theProbeModelIsAdmitted() =
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(modelJson),
            "a refused model would make every type reading below vacuous")

    /**
     * **The instrument's own control, and the result of this file.** Within each family the three groups must
     * report DIFFERENT types for the polarity channel to see the leaf at all. They do not — every member of a
     * family types identically — while the two families differ from each other, which is what proves the channel
     * is live rather than saturated. A fired `Or` carries its true disjunct's polarity; the group leaf
     * contributes nothing observable.
     *
     * <p>What this settles for `SPEC-2026-09-02-03`, stated at exactly its measured width: **the polarity of a
     * rule fired by another disjunct does not see the leaf**, on both omission- and value-typed disjuncts. It
     * does not establish that no channel anywhere can; a `Having` predicate carrying the leaf, where an unknown
     * filter could make an aggregate unavailable while a false one merely excludes a row, is a live candidate
     * this file does not probe. Pending such a witness the two accounts are indistinguishable here and the
     * choice is free on both sides, the disposition `IF316` records for a different operator.
     *
     * <p>Asserted as an exact per-code map rather than described, so a kernel upgrade that made the polarity
     * leaf-sensitive fails here and reopens the question.
     */
    @Test
    fun thePolarityChannelCannotSeeTheLeafSoTheAccountsStayIndistinguishable() {
        val types = triEngineTypes()
        assertEquals(PROBES, types.keys,
            "all six rules must fire, or the polarity reading has nothing to read")
        // Pinned per code rather than as "they are all equal": an all-equal assertion would also hold if the
        // typing channel collapsed everything for an unrelated reason, and the VALUE the three agree on is what
        // a reader needs to see the leaf contributing nothing.
        assertEquals(
            mapOf(
                "CLEANLEAF" to "OMISSION_ERROR", "ERRORLEAF" to "OMISSION_ERROR", "MIXEDLEAF" to "OMISSION_ERROR",
                "VCLEANLEAF" to "VALUE_ERROR", "VERRORLEAF" to "VALUE_ERROR", "VMIXEDLEAF" to "VALUE_ERROR",
            ),
            types,
            "within each family the control pair must differ for the channel to see the leaf; the two families " +
                "differ from each other, which is what shows the typing is reading the OTHER disjunct",
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
            .filter { it.code() !in PROBES }
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
        val probes = PROBES
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

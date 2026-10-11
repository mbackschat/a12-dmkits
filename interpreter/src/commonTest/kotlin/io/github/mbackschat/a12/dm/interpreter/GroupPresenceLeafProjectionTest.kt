package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.ast.Tri
import io.github.mbackschat.a12.dm.interpreter.eval.EvalConfig
import io.github.mbackschat.a12.dm.interpreter.eval.GroupPresenceState
import io.github.mbackschat.a12.dm.interpreter.eval.GroupRelevance
import io.github.mbackschat.a12.dm.interpreter.eval.Interpreter
import io.github.mbackschat.a12.dm.interpreter.eval.SingleRowDoc
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.interpreter.parse.parseCondition
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform lock for the two group-presence projections — the tally's [GroupPresenceState.fillProduct] and
 * the scalar `GroupNotFilled` leaf [GroupPresenceState.negativeLeaf] — over the COMPLETE
 * `filled × erroneous × relevance` state space.
 *
 * **Why this lock is a unit test rather than a differential.** The negative leaf is not observable through any
 * channel either estate can reach. Rule firing cannot see it: the condition language has no negation combinator
 * and Kleene `And`/`Or` are monotone, so a leaf moving between false and unknown can never turn a silent rule
 * into a firing one. Message polarity cannot: measured on both codegen strategies and pinned by
 * `adapter.laws.GroupNotFilledErroneousLeafDiffTest` (KF233). A `Having` predicate carrying the leaf cannot
 * either, which was the last live candidate: a conjunct that is not true drops its own row and leaves the
 * aggregate available at its ordinary value, so the filter position collapses false and unknown exactly as the
 * other two do (KF234, `adapter.laws.HavingNonTrueRowDiffTest`). There is therefore no kernel oracle to write a
 * differential against, and the honest lock is over the projection itself.
 *
 * **What it pins.** The aligned cell is `filled=true, erroneous=true`: admitted content decides the leaf FALSE
 * before the independent error is considered. Its two neighbours are the controls — a content-free erroneous
 * group stays UNKNOWN, and a clean filled group was already FALSE — so an implementation that simply ignored
 * the error bit would fail the first, and one that tested it first would fail the aligned cell. The alignment
 * is a12-kernel-lean's `SPEC-2026-09-02-03`, taken on the owner's 2026-09-03 decision as a coherence choice:
 * the leaf and the GROUP-fill tally now project one presence notion instead of two.
 */
class GroupPresenceLeafProjectionTest {

    @Test
    fun descendantCoverageMatchesEveryConcreteTuple() {
        val parent = "/Shipment/Assessment"
        val packages = "$parent/Packages"
        val items = "$packages/Items"
        val quantity = "$items/Quantity"
        val reference = "/Shipment/Reference"
        val patterns = (0..2).flatMap { p -> (0..2).map { i -> p to i } }
        for (bounded in listOf(true, false)) {
            val model = ModelDef(
                fields = listOf(FieldDef(reference, Kind.STRING), FieldDef("$parent/Score", Kind.NUMBER), FieldDef(quantity, Kind.NUMBER)),
                rules = listOf(RuleDef("/Shipment/Coverage", "/Shipment", "FieldFilled(Reference) And GroupNotFilled(Assessment)", "EMPTY", "../Reference")),
                repeatableGroups = setOf(packages, items),
                declaredReps = if (bounded) mapOf(packages to 2, items to 2) else emptyMap(),
            )
            val interpreter = DmInterpreter(model)
            val document = model.newDocument().group("/Shipment", 1).field(reference, "ready", 1, 1)
            val direct = setOf(RelevantEntity.anyRep(reference), RelevantEntity.anyRep("$parent/Score"))
            val concreteProduct = patterns.indices.filter { patterns[it].first > 0 && patterns[it].second > 0 }.sumOf { 1 shl it }
            for (mask in if (bounded) 0 until (1 shl patterns.size) else listOf(1, concreteProduct)) {
                val selected = patterns.filterIndexed { index, _ -> mask and (1 shl index) != 0 }
                val complete = if (bounded) (1..2).all { p -> (1..2).all { i ->
                    selected.any { (a, b) -> (a == 0 || a == p) && (b == 0 || b == i) }
                } } else selected.any { it == (0 to 0) }
                val relevant = direct + selected.map { (p, i) -> RelevantEntity(quantity, listOf(0, 0, p, i, 0)) }
                val report = interpreter.validatePart(document, relevant)
                assertEquals(if (complete) 1 else 0, report.messages.count { it.code == "EMPTY" }, "bounded=$bounded/subset=$mask")
            }
        }
    }

    private val CASE = "/Case"
    private val ANCHOR = "/Case/Anchor"
    private val NOT_FILLED = "GroupNotFilled(Mixed)"
    private val FILLED = "GroupFilled(Mixed)"

    private fun state(filled: Boolean, erroneous: Boolean, relevance: GroupRelevance) =
        GroupPresenceState(filled = filled, erroneous = erroneous, relevance = relevance)

    /** `filled|erroneous|relevance` → `fillProduct/negativeLeaf`, for every cell of the state space. */
    private fun table(): Map<String, String> = buildMap {
        for (filled in listOf(true, false)) {
            for (erroneous in listOf(true, false)) {
                for (relevance in GroupRelevance.entries) {
                    val s = state(filled, erroneous, relevance)
                    put("$filled|$erroneous|$relevance", "${s.fillProduct}/${s.negativeLeaf}")
                }
            }
        }
    }

    /**
     * The complete table, asserted as an exact map rather than described. Read the `true|true|FULL` and
     * `true|true|PARTIAL` rows: those are the alignment, and they are what a reimplementation testing the error
     * bit before positive presence gets wrong.
     */
    @Test
    fun theTwoProjectionsCoverTheStateSpaceExactly() = assertEquals(
        mapOf(
            // admitted content decides, error bit or not — the aligned cell and its clean twin
            "true|true|FULL" to "TRUE/FALSE",
            "true|true|PARTIAL" to "TRUE/FALSE",
            "true|false|FULL" to "TRUE/FALSE",
            "true|false|PARTIAL" to "TRUE/FALSE",
            // no admitted content: only a fully relevant, error-free group is definitely empty
            "false|false|FULL" to "FALSE/TRUE",
            "false|false|PARTIAL" to "UNKNOWN/UNKNOWN",
            // a content-free erroneous group stays undecided — the control the alignment must not move
            "false|true|FULL" to "UNKNOWN/UNKNOWN",
            "false|true|PARTIAL" to "UNKNOWN/UNKNOWN",
            // an irrelevant group is undecided whatever it holds
            "true|true|NONE" to "UNKNOWN/UNKNOWN",
            "true|false|NONE" to "UNKNOWN/UNKNOWN",
            "false|true|NONE" to "UNKNOWN/UNKNOWN",
            "false|false|NONE" to "UNKNOWN/UNKNOWN",
        ),
        table(),
    )

    /**
     * The projection above is only worth locking if the EVALUATOR reads it, and until 2026-09-03 nothing checked
     * that it did — a cold review's finding. Reverting `evalPred`'s `GROUP_NOT_FILLED` branch to its old inline
     * form was caught by no test in the estate: this class exercised the data class alone, no kernel channel can
     * see the leaf, and the differential that probes the polarity channel is deliberately insensitive to it.
     *
     * <p>The seam is [EvalConfig.groupPresence], which lets a test state the presence directly and read the
     * leaf's own [Tri] out of [Interpreter.eval] — the only surface on which this branch is observable at all.
     * The three states are the aligned cell and its two neighbours; `GroupFilled` rides along on the same
     * states, since the alignment must leave it alone.
     */
    private fun leaf(condition: String, filled: Boolean, erroneous: Boolean): Tri =
        Interpreter(
            SingleRowDoc(CASE, mapOf(ANCHOR to "x"), mapOf(ANCHOR to Kind.STRING)),
            EvalConfig(groupPresence = { _, _ ->
                GroupPresenceState(filled = filled, erroneous = erroneous, relevance = GroupRelevance.FULL)
            }),
        ).eval(parseCondition(condition, CASE))

    @Test
    fun theEvaluatorReadsTheProjectionForBothGroupPresenceLeaves() = assertEquals(
        mapOf(
            // the aligned cell: admitted content decides FALSE even though the group also carries an error
            "GroupNotFilled|content+error" to Tri.FALSE,
            "GroupNotFilled|error only" to Tri.UNKNOWN,
            "GroupNotFilled|clean and empty" to Tri.TRUE,
            // unchanged by the alignment, and asserted here so a "make it consistent" edit cannot move it
            "GroupFilled|content+error" to Tri.TRUE,
            "GroupFilled|error only" to Tri.FALSE,
            "GroupFilled|clean and empty" to Tri.FALSE,
        ),
        mapOf(
            "GroupNotFilled|content+error" to leaf(NOT_FILLED, filled = true, erroneous = true),
            "GroupNotFilled|error only" to leaf(NOT_FILLED, filled = false, erroneous = true),
            "GroupNotFilled|clean and empty" to leaf(NOT_FILLED, filled = false, erroneous = false),
            "GroupFilled|content+error" to leaf(FILLED, filled = true, erroneous = true),
            "GroupFilled|error only" to leaf(FILLED, filled = false, erroneous = true),
            "GroupFilled|clean and empty" to leaf(FILLED, filled = false, erroneous = false),
        ),
        "the evaluator must read the aligned projection, and must not have moved GroupFilled while doing so",
    )

    /** The leaf is the product's Kleene complement by construction; pinned so a later edit cannot split them. */
    @Test
    fun theNegativeLeafIsTheProductsKleeneComplement() {
        for ((cell, projections) in table()) {
            val (product, leaf) = projections.split("/")
            val complement = when (Tri.valueOf(product)) {
                Tri.TRUE -> Tri.FALSE
                Tri.FALSE -> Tri.TRUE
                Tri.UNKNOWN -> Tri.UNKNOWN
            }
            assertEquals(complement.name, leaf, "cell $cell")
        }
    }
}

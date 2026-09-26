package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.mbackschat.a12.dm.consistency.FindingCategory
import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.core.ModelConsistencyException
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * On a model A12 REJECTS, every A12 class the checker names must be one A12 named — at the DECLARATION A12
 * named it about.
 *
 * **This is the half the silence sweeps cannot reach, and it catches a different defect.**
 * [CheckerSilenceOnCleanModelsTest] and [CheckerSilenceOnMutatedModelsTest] both measure models A12 ACCEPTS,
 * so a checker that refuses nothing legal passes them however wrongly it classifies what it does refuse.
 * Naming the wrong class on a correctly rejected model is not an over-rejection — but it hands the author a
 * fix for a different gate, which is the same cost one step later.
 *
 * **It exists because the defect was real and recent.** On 2026-09-14 the reference family named
 * `MVK_ERROR_FIELD_NOT_REFERENCED` on seven committed models where A12 names `MVK_NO_WILDCARD`
 * ([KERNEL-FINDINGS KF350]), and it was found by exactly this comparison run by hand. A found defect whose
 * instrument is then thrown away is a defect class left open.
 *
 * **The population is mutants A12 rejects WITH a keyed class.** A12 answers some invalid models by refusing
 * to build them at all, reporting no class — the comparison is undefined there, so those are excluded rather
 * than counted as agreement. The mutations are chosen to reach the CONDITION gates, because damaging a
 * declaration mostly makes a model unbuildable and lands in that excluded tier — measured, not assumed: a
 * kind that removes a group's repetitions drew a keyed class from A12 **zero** times over the whole
 * estate and was dropped, which is what the per-kind assertion exists to catch.
 *
 * **The error MESSAGE is a second position A12 classifies, and it was outside this population until
 * 2026-09-15.** Every kind aimed at a declaration or a condition leaves the message untouched, so the wrong
 * class that stood longest in this estate — the rule-group shorthand reported as a reference failure — could
 * not be reached by any of them. The three message kinds also reach far more of the estate, because a rule
 * with a message is nearly every model while a rule whose message writes a PARAMETER is four of forty-three:
 * 116 mutants compare, up from 32 (measured 2026-09-15), and none is refused without a keyed class.
 *
 * **A12 masking one class behind another is not automatically a defect, which is why [masked] exists.** A12
 * stops at the first gate that answers; the checker accumulates. A class the checker reports and A12 does not
 * may be a true second defect A12 has not reached — or a wrong class. Only measurement separates them, so a
 * new pair fails this test and is dispositioned by hand rather than allowed through.
 *
 * **The comparison is keyed by DECLARATION, which is what makes the right class in the wrong place visible.**
 * A12 prefixes every keyed notification of this population with the declaration it judged — measured
 * 2026-09-15 as `<Kind> with path '<path>': … [KEY]` on all 116, every one of them keyed, every one of them
 * prefixed and every one naming a `Rule`; the earlier assumption that the prefix carries an element ID was
 * wrong, so no id→path map is needed and the paths compare directly against a finding's own `at`. A class-only
 * comparison passes a checker that names A12's own class about a declaration A12 said nothing about, and on
 * this population it cannot even see a SECOND class, because A12 answers each of these mutants with exactly
 * one notification (116 of 116) — so `ours ⊆ kernel` already forces a one-element set and the count invariant
 * the F2 record asked for is only meaningful once the key is the declaration.
 *
 * **Only declarations A12 keyed are compared, and that restriction is the honest half.** A12 stops at its
 * first answering gate, so its silence about every OTHER declaration of the mutant is not evidence: comparing
 * there would refuse honest content the moment A12's stop point moved. What the restriction costs is a
 * finding whose class A12 named about a declaration A12 never reached, which is why the classes A12 named are
 * compared as (declaration, class) PAIRS rather than per declaration — a class A12 named is then wrong
 * wherever A12 did not name it, and a genuine second instance A12 masked is a row in [positionsA12Masks].
 *
 * **The checker is silent on NONE of the 116 as of 2026-09-15, down from 16 of 98 the same day.** Silence was
 * always the safe direction and was disclosed rather than asserted; now there is nothing to disclose, and
 * [SILENT_CLASSES] guards the floor from the other side by being empty. The [positionedFloor] still fails if
 * silence returns, because a compared pair needs both sides to speak.
 *
 * **Both directions are asserted as of 2026-09-16, where only `ours ⊆ kernel` was before.** The reverse is
 * [unreached], and the reason it was reachable at all is that it is scoped to declarations A12 KEYED: A12
 * stops at its first answering gate, so what it would say about a declaration it never reached needs an
 * accumulation contract this lane deliberately does not have — but where A12 did speak, it said everything
 * it had to say there, and no contract is needed to compare against that. The residual is therefore precisely
 * "what A12 would name about declarations it never reached", and nothing here claims it.
 *
 * It was not a formality: the first run found a real under-report the subset direction cannot see by
 * construction, and with it a live false claim in the MVK ledger's `MVK_MISSING_ERROR_FIELD` row, which had
 * the checker drawing that code for an unresolvable error target when it draws it for a BLANK member only —
 * a sentence contradicting the one above it in its own row.
 */
class CheckerClassAgreementTest {

    /**
     * Pairs measured to be A12 MASKING a class the checker legitimately reports — not defects.
     *
     * Empty on purpose as of 2026-09-14: the one pair this test was built from turned out to be a defect in
     * the checker, and fixing it emptied the map. A row here must name the model, both classes, and the
     * measurement that says the checker's class is true of the model.
     */
    private val masked = emptySet<String>()

    /**
     * Rows measured to be a class A12 named about ONE declaration that is genuinely also true of another.
     *
     * Empty as of 2026-09-15: every one of the checker's 116 positioned findings over this population landed
     * at the declaration A12 keyed. A row here must name the model, the mutation, both declarations, and the
     * measurement that says the checker's placement is true of the model — not merely plausible.
     */
    private val positionsA12Masks = emptySet<String>()

    /**
     * (declaration, class-pair) rows measured to be a TRUE second defect rather than an over-report.
     *
     * Empty as of 2026-09-15. Two families report a second class deliberately — `REPETITION_NOT_UNIQUE_
     * COMBINATION` and `H_MODEL_PATH_SUFFIX`, where A12 stops at an earlier gate — so the invariant is not
     * blanket and a row here is the way to say so, with the measurement that says A12 draws the second class
     * once the first is repaired.
     *
     * **A pair A12 names ITSELF at the declaration needs no row**, since the run has just measured it; the
     * assertion exempts it rather than enumerating it per model. That became reachable on 2026-09-24, when a
     * mutation first emptied a rule's messages beside a refused condition and A12 answered with both.
     *
     * **With that exemption this assertion adds nothing while [masked] and [positionsA12Masks] are empty, and
     * saying so is the point.** A pair it refuses holds a class A12 did not name at that declaration, and such a
     * class is already a row of the disagreement assertion (A12 named it nowhere) or of [misplaced] (A12 named
     * it elsewhere). So the redundancy is a property of the contract, not of the population. It stays because
     * each mask lets one class past those two assertions, and a masked class reported together with another is
     * the pair only this check sees. Two co-firing over-reports were nearly shipped on 2026-09-15, and BOTH
     * were caught by a family mirror rather than by this lane, because the corpus carries no model of either
     * shape. That is the honest reason this is insurance rather than the net that would have caught them.
     */
    private val coFiring = emptySet<String>()

    /**
     * (declaration, class) pairs A12 named that the checker does not name there — the MISSING direction.
     *
     * **Empty as of 2026-09-16, which is the first time this lane asserts both directions.** Until then it
     * asserted only `ours ⊆ kernel` and disclosed the reverse, because "the checker names everything A12
     * does" reads as a claim about A12's whole verdict and A12 stops at its first answering gate. The half
     * that does NOT need an accumulation contract is this one: where A12 SPOKE, it said everything it had to
     * say about that declaration, so a class it named there and the checker did not is a gap with no
     * accumulation question attached. What stays out of reach is only what A12 would have said about
     * declarations it never reached, and that is the residual the plan's row 7 still names.
     *
     * A row here is an under-report, which is the safe direction and must be disclosed rather than hidden —
     * so it carries the model, the mutation, the declaration, the class, and why no family reaches that
     * shape. Like [SILENT_CLASSES] the map moves both ways: a row leaving is a family gaining reach, a row
     * arriving is a family that lost it.
     *
     * It overlaps [SILENT_CLASSES] and is kept beside it deliberately: silence keyed by CLASS says which
     * family to widen and carries its mutants, while this says which (declaration, class) pair is missing
     * even on a mutant where the checker did speak — the case a per-mutant silence count cannot see.
     *
     * **Its positive control is the run that introduced it, not a synthetic mutant.** With this set empty the
     * sweep failed on one row and on nothing else, every other assertion staying green — which is the whole
     * claim, since that row is invisible to `ours ⊆ kernel` by construction. The row was an unresolvable error
     * target's `MVK_MISSING_ERROR_FIELD`, pinned here until the checker named A12's pair (2026-09-26).
     *
     * **What this check does NOT currently add over [misplaced], measured 2026-09-16 and stated rather than
     * assumed:** dropping the declaration from its key leaves the sweep green, because [misplaced] being
     * empty already forces every class of ours that A12 also named to sit where A12 named it. So the keying
     * is redundant *on this population*, exactly as [coFiring] is. It is keyed anyway for the same reason:
     * the redundancy is a property of the corpus, and the moment a row enters [positionsA12Masks] the two
     * stop agreeing and only the keyed form still refuses a class named about the wrong element.
     */
    private val unreached = emptySet<String>()

    /**
     * (declaration, class) pairs A12 would name on a TWO-defect model that the checker does not name there.
     *
     * Empty as of 2026-09-16. A row is an under-report on the accumulated verdict specifically, so it names
     * the model, both mutation kinds, the declaration, the class, and why no family reaches it.
     */
    private val accumulationUnreached = emptySet<String>()

    /**
     * Independent mutation PAIRS that must compare before a green on the accumulated verdict means anything.
     *
     * 23 compare as of 2026-09-16, and this sits well below that on purpose — like every other floor here it
     * answers *did the comparison happen*, not *how big is the population*. Set at the live count it would
     * fail on any corpus edit that removes a second rule from one model, which is an editorial change rather
     * than a regression in what this asserts.
     */
    private val pairFloor = 10

    /** The two declarations a pair's defects land on — different by construction, not by luck. */
    private val FIRST = 0
    private val SECOND = 1

    /** Mutants A12 must reject with a keyed class before a green here means anything. */
    private val floor = 20

    /**
     * (mutant, declaration) pairs where A12 keyed a class AND the checker names something there.
     *
     * A pair needs both sides to speak, so this is the assertion that fails if the checker goes quiet on
     * rejected models — the direction the silence sweeps cannot see, since they measure models A12 BUILDS.
     * 116 of 116 as of 2026-09-15 — every rejected mutant has something on both sides. The floor sits
     * well below that on purpose, because it answers *did the comparison happen* rather than *how big is
     * the population*.
     */
    private val positionedFloor = 60

    /**
     * The classes A12 names on a rejected mutant where the checker says nothing, with how often.
     *
     * **EMPTY as of 2026-09-15, and it was a work list that emptied itself in one sitting.** The lane
     * counted the silence — 16 of 98 — and a count cannot be acted on: it says a gap exists without saying
     * where. Keyed by class it said which family to widen. Now that it is empty its job reverses: any entry
     * ARRIVING is a family that LOST reach, which is a regression rather than a number nobody reads.
     *
     * Every one of the four it held was a class the checker ALREADY stated elsewhere, so none was a missing
     * family — each was a shape an existing family did not reach. That is why the silence direction turned
     * out cheaper than it looked: no new family was written to empty this map.
     *
     * `MVK_WILDCARDS_REQUIRED` ×7 and `MVK_PARAMSIZE_INVALIDN` ×2 came off the list on 2026-09-15, and how
     * they did is the method rather than the outcome. All seven of the first were one mutation kind on one
     * shape, and reproducing that shape on a shared fixture gave a DIFFERENT class, because every row of the
     * grid that owns it sat on a rule that does not iterate — a constant nobody had named. Whether the rule
     * iterates is what picks the class ([KERNEL-FINDINGS KF424]). The other two then fell to the same fact
     * one layer down: the shared reference reader asked whether the DECLARING group sits in a repetition,
     * where A12 asks about the target's coordinates, so nine families were yielding on operands A12 admits.
     * One pinned class turned out to name a defect in a helper none of its mutants pointed at.
     *
     * `MVK_NO_WILDCARD` ×4 followed, and the enumeration below is what made it cheap: all seven remaining
     * mutants came from ONE mutation kind, and the four differed from the three only in carrying the outer
     * marker — which the scope family used to read as a reason to skip the whole condition. It binds a filter
     * BODY's references, so the family now removes the body and reads what surrounds it; the two mirror rows
     * that had DISCLOSED this under-report in their own comments went green by gaining the class.
     *
     * `MVK_ERROR_FIELD_NOT_REFERENCED` ×3 was the last, and it took TWO facts, which is why lifting one of
     * them failed loudly first. A filter leaf must stop pre-empting that family's iteration test — measured
     * and correct on its own — and that alone made the family name a wrong class beside a right one, because
     * A12 answers a filtered PARALLEL operand with its own class ahead of the not-referenced gate. The gate
     * ORDER was already kernel-locked in a laws row nobody had connected to this map; adding the yield to it
     * is what closed them.
     *
     * Measured 2026-09-15.
     */
    private val SILENT_CLASSES = sortedMapOf<String, Int>()

    @Test
    fun everyClassTheCheckerNamesOnARejectedModelIsOneTheKernelNames() {
        var compared = 0
        var noKeyedClass = 0
        var positionedPairs = 0
        var silentOnRejection = 0
        val perKind = MUTATIONS.associate { it.label to 0 }.toMutableMap()
        val disagreements = mutableListOf<String>()
        val misplaced = mutableListOf<String>()
        val repeated = mutableListOf<String>()
        val unpositioned = mutableListOf<String>()
        val unparsed = mutableListOf<String>()
        val missed = mutableListOf<String>()
        val silentClasses = mutableMapOf<String, Int>()
        val silentRows = mutableListOf<String>()
        val coFired = mutableListOf<String>()
        for (model in committedModels()) {
            val text = model.readText()
            for (mutation in MUTATIONS) {
                val mutated = mutation.applyTo(text) ?: continue
                val kernel = kernelClassification(mutated) ?: continue
                val where = { note: String -> "${model.name}[${mutation.label}]: $note" }
                unparsed += kernel.unparsed.map { where("A12 keyed `$it` with no declaration prefix") }
                if (kernel.classes.isEmpty()) {
                    noKeyedClass++
                    continue
                }
                compared++
                perKind[mutation.label] = perKind.getValue(mutation.label) + 1
                val ours = check(ModelSource(mutated, diagnosticLabel = model.name)).findings
                    .filter { it.justifiesRejection && it.category == FindingCategory.A12_CONSTRAINT }
                if (ours.isEmpty()) {
                    silentOnRejection++
                    kernel.classes.forEach { silentClasses.merge(it, 1, Int::plus) }
                    silentRows += where("A12 said ${kernel.byDeclaration}")
                }
                (ours.map { it.code }.toSet() - kernel.classes).forEach { extra ->
                    val row = where("ours=$extra kernel=${kernel.classes}")
                    if (row !in masked) disagreements += row
                }
                ours.filter { it.at == null }.forEach { finding ->
                    unpositioned += where("${finding.code} names no declaration, so it cannot be compared")
                }
                val positioned = ours.filter { it.at != null }
                positioned.groupBy { it.at to it.code }.filterValues { it.size > 1 }.forEach { (key, found) ->
                    repeated += where("${found.size} × ${key.second} at ${key.first}")
                }
                positioned.groupBy { it.at }.filterValues { found ->
                    found.mapTo(mutableSetOf()) { it.code }.size > 1
                }.forEach { (at, found) ->
                    // A pair A12 draws ITSELF at that declaration is a true second defect, and this run is the
                    // measurement: a message with no text is refused beside the condition's class, on its
                    // prefixed presence class (`MessageParameterConditionDependencyLawsTest`, [KF338]).
                    if (kernel.byDeclaration[at]?.containsAll(found.map { it.code }) == true) return@forEach
                    val row = where("${found.map { it.code }.sorted()} together at $at")
                    if (row !in coFiring) coFired += row
                }
                // Only the classes A12 actually named: a class A12 names nowhere is the row above, and
                // reporting it twice would make one defect read as two.
                positioned.filter { it.code in kernel.classes }
                    .filterNot { kernel.byDeclaration[it.at]?.contains(it.code) == true }
                    .forEach { finding ->
                        val row = where(
                            "${finding.code} at ${finding.at}, where A12 named it at " +
                                kernel.byDeclaration.filterValues { finding.code in it }.keys.sorted(),
                        )
                        if (row !in positionsA12Masks) misplaced += row
                    }
                // The reverse of `disagreements`, and the only half of "names everything A12 does" that can
                // be asked without an accumulation contract: A12 keyed this declaration, so it said all it
                // had to say there.
                kernel.byDeclaration.forEach { (at, classes) ->
                    classes.filterNot { code -> positioned.any { it.at == at && it.code == code } }
                        .forEach { code ->
                            // What we DID say there rides along: widening a family starts from the class we
                            // named instead, and deriving it otherwise means a throwaway assertion here.
                            val instead = positioned.filter { it.at == at }.map { it.code }.sorted()
                            val row = where(
                                "A12 named $code at $at and no family of ours does" +
                                    if (instead.isEmpty()) " (we say nothing there)" else " (we say $instead)",
                            )
                            if (row !in unreached) missed += row
                        }
                }
                positionedPairs += kernel.byDeclaration.keys.count { at -> positioned.any { it.at == at } }
            }
        }

        assertEquals(
            SILENT_CLASSES, silentClasses.toSortedMap(),
            "the classes A12 named where the checker said nothing have moved. This map is the SILENCE " +
                "direction's work list, not a failure: a class leaving it is a family gaining reach and the " +
                "pin comes down with it, while a class ARRIVING is a family that lost reach and is a " +
                "regression. Re-derive before editing:\n" + silentClasses.toSortedMap() +
                // The MUTANTS, not just the classes. Widening a family starts by reading the shapes behind
                // one class, and deriving them used to mean adding a throwaway assertion to this test —
                // which is a diff waiting to be committed by accident. Carrying them in the message costs
                // nothing while green and is the whole input to the next slice.
                "\n\nthe silent mutants:\n" + silentRows.sorted().joinToString("\n"),
        )
        assertEquals(
            emptyList<String>(), coFired,
            "two DIFFERENT classes at one declaration of a model A12 rejects. A12 stops at the first gate " +
                "that answers, so a second positioned class is an over-report by construction unless it is " +
                "a true second defect — which is what [coFiring] is for, per entry, with its measurement. " +
                "Add a row there only after measuring that A12 would draw the second class on a model where " +
                "the first is repaired:\n" + coFired.joinToString("\n"),
        )
        assertEquals(
            emptyList<String>(), unparsed,
            "A12 keyed a class in a shape this comparison cannot read, so the declaration it judged was " +
                "silently dropped and the keying below degraded to a class-only check. Re-measure the " +
                "notification prefix and widen the parser rather than letting it fail open:\n" +
                unparsed.joinToString("\n") { "  $it" },
        )
        assertTrue(
            compared >= floor,
            "only $compared mutants drew a keyed class from A12 (floor $floor; $noKeyedClass were refused " +
                "without one), so a green here would be an empty comparison rather than an agreeing one",
        )
        assertTrue(
            positionedPairs >= positionedFloor,
            "only $positionedPairs (mutant, declaration) pairs had something on BOTH sides (floor " +
                "$positionedFloor), with the checker silent on $silentOnRejection of $compared rejected " +
                "mutants — so the positional half below compared almost nothing",
        )
        assertEquals(
            emptyList<String>(), perKind.filterValues { it == 0 }.keys.toList(),
            "a mutation kind drew no keyed class from A12, so it compares nothing and contributes only " +
                "runtime — fix the mutation rather than leaving it in: $perKind",
        )
        assertEquals(
            emptyList<String>(), unpositioned,
            "a finding that alone rejects the model named no declaration, so an author cannot act on it " +
                "and this comparison cannot place it. Give it the declared path it concerns:\n" +
                unpositioned.joinToString("\n") { "  $it" },
        )
        assertEquals(
            emptyList<String>(), repeated,
            "the checker reported one class twice about one declaration, which reads to an author as two " +
                "defects where A12 names one:\n" + repeated.joinToString("\n") { "  $it" },
        )
        assertEquals(
            emptyList<String>(), disagreements,
            "the checker named an A12 class on a rejected model that A12 itself did not name. Either the " +
                "class is wrong — name the defect A12 names — or A12 is MASKING a true second defect behind " +
                "an earlier gate, which is a row in `masked` with the measurement that says so:\n" +
                disagreements.joinToString("\n") { "  $it" },
        )
        assertEquals(
            emptyList<String>(), misplaced,
            "the checker named A12's own class about a declaration A12 named it about nowhere. The class " +
                "being right is what makes this invisible to the comparison above, and it still hands the " +
                "author the wrong element to fix — unless the second instance is genuinely true of the " +
                "model, which is a row in `positionsA12Masks` with its measurement:\n" +
                misplaced.joinToString("\n") { "  $it" },
        )
        assertEquals(
            emptyList<String>(), missed,
            "A12 named a class about a declaration and no family of ours names it there. This is the " +
                "MISSING direction and it is an under-report, so it is safe — but it must be DISCLOSED " +
                "rather than passed: add the family that reaches the shape, or a row in `unreached` saying " +
                "why none does. A12 keyed this declaration, so its early stop does not excuse the gap:\n" +
                missed.joinToString("\n") { "  $it" },
        )
    }

    /**
     * What A12 WOULD name if it did not stop — the accumulation contract, CONSTRUCTED rather than requested.
     *
     * **The blocker this answers.** The sweep above compares only where A12 spoke, because A12 stops at its
     * first answering gate and its silence about every other declaration is not evidence. That leaves "does
     * the checker name everything A12 would name" unasked, and it was recorded as needing an accumulation
     * contract A12 does not offer. It does not need one from A12: for two defects on DIFFERENT declarations,
     * what A12 would say about a model carrying both is what it says about each alone. So the accumulated
     * verdict is built from single-defect observations we already make, and the composed model is then the
     * only new thing measured.
     *
     * **Independence is checked, not assumed** — the pair is skipped unless A12 keys the two single-defect
     * models at disjoint declarations. Two mutations landing on one declaration interact: the union is then
     * a claim about a defect A12 may never have judged in that combination, which is precisely the inference
     * this test exists to avoid making.
     *
     * The assertion is one-directional in the safe sense: the checker must name at least what A12 would, and
     * naming more is what [everyClassTheCheckerNamesOnARejectedModelIsOneTheKernelNames] already refuses.
     */
    @Test
    fun everyClassA12WouldNameOnATwoDefectModelIsOneTheCheckerNamesThere() {
        val singles = mutableMapOf<String, KernelClassification?>()
        var pairs = 0
        var a12Stopped = 0
        var attempted = 0
        var uncomposable = 0
        var unrejected = 0
        var overlapping = 0
        val missed = mutableListOf<String>()
        for (model in committedModels()) {
            val text = model.readText()
            // Memoised per (model, kind): a pair only ever adds the COMPOSED model's classification, so the
            // sweep costs one kernel run per pair rather than three.
            fun single(mutation: Mutation, ordinal: Int): KernelClassification? =
                singles.getOrPut("${model.name}|${mutation.label}|$ordinal") {
                    mutation.applyTo(text, ordinal)?.let { kernelClassification(it) }
                }
            MUTATIONS.forEachIndexed { index, first ->
                MUTATIONS.drop(index + 1).forEach { second ->
                    attempted++
                    // The second mutation takes the NEXT declaration it can reach, so the two defects are on
                    // different rules by construction rather than by luck. `single` uses the same ordinals,
                    // or the union below would be built from defects the composed model does not carry.
                    val composed = first.applyTo(text, FIRST)?.let { second.applyTo(it, SECOND) }
                    val one = single(first, FIRST)
                    val other = single(second, SECOND)
                    if (composed == null) uncomposable++
                    else if (one == null || other == null || one.classes.isEmpty() || other.classes.isEmpty()) {
                        unrejected++
                    } else if (one.byDeclaration.keys.intersect(other.byDeclaration.keys).isNotEmpty()) {
                        overlapping++
                    }
                    if (composed != null && one != null && other != null &&
                        one.classes.isNotEmpty() && other.classes.isNotEmpty() &&
                        one.byDeclaration.keys.intersect(other.byDeclaration.keys).isEmpty()
                    ) {
                        pairs++
                        val would = one.pairs() + other.pairs()
                        if (kernelClassification(composed)?.pairs() != would) a12Stopped++
                        val ours = check(ModelSource(composed, diagnosticLabel = model.name)).findings
                            .filter { it.justifiesRejection && it.category == FindingCategory.A12_CONSTRAINT }
                            .mapTo(mutableSetOf()) { it.at to it.code }
                        (would - ours).forEach { (at, code) ->
                            val row = "${model.name}[${first.label}+${second.label}]: A12 would name $code " +
                                "at $at and no family of ours does"
                            if (row !in accumulationUnreached) missed += row
                        }
                    }
                }
            }
        }
        assertTrue(
            pairs >= pairFloor,
            "only $pairs independent mutation pairs compared (floor $pairFloor), so a green here would be an " +
                "empty accumulation check rather than an agreeing one. Attempted $attempted; rejected " +
                "$uncomposable uncomposable, $unrejected where a single mutant drew no keyed class, and " +
                "$overlapping where the two defects land on one declaration. A12 reported less than the " +
                "union on $a12Stopped of the pairs that compared",
        )
        // A12 reporting the FULL union is the measured fact that makes this a direct comparison instead of a
        // contract, so it is pinned rather than assumed. A non-zero here means the early stop has appeared
        // ACROSS declarations, and the union stops being what A12 would say — which invalidates the sweep's
        // premise rather than merely failing one of its rows.
        assertEquals(
            0, a12Stopped,
            "A12 reported less than the union of its own single-defect verdicts on $a12Stopped of $pairs " +
                "pairs whose defects sit on DIFFERENT declarations. Measured 2026-09-16 as 0 of 23: its stop " +
                "is intra-text ([KERNEL-FINDINGS KF340]) and does not hide a sibling declaration. If that has " +
                "changed, the accumulated verdict can no longer be built this way and this sweep must be " +
                "re-derived, not re-pinned",
        )
        assertEquals(
            emptyList<String>(), missed,
            "the checker does not name a class A12 WOULD name on a model carrying two independent defects. " +
                "A12 reported less than the union on $a12Stopped of $pairs pairs, which is the early stop " +
                "this contract exists to see past — so the gap below is not excused by it. Add the family " +
                "that reaches the shape, or a row in `accumulationUnreached` saying why none does:\n" +
                missed.joinToString("\n") { "  $it" },
        )
    }

    /** A12's verdict as (declaration, class) pairs, the shape both directions above compare in. */
    private fun KernelClassification.pairs(): Set<Pair<String?, String>> =
        byDeclaration.flatMap { (at, codes) -> codes.map { at to it } }.toSet()

    /**
     * What A12 says about a model, keyed by the declaration it says it about.
     *
     * @param unparsed classes A12 keyed whose declaration prefix this reader could not bind, held separately
     *     so a changed notification shape fails loudly instead of quietly emptying the keying.
     */
    private class KernelClassification(
        val byDeclaration: Map<String, Set<String>>,
        val unparsed: List<String>,
    ) {
        val classes: Set<String> = byDeclaration.values.flatten().toSet()
    }

    /** What A12 reports for a model, or null when it BUILDS the model (not in this population). */
    private fun kernelClassification(json: String): KernelClassification? = try {
        BoundModel.ofJson(json)
        null
    } catch (rejected: ModelConsistencyException) {
        val keyed = rejected.notifications().mapNotNull { notification ->
            val message = notification.message().trim()
            // An UNKEYED notification carries prose rather than a class, so it is not a comparable claim at
            // all; only a keyed one that hides its declaration is a parser failure. Measured 2026-09-15:
            // every one of the 116 over this population is keyed, prefixed, and about a `Rule`.
            CLASS_KEY.find(message)?.groupValues?.get(1)?.let { key ->
                KEYED_DECLARATION.find(message)?.groupValues?.get(2) to key
            }
        }
        KernelClassification(
            byDeclaration = keyed.filter { it.first != null }
                .groupBy({ it.first!! }, { it.second })
                .mapValues { (_, keys) -> keys.toSet() },
            unparsed = keyed.filter { it.first == null }.map { it.second },
        )
    } catch (raised: RuntimeException) {
        // A12 refusing by RAISING past every typed channel is a reporting defect of its own (KF259) and says
        // nothing about classification, so it is not this test's population either.
        null
    }

    /** One perturbation aimed at the CONDITION gates, where A12 reports classes rather than refusing to build. */
    private class Mutation(val label: String, private val mutate: (ObjectNode, Int) -> Boolean) {
        /**
         * Apply this perturbation to the [ordinal]-th declaration it can reach, or null when there is none.
         *
         * **The ordinal exists so two mutations can land on DIFFERENT declarations.** Every kind here picks
         * the first declaration that fits it, which is right for the one-defect population and makes a
         * two-defect one nearly impossible to build: measured 2026-09-16 over 645 attempted pairs, 355 could
         * not compose at all and 246 more put both defects on one declaration, leaving 5. Ordinal 0 is
         * exactly today's behaviour, so the one-defect population is unchanged.
         */
        fun applyTo(text: String, ordinal: Int = 0): String? {
            val root = runCatching { MAPPER.readTree(text) as? ObjectNode }.getOrNull() ?: return null
            return if (mutate(root, ordinal)) MAPPER.writeValueAsString(root) else null
        }
    }

    private companion object {
        private val MAPPER = ObjectMapper()

        /** A12's own class key, the terminal `[KEY]` every coded notification carries. */
        private val CLASS_KEY = Regex(".*\\[(\\w+)]$", RegexOption.DOT_MATCHES_ALL)

        /**
         * The declaration a keyed notification judges — A12's own leading `<Kind> with path '<path>':`.
         *
         * The kind word is captured and deliberately unused: a path identifies the declaration on its own,
         * and pinning the kind too would fail on a class A12 reports about a `Field` or a `Computation`
         * without measuring anything this comparison is about.
         */
        private val KEYED_DECLARATION = Regex("^(\\w+) with path '([^']*)':", RegexOption.DOT_MATCHES_ALL)

        /**
         * A starred name followed by one unstarred name: the two captures are the names, the star is dropped.
         * Letters rather than `\\w`, which is ASCII here and would split a German model's names mid-word.
         */
        private val STAR_BEFORE_A_NAME = Regex("([\\p{L}\\p{N}_]+)\\*/([\\p{L}\\p{N}_]+)(?![\\p{L}\\p{N}_*])")

        /** The declared NAME of the element whose `Group` body is [body], for building a relative path. */
        private fun nameOfGroupBody(root: ObjectNode, body: ObjectNode): String? {
            var found: String? = null
            fun walk(node: ObjectNode) {
                if (node["Group"] === body) {
                    found = node["name"]?.asText()
                }
                node.elements().forEach { child ->
                    when (child) {
                        is ObjectNode -> walk(child)
                        is ArrayNode -> child.forEach { if (it is ObjectNode) walk(it) }
                        else -> Unit
                    }
                }
            }
            walk(root)
            return found
        }

        private fun groupBodies(root: ObjectNode): List<ObjectNode> {
            val found = mutableListOf<ObjectNode>()
            fun walk(node: ObjectNode) {
                (node["Group"] as? ObjectNode)?.let { found += it }
                node.elements().forEach { child ->
                    when (child) {
                        is ObjectNode -> walk(child)
                        is ArrayNode -> child.forEach { if (it is ObjectNode) walk(it) }
                        else -> Unit
                    }
                }
            }
            walk(root)
            return found
        }

        /**
         * The first rule message text the model carries, as the entry that owns it.
         *
         * The message mutations all APPEND to an existing text rather than rewriting the declaration, which
         * is what keeps them one-variable: every declared element stays exactly as authored, so a class A12
         * draws on the mutant is the parameter's and the comparison is about the message families alone.
         */
        private fun ruleMessage(root: ObjectNode, ordinal: Int): ObjectNode? = groupBodies(root).asSequence()
            .mapNotNull { it["elements"] as? ArrayNode }
            .flatMap { elements -> elements.asSequence() }
            .filter { it["type"]?.asText() == "Rule" }
            .mapNotNull { (it["Rule"] as? ObjectNode)?.get("errorMessage") as? ArrayNode }
            .mapNotNull { it.firstOrNull() as? ObjectNode }
            .filter { it["text"]?.isTextual == true }
            .drop(ordinal)
            .firstOrNull()

        /** Moves the first star of the [ordinal]-th rule that has one onto the name after it; that rule, or null. */
        private fun moveAStar(root: ObjectNode, ordinal: Int): ObjectNode? {
            val rules = groupBodies(root).flatMap { body ->
                (body["elements"] as? ArrayNode)?.mapNotNull { element ->
                    (element["Rule"] as? ObjectNode)?.takeIf {
                        element["type"]?.asText() == "Rule" &&
                            STAR_BEFORE_A_NAME.containsMatchIn(it["errorCondition"]?.asText().orEmpty())
                    }
                }.orEmpty()
            }
            val rule = rules.getOrNull(ordinal) ?: return null
            rule.put("errorCondition", STAR_BEFORE_A_NAME.replaceFirst(rule["errorCondition"].asText(), "$1/$2*"))
            return rule
        }

        /** One perturbation of a message TEXT — the position no declaration-shaped mutation reaches. */
        private fun messageMutation(label: String, appended: String) = Mutation(label) { root, ordinal ->
            val message = ruleMessage(root, ordinal) ?: return@Mutation false
            message.put("text", message["text"].asText() + appended)
            true
        }

        private val MUTATIONS = listOf(
            // The mutation that found KF350: a target the condition cannot be naming, in the rule's own
            // group, leaving the condition untouched. It reaches the error-target gates, which is where the
            // checker and A12 have the most to disagree about.
            Mutation("repoint-error-field") { root, ordinal ->
                val group = groupBodies(root).firstOrNull { body ->
                    (body["elements"] as? ArrayNode)?.any { it["type"]?.asText() == "Rule" } == true
                } ?: return@Mutation false
                val elements = group["elements"] as ArrayNode
                val rule = (0 until elements.size())
                    .filter { elements[it]["type"]?.asText() == "Rule" }
                    .getOrNull(ordinal) ?: return@Mutation false
                // The injected name carries the ordinal, so composing two of these adds two DISTINCT fields.
                // Sharing one name would collide into a duplicate-child refusal, which is a third defect
                // neither mutation intended and would make the composed model unbuildable.
                val target = "MutantTarget$ordinal"
                val field = MAPPER.createObjectNode()
                field.put("type", "Field").put("id", "mutant-target-$ordinal").put("name", target)
                field.putObject("Field").putObject("fieldType").put("type", "StringType")
                elements.add(field)
                ((elements[rule] as ObjectNode)["Rule"] as ObjectNode)
                    .put("errorEntityRelPath", "../$target")
                true
            },
            // Repoint a rule's error field INTO a repeatable group, which makes the rule ITERATE. The kind
            // above only ever points OUT of one, so every mutant it produces sits on a non-iterating rule —
            // and whether the rule iterates is what picks the class an unstarred operand draws
            // ([KERNEL-FINDINGS KF424]). Two over-reports of that shape were nearly shipped on 2026-09-15
            // and both were caught by a family mirror, because no mutation kind could build the carrier.
            Mutation("repoint-error-field-into-a-repetition") { root, ordinal ->
                val repeatable = groupBodies(root).firstOrNull { body ->
                    body["repeatability"]?.asInt()?.let { it > 1 } == true &&
                        (body["elements"] as? ArrayNode)?.any { it["type"]?.asText() == "Field" } == true
                } ?: return@Mutation false
                val group = groupBodies(root).firstOrNull { body ->
                    (body["elements"] as? ArrayNode)?.any { it["type"]?.asText() == "Rule" } == true
                } ?: return@Mutation false
                val repeatableName = nameOfGroupBody(root, repeatable) ?: return@Mutation false
                val elements = group["elements"] as ArrayNode
                val rule = (0 until elements.size())
                    .filter { elements[it]["type"]?.asText() == "Rule" }
                    .getOrNull(ordinal) ?: return@Mutation false
                // Ordinal-suffixed for the same reason as the kind above: two of these must add two fields.
                val inner = "MutantInner$ordinal"
                val field = MAPPER.createObjectNode()
                field.put("type", "Field").put("id", "mutant-inner-$ordinal").put("name", inner)
                field.putObject("Field").putObject("fieldType").put("type", "StringType")
                (repeatable["elements"] as ArrayNode).add(field)
                ((elements[rule] as ObjectNode)["Rule"] as ObjectNode)
                    .put("errorEntityRelPath", "../$repeatableName/$inner")
                true
            },
            // Remove one star from a rule's condition. A legal aggregate read becomes an iterating per-row
            // read, which is precisely the gate KF350 turned on — so this is the mutation aimed at the class
            // of defect this test exists for, rather than at models in general.
            Mutation("unstar-a-condition") { root, ordinal ->
                val group = groupBodies(root).firstOrNull { body ->
                    (body["elements"] as? ArrayNode)?.any { element ->
                        element["type"]?.asText() == "Rule" &&
                            (element["Rule"] as? ObjectNode)?.get("errorCondition")?.asText()
                                ?.contains('*') == true
                    } == true
                } ?: return@Mutation false
                val elements = group["elements"] as ArrayNode
                val at = (0 until elements.size()).filter { position ->
                    elements[position]["type"]?.asText() == "Rule" &&
                        (elements[position]["Rule"] as? ObjectNode)?.get("errorCondition")?.asText()
                            ?.contains('*') == true
                }.getOrNull(ordinal) ?: return@Mutation false
                val rule = elements[at]["Rule"] as ObjectNode
                rule.put("errorCondition", rule["errorCondition"].asText().replaceFirst("*", ""))
                true
            },
            // Move a star off a level and onto the name after it, so the level stays bare and the star lands
            // where nothing repeats. A12 answers that with `MVK_INVALID_WILDCARD` alone ([KERNEL-FINDINGS
            // KF328]), while a family that asks only WHETHER a star is written, or how many, still judges the
            // read and speaks beside it — five did until 2026-09-24, each found by a hand-built row.
            Mutation("move-a-star-onto-the-next-name") { root, ordinal ->
                moveAStar(root, ordinal) != null
            },
            // …and break the SAME rule's message too. A12 examines a message only beside a sound condition
            // ([KERNEL-FINDINGS KF338]), so it names the star's class alone; the pair sweep below cannot see
            // this, because it composes defects on DIFFERENT declarations by construction.
            Mutation("move-a-star-and-unterminate-its-message") { root, ordinal ->
                val rule = moveAStar(root, ordinal) ?: return@Mutation false
                val message = (rule["errorMessage"] as? ArrayNode)?.firstOrNull() as? ObjectNode
                    ?: return@Mutation false
                message.put("text", message["text"].asText() + " ${'$'}Oops")
                true
            },
            // …and the two error-text classes that were drawn from outside the message families until 2026-09-24,
            // so the withholding above never reached them: a rule with no text draws `RULE_HAS_NO_ERRORTEXT`
            // beside `MVK_ERROR_MESSAGE_MISSING`, and a Base Year terminal in a model declaring none draws
            // `NO_BASE_YEAR`. A12 withholds both behind the star and keeps the prefixed presence class.
            Mutation("move-a-star-and-empty-its-messages") { root, ordinal ->
                val rule = moveAStar(root, ordinal) ?: return@Mutation false
                rule.putArray("errorMessage")
                true
            },
            // Only where the model declares no Base Year, so `NO_BASE_YEAR` is at stake in every mutant this kind
            // counts and the per-kind floor below cannot be met by mutants that withhold nothing.
            Mutation("move-a-star-and-name-the-base-year") { root, ordinal ->
                if (root.path("content").path("modelInfo").has("baseYear")) return@Mutation false
                val rule = moveAStar(root, ordinal) ?: return@Mutation false
                val message = (rule["errorMessage"] as? ArrayNode)?.firstOrNull() as? ObjectNode
                    ?: return@Mutation false
                message.put("text", message["text"].asText() + " ${'$'}BaseYear${'$'}")
                true
            },
            // The three below aim at the error MESSAGE, which no declaration-shaped mutation reaches — the
            // blind spot that let a wrong class stand in the name position for as long as the family existed
            // (`$RuleGroup$` reported as `INVALID_ENTITY`, fixed 2026-09-15). They also reach far more of the
            // estate than the condition kinds do: every model carrying a rule with a message, rather than the
            // four that happen to write a parameter.
            messageMutation("name-an-unresolvable-parameter", " ${'$'}MutantParameter${'$'}"),
            messageMutation("unterminate-a-message", " ${'$'}Oops"),
            // The rule-group shorthand is a TERMINAL A12 recognises and refuses in this position, and only in
            // its own language — so this kind compares nothing on a German-condition model, which is what the
            // per-kind assertion tolerates as long as some model reaches it.
            messageMutation("name-the-rule-group-shorthand", " ${'$'}RuleGroup${'$'}"),
        )

        private fun committedModels(): List<Path> {
            val root = CorpusFiles.repoRoot()
            return listOf(
                root.resolve("corpus/models"),
                root.resolve("cli/src/test/resources/models"),
                root.resolve("src/test/resources/models"),
            ).filter { Files.isDirectory(it) }.flatMap { dir ->
                Files.list(dir).use { stream ->
                    stream.filter { Files.isRegularFile(it) && it.name.endsWith(".json") }.sorted().toList()
                }
            }
        }
    }
}

package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.FieldKind
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Tri-engine differential — does the declared-capacity projection reach `NumberOfValueInFields` over a **plain
 * starred STRING, BOOLEAN, and CONFIRM field**? a12-kernel-lean's `SPEC-2026-09-01-03`, `SPEC-2026-09-01-04` and
 * `SPEC-2026-09-02-01`, reproduced here on an independently authored carrier.
 *
 * **Why this is not covered by the Number row we already hold.** `GroupOperandOverLimitExtentDiffTest` measures
 * the same operator over a starred field, but its carrier is a scale-0 NUMBER throughout. Reusing that result on
 * a String, Boolean, or Confirm declaration would be a scope transfer across the carrier boundary — the class of
 * silent reuse CLAUDE.md's oracle table forbids and which this project has been burned by three times on this
 * very operator. Each kind therefore gets its own document rows.
 *
 * **Three documents, one model, and every row reads an exact count.** A count that is *unavailable* leaves both
 * `> 0` and `< 1` silent, so a fired/silent read cannot separate `unknown` from `0`. Each carrier therefore
 * carries a LADDER of exact equalities `== 0 … == 3` and the fired member names the value; none firing means the
 * carrier could not evaluate. The assertion compares the COMPLETE fired set, so a count outside the ladder fails
 * with the observed set printed rather than passing as an evaluable carrier.
 *
 * **The sibling field in every group is the fixture control, not decoration.** Each operand group declares two
 * fields and the star names exactly one of them; the unnamed sibling is filled in every row. So an evaluator
 * that read the star as the group's whole subtree — the account a one-field-per-group fixture cannot separate
 * from the correct one, which is precisely how a12-kernel-lean's `LF114` shipped — answers a different ladder
 * member and the exact-set assertion fails.
 *
 * <p><b>Which carrier catches it, stated because it is not all of them.</b> `Reviews` holds one sibling value
 * (`Escalated = false`) and serves two carriers with opposite constants, so `BFALSE` is the one that separates
 * the accounts there (4 / 3 / 2 against 2 / 1 / 0) while `BTRUE` reads the same either way. `STR` and `CONF`
 * separate them on their own groups. A cold review found the first version of this paragraph claiming every
 * carrier discriminates (2026-09-03).
 *
 * **What the rows predicted and what they merely record.** The COUNT per carrier per document is the account
 * under test and was fixed before the first run. The message TYPE beside it (the kernel's omission-vs-value
 * typing) is reported by the same channel and is pinned as measured, not predicted; it is carried because
 * a12-kernel-lean's entries report the polarity and a reproduction that dropped it would be narrower than the
 * claim it answers.
 *
 * Own domain (insurance claim handling).
 */
// One instance per CLASS: these files hold their model JSON, the loaded definition and a kernel Java
// engine as fields, and this Gradle task also carries a clock-reading assertion. Per-method lifecycle
// would redo codegen and model loading on every case for no gain (CLAUDE.md: a new test must not
// perturb a timing floor sharing its task).
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StarredFieldValueCountCarrierCapacityDiffTest {

    private companion object {
        const val CLAIM = "/Claim"
        const val ADJUSTER = "/Claim/Adjuster"
        const val CATEGORIES = "/Claim/Categories"
        const val LABEL = "/Claim/Categories/Label"
        const val NOTE = "/Claim/Categories/Note"
        const val REVIEWS = "/Claim/Reviews"
        const val APPROVED = "/Claim/Reviews/Approved"
        const val ESCALATED = "/Claim/Reviews/Escalated"
        const val CONSENTS = "/Claim/Consents"
        const val WAIVER = "/Claim/Consents/Waiver"
        const val DISCLOSURE = "/Claim/Consents/Disclosure"

        /** The match the String carrier counts — and the value its unnamed sibling always holds. */
        const val MATCH = "SALVAGE"
        const val NON_MATCH = "TOTAL-LOSS"

        const val ADJUSTER_NAME = "M. Fischer"

        /** Capacity is two on every operand group, so row 3 is the over-limit row everywhere. */
        const val CAPACITY = 2
        val LADDER = 0..3

        /** The kernel's omission-vs-value message typing, spelled once for both engines and the interpreter. */
        const val OMISSION = "OMISSION"
        const val VALUE = "VALUE"

        /** The over-repetition findings — the structural channel that must stay lit on an over-limit document. */
        val OVER_REPETITION_CODES = setOf("zuGrosseZeile", "zuGrosseKontextnummer")

        fun typeName(kernelType: String) = if (kernelType == "OMISSION_ERROR") OMISSION else VALUE
    }

    private val base = DocumentModelDraft.named("claim-value-count-capacity").locale("en_US")
        .rootGroup("Claim") { claim ->
            claim.stringField("Adjuster") { }
            claim.group("Categories") { g ->
                g.repeatable(CAPACITY)
                g.stringField("Label") { }
                g.stringField("Note") { }
            }
            claim.group("Reviews") { g ->
                g.repeatable(CAPACITY)
                g.field("Approved", FieldKind.BOOLEAN)
                g.field("Escalated", FieldKind.BOOLEAN)
            }
            claim.group("Consents") { g ->
                g.repeatable(CAPACITY)
                g.field("Waiver", FieldKind.CONFIRM)
                g.field("Disclosure", FieldKind.CONFIRM)
            }
        }.build().toJson()

    private val bound = BoundModel.ofJson(base)

    /**
     * The four carriers. `True` admits a Boolean and a Confirm declaration and `False` admits a Boolean only, so
     * the Boolean field carries both constants and the Confirm field carries the one it is legal under.
     */
    private val carriers = linkedMapOf<String, (Int) -> Condition>(
        "STR" to { n -> Dsl.numberOfValueInFields(MATCH, bound.flatten("$CATEGORIES*/Label")).eq(n.toLong()) },
        "BTRUE" to { n -> Dsl.numberOfValueInFields(true, bound.flatten("$REVIEWS*/Approved")).eq(n.toLong()) },
        "BFALSE" to { n -> Dsl.numberOfValueInFields(false, bound.flatten("$REVIEWS*/Approved")).eq(n.toLong()) },
        "CONF" to { n -> Dsl.numberOfValueInFields(true, bound.flatten("$CONSENTS*/Waiver")).eq(n.toLong()) },
    )

    private val rules: List<Rule> = carriers.flatMap { (carrier, condition) ->
        LADDER.map { n -> rule("$carrier$n", condition(n)) }
    }

    /**
     * The error field is conjoined into every condition because the kernel requires it —
     * `MVK_ERROR_FIELD_NOT_REFERENCED` refuses a rule whose error field its condition never reads, measured on
     * the first run of this file. `Adjuster` is filled in every document below, so the conjunct is constantly
     * true and the count is what decides each rule; were it ever empty the whole condition would go unknown and
     * every ladder member would read as an unevaluable carrier.
     */
    private fun rule(code: String, condition: Condition): Rule =
        bound.rule(bound.group(CLAIM)).name("${code}Rule").errorField(bound.str(ADJUSTER))
            .`when`(bound.field(ADJUSTER).filled().and(condition))
            .code(code).severity(Severity.ERROR).message(Dsl.en(code)).build()

    private val modelJson = rules.fold(base) { json, rule -> TypedRules.inject(json, rule) }
    private val definition = ModelLoader.load(modelJson)
    private val staticEngine by lazy { RuntimeLaws.kernelJavaEngine(modelJson) }

    /**
     * The kernel must ADMIT this model, or every silent ladder member would be a rule that never ran rather than
     * a carrier's answer, and each `no member fired` reading would be unfalsifiable. Sixteen conditions are
     * spliced in as raw JSON downstream of every gate, so the admission is asserted rather than assumed.
     */
    @Test
    fun theProbeModelIsAdmittedSoASilentLadderMemberIsAnAnswer() {
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(modelJson),
            "a refused model makes every silent ladder member in this file unfalsifiable")
        assertEquals(16, rules.size, "four carriers × the four-member ladder")
    }

    // ---- the documents: one row spec per repeatable group, index 0 = row 1, `null` = row present, cell empty ----

    /**
     * Rows 1 and 2 hold no match and the sole match sits in over-limit row 3. This is the deciding document: the
     * declared-capacity account answers `0` on every carrier, a complete-instantiated-row account answers `1`.
     */
    private val matchOnlyOverLimit = Rows(
        label = listOf(NON_MATCH, NON_MATCH, MATCH),
        approved = listOf("false", "false", "true"),
        waiver = listOf(null, null, "true"),
    )

    /** The identical content one index lower — the control that keeps `0` from reading as "the operand sees nothing". */
    private val matchInCapacity = Rows(
        label = listOf(MATCH, NON_MATCH),
        approved = listOf("true", "false"),
        waiver = listOf("true", null),
    )

    /**
     * Both in-capacity rows match and over-limit row 3 holds the opposite value, so the second constant's ladder
     * is discriminating here too: `BFALSE` answers `0` under the projection and `1` without it.
     */
    private val bothInCapacityOppositeOverLimit = Rows(
        label = listOf(MATCH, MATCH, NON_MATCH),
        approved = listOf("true", "true", "false"),
        waiver = listOf("true", "true", null),
    )

    // ---- the measurements ----

    @Test
    fun aMatchInAnOverLimitRowIsCountedByNoCarrier() =
        assertCounts(
            matchOnlyOverLimit,
            mapOf("STR" to 0, "BTRUE" to 0, "BFALSE" to 2, "CONF" to 0),
            mapOf("STR" to VALUE, "BTRUE" to VALUE, "BFALSE" to VALUE, "CONF" to OMISSION),
            "the only match sits above capacity",
        )

    @Test
    fun theSameMatchOneIndexLowerIsCountedByEveryCarrier() =
        assertCounts(
            matchInCapacity,
            mapOf("STR" to 1, "BTRUE" to 1, "BFALSE" to 1, "CONF" to 1),
            mapOf("STR" to VALUE, "BTRUE" to VALUE, "BFALSE" to VALUE, "CONF" to OMISSION),
            "the in-capacity control",
        )

    @Test
    fun bothInCapacityRowsCountAndTheOppositeOverLimitRowDoesNot() =
        assertCounts(
            bothInCapacityOppositeOverLimit,
            mapOf("STR" to 2, "BTRUE" to 2, "BFALSE" to 0, "CONF" to 2),
            mapOf("STR" to VALUE, "BTRUE" to VALUE, "BFALSE" to VALUE, "CONF" to VALUE),
            "the over-limit row carries the opposite value, so the second constant discriminates too",
        )

    /**
     * The over-repetition finding is reported for the same documents whose counts it does not affect. Without
     * this, a fixture whose over-limit row was quietly *not* over limit would satisfy every count row above by
     * accident — the count would be right for the wrong reason and the file would lock nothing.
     */
    @Test
    fun theOverLimitRowsStructuralFindingStaysVisibleBesideTheUnaffectedCounts() {
        // Checked PER GROUP, not once for the document: a single `any` would be satisfied by one group's
        // finding while another group's row 3 sat quietly inside capacity, which is exactly the fixture mistake
        // this control exists to catch (tightened after a cold review, 2026-09-03).
        for (rows in listOf(matchOnlyOverLimit, bothInCapacityOppositeOverLimit)) {
            val lit = RuntimeLaws.messages(modelJson, kernelDocument(rows))
                .filter { it.code() in OVER_REPETITION_CODES }.map { it.field() }
            for (group in listOf(CATEGORIES, REVIEWS, CONSENTS)) {
                val name = group.substringAfterLast('/')
                assertTrue(lit.any { it.contains(name) },
                    "no over-repetition finding for $name in $lit — then its row 3 is not above capacity and " +
                        "that carrier's counts measure nothing about the projection")
            }
        }
    }

    // ---- the instrument ----

    /**
     * Runs one document through dynamic Groovy, generated Java, and the interpreter, and asserts the complete
     * `code:type` signature agrees before reading the counts off it. Comparing the whole set rather than one
     * carrier's members means a divergence in the accompanying structural findings cannot pass as agreement.
     */
    private fun assertCounts(
        rows: Rows,
        expectedCounts: Map<String, Int>,
        expectedTypes: Map<String, String>,
        label: String,
    ) {
        val expected = expectedCounts.map { (carrier, count) ->
            "$carrier$count:${expectedTypes.getValue(carrier)}"
        }.sorted()

        val kernelDocument = kernelDocument(rows)
        val dynamic = RuntimeLaws.messages(modelJson, kernelDocument)
            .filter { it.code() in ladderCodes }
            .map { "${it.code()}:${typeName(it.type().name)}" }.sorted()
        val static = staticEngine.messages(staticEngine.instance(kernelDocument(rows)))
            .filter { it.code() in ladderCodes }
            .map { "${it.code()}:${typeName(it.type().name)}" }.sorted()
        val interpreter = DmInterpreter(definition).validateFull(interpreterDocument(rows))
            .filter { it.code in ladderCodes }
            .map { "${it.code}:${if (it.type == MsgType.OMISSION) OMISSION else VALUE}" }.sorted()

        assertEquals(expected, dynamic, "$label — normative dynamic-Groovy")
        assertEquals(dynamic, static, "$label — generated Java")
        assertEquals(dynamic, interpreter, "$label — interpreter")
    }

    private val ladderCodes: Set<String> =
        carriers.keys.flatMap { carrier -> LADDER.map { "$carrier$it" } }.toSet()

    private fun kernelDocument(rows: Rows): RuntimeLaws.Doc {
        val d = RuntimeLaws.doc().group(CLAIM, 1).field(ADJUSTER, ADJUSTER_NAME, 1, 1)
        forEachRow(rows) { group, row, field, sibling, value, siblingValue ->
            d.group(group, 1, row)
            value?.let { d.field(field, it, 1, row, 1) }
            d.field(sibling, siblingValue, 1, row, 1)
        }
        return d
    }

    private fun interpreterDocument(rows: Rows): Document {
        val d = definition.newDocument().group(CLAIM, 1).field(ADJUSTER, ADJUSTER_NAME, 1, 1)
        forEachRow(rows) { group, row, field, sibling, value, siblingValue ->
            d.group(group, 1, row)
            value?.let { d.field(field, it, 1, row, 1) }
            d.field(sibling, siblingValue, 1, row, 1)
        }
        return d
    }

    /**
     * Walks one document spec, visiting every row of every operand group with the counted cell and its unnamed
     * sibling. The sibling's value is fixed here rather than passed in: it always holds the value the operand
     * hunts for, which is what makes a subtree misread land on a different ladder member.
     */
    private fun forEachRow(
        rows: Rows,
        visit: (group: String, row: Int, field: String, sibling: String,
            value: String?, siblingValue: String) -> Unit,
    ) {
        rows.label.forEachIndexed { i, v -> visit(CATEGORIES, i + 1, LABEL, NOTE, v, MATCH) }
        rows.approved.forEachIndexed { i, v -> visit(REVIEWS, i + 1, APPROVED, ESCALATED, v, "false") }
        rows.waiver.forEachIndexed { i, v -> visit(CONSENTS, i + 1, WAIVER, DISCLOSURE, v, "true") }
    }

    /**
     * One document's content, per operand group: the counted cell's value per row, where `null` means the row
     * exists (its sibling is filled) and the counted cell is absent.
     */
    private data class Rows(
        val label: List<String?>,
        val approved: List<String?>,
        val waiver: List<String?>,
    )
}

package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Differential + tri-check — a **`$`-OUTER-marked aggregate operand**, the shape whose reachability a cold review
 * had to establish twice.
 *
 * **Why this exists, and the correction it carries.** `aggOperandVals` decided an operand's presence with
 * `ctx.filled(path)` — the AMBIENT context — while the line beside it and `evalExpr(arg)` both resolve through
 * `ctxOf(arg)`, which returns the OUTER context for a `$`-marked node (`Interpreter.ctxOf`). So the fill test and
 * the value read consulted **different rows**. The sibling `starOperandVals` uses `ctxOf` consistently, which is
 * this class's "disagrees with a sibling" tell for the fifth time
 * ([IF287](../../../../../../../../../../../docs/INTERPRETER-FINDINGS.md#if287)).
 *
 * **It was nearly closed as unreachable, on a measurement that measured something else.** A first sweep put `$`
 * under `Sum` at the TOP LEVEL of a condition and read `MVK_INVALID_OUTER_ITERATION` as "an aggregate cannot carry
 * a `$` operand". The refusal is universal: `CheckEntitySpecImpl.checkOuter` fires on `ctx.outer != null &&
 * !isInFilterCondition()`, which names no carrier, and a bare `[$…] > 0` comparison with no aggregate anywhere
 * draws the identical code (`KF187`). `$` is **filter-only** — so the shape had to be probed inside a `Having`,
 * where it is admitted, and the closure was wrong.
 *
 * **The discriminator needs the two contexts to actually disagree**, which a root-authored rule cannot arrange: at
 * the root, `$X` and `X` address the same cell. The rule is therefore authored ON the repeatable group, so `$` is
 * the rule's own row and the plain reference is the filtered row, and the document fills the marked field in one
 * row but not the other.
 */
class OuterMarkedAggregateOperandDiffTest {

    private companion object {
        const val REF = "/Claim/Reference"
        const val LOTS = "/Claim/Lots"
        const val AMOUNT = "/Claim/Lots/Amount"
        const val SKU = "/Claim/Lots/Sku"
        const val BONUS = "/Claim/Lots/Bonus"
        const val EXTRA = "/Claim/Lots/Extra"
        const val EXTRAS = "/Claim/Extras"
        const val SPARE = "/Claim/Extras/Spare"
        const val RESERVE = "/Claim/Extras/Reserve"
        const val CODE = "OMAO"
    }

    private val base = DocumentModelDraft.named("claim-lots").locale("en_US")
        .rootGroup("Claim") { g ->
            g.stringField("Reference") { }
            g.group("Extras") { e ->
                e.numberField("Spare") { it.fractionDigits(0, 0) }
                    .numberField("Reserve") { it.fractionDigits(0, 0) }
            }
            g.group("Lots") { l ->
                l.repeatable(2)
                    .numberField("Amount") { it.fractionDigits(0, 0) }
                    .stringField("Sku") { }
                    .numberField("Bonus") { it.fractionDigits(0, 0) }
                    .numberField("Extra") { it.fractionDigits(0, 0) }
            }
        }.build().toJson()

    /**
     * The rule is authored ON `/Claim/Lots`, so it fires per row and `$` addresses the row being validated while
     * the unmarked reference inside the filter addresses the row being filtered.
     */
    private fun agreeFireRows(condition: String, expected: List<String>, msg: String, build: (Both) -> Unit) {
        val kModel = RuntimeLaws.withRule(base, LOTS, "R", "../Sku", CODE, condition)
        val def = ModelLoader.load(kModel)

        val dynamic = Both(RuntimeLaws.doc(), def.newDocument()).also(build)
        val kernelMessages = RuntimeLaws.messages(kModel, dynamic.kDoc)
        assertTrue(kernelMessages.map { it.code() }.none { it.startsWith("MVK_") }) {
            "$msg — the kernel REJECTED the shape " +
                "(${kernelMessages.map { it.code() }.filter { it.startsWith("MVK_") }}); that is a measurement " +
                "about admission, and it is what a first attempt at this file got wrong"
        }
        val groovy = kernelMessages.filter { it.code() == CODE }
            .map { "${it.field()}|${it.type().name}" }.sorted()

        val staticDoc = Both(RuntimeLaws.doc(), def.newDocument()).also(build).kDoc
        val javaStatic = RuntimeLaws.kernelJavaEngine(kModel)
            .messagesModern(staticDoc, null, Locale.US)
            .filter { it.code() == CODE }.map { "${it.field()}|${it.type().name}" }.sorted()

        val interpreter = DmInterpreter(def).validateFull(dynamic.iDoc).filter { it.code == CODE }
            .map { "${it.errorPath}|${if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR"}" }
            .sorted()

        assertEquals(expected, groovy, "$msg — the kernel groovy-dynamic rows")
        assertEquals(groovy, javaStatic, "$msg — groovy vs java-static (tri-check)")
        assertEquals(groovy, interpreter, "$msg — kernel vs INTERPRETER")
    }

    /**
     * Row 1's `Bonus`/`Extra` are EMPTY and row 2's are filled, so the `$`-marked operands are present when the
     * rule validates row 2 and absent when it validates row 1 — while the filter keeps row 1 in both cases. That
     * is what makes the ambient context and the outer context disagree: asking the FILTERED row about `Bonus`
     * answers "empty", asking the RULE's row answers "filled" on row 2.
     */
    private fun onlyTheSecondRowCarriesABonus(b: Both) {
        b.group("/Claim", 1).field(REF, "C-1", 1, 1)
            .group(LOTS, 1, 1)
            .field(AMOUNT, "10", 1, 1, 1).field(SKU, "A", 1, 1, 1)
            .group(LOTS, 1, 2)
            .field(AMOUNT, "20", 1, 2, 1).field(SKU, "B", 1, 2, 1)
            .field(BONUS, "5", 1, 2, 1).field(EXTRA, "7", 1, 2, 1)
    }

    /**
     * **THE DISCRIMINATOR.** The filter keeps only `Sku == "A"` (row 1), and its body additionally requires the
     * `$`-marked sum — the RULE's own row's `Bonus + Extra` — to be positive. So the outer sum decides which rows
     * fire: row 2 has `5 + 7`, row 1 has nothing. Reading the operands' presence on the filtered row instead of
     * the rule's row inverts exactly that.
     */
    @Test
    fun theOuterMarkedOperandsResolveInTheRulesOwnRow() =
        agreeFireRows(
            "FieldFilled($SKU) And Sum($LOTS*/Amount Having ([$SKU] == \"A\" And Sum(\$$BONUS, \$$EXTRA) > 0)) > 0",
            expected = listOf("/Claim[1]/Lots[2]/Sku|OMISSION_ERROR"),
            msg = "the \$-marked Bonus+Extra is positive only in row 2",
            build = ::onlyTheSecondRowCarriesABonus,
        )

    /**
     * The UNMARKED control: the same condition with the inner sum reading the FILTERED row rather than the rule's
     * row. The filter keeps row 1, whose `Bonus`/`Extra` are empty, so the sum is zero and NO row fires — the
     * mirror of the row above, which is what shows the marker is what selects the context rather than the fixture.
     */
    @Test
    fun theUnmarkedControlResolvesInTheFilteredRow() =
        agreeFireRows(
            "FieldFilled($SKU) And Sum($LOTS*/Amount Having ([$SKU] == \"A\" And Sum($BONUS, $EXTRA) > 0)) > 0",
            expected = listOf(),
            msg = "unmarked: the filter keeps row 1, whose Bonus and Extra are empty",
            build = ::onlyTheSecondRowCarriesABonus,
        )

    /**
     * **The SAME context mismatch one function over — `comparedCells`, [IG136] item (n).** Its final arm reads
     * `ctx.filled(pathOf(a))` and then `evalExpr(a)`, so the FILL test asks the ambient (filtered-row) context
     * while the VALUE read goes through `ctxOf` to the outer one — byte-identical to the defect this file's
     * discriminator above found in `aggOperandVals`. The kernel ADMITS a `$`-marked operand under
     * `FieldValuesNotUnique` inside a filter, so it is reachable.
     *
     * The document makes the two contexts disagree: the marked cells are EMPTY in the row the filter keeps and
     * hold a DUPLICATE pair in the rule's own row 2. Reading the filtered row drops both operands, finds no
     * duplicate, drops every row from the filter, and the sum never clears the bar.
     */
    @Test
    fun theOuterMarkedOperandsOfAUniquenessPredicateResolveInTheRulesOwnRow() =
        agreeFireRows(
            "FieldFilled($SKU) And Sum($LOTS*/Amount Having ([$SKU] == \"A\" " +
                "And FieldValuesNotUnique(\$$BONUS, \$$EXTRA))) > 0",
            expected = listOf("/Claim[1]/Lots[2]/Sku|OMISSION_ERROR"),
            msg = "the \$-marked Bonus/Extra pair duplicates only in row 2",
        ) { b ->
            b.group("/Claim", 1).field(REF, "C-1", 1, 1)
                .group(LOTS, 1, 1)
                .field(AMOUNT, "10", 1, 1, 1).field(SKU, "A", 1, 1, 1)
                .group(LOTS, 1, 2)
                .field(AMOUNT, "20", 1, 2, 1).field(SKU, "B", 1, 2, 1)
                .field(BONUS, "5", 1, 2, 1).field(EXTRA, "5", 1, 2, 1)
        }

    /**
     * **The THIRD function carrying the same two-line pattern, and the one that made it a class fix.** After the
     * two measured instances, a `grep` for `ctx.filled(` beside an `evalExpr(` returned three more sites:
     * `numberOfValueInFields` (here), `filledFieldsValue`'s plain-field arm, and `PolarityWalk.leafOmission`'s
     * `Expr.Field` arm — the last of which sits directly above an `Expr.EnumCategory` arm that already used
     * `ctxOf`, the sibling-disagreement tell this whole class shares. All three were fixed together; this row is
     * the lock, because it is the one whose fill test and value read sit on adjacent lines.
     */
    @Test
    fun theOuterMarkedOperandsOfAValueCountResolveInTheRulesOwnRow() =
        agreeFireRows(
            "FieldFilled($SKU) And Sum($LOTS*/Amount Having ([$SKU] == \"A\" " +
                "And NumberOfValueInFields(5 In \$$BONUS, \$$EXTRA) > 1)) > 0",
            expected = listOf("/Claim[1]/Lots[2]/Sku|OMISSION_ERROR"),
            msg = "the \$-marked pair both hold 5 only in row 2",
        ) { b ->
            b.group("/Claim", 1).field(REF, "C-1", 1, 1)
                .group(LOTS, 1, 1)
                .field(AMOUNT, "10", 1, 1, 1).field(SKU, "A", 1, 1, 1)
                .group(LOTS, 1, 2)
                .field(AMOUNT, "20", 1, 2, 1).field(SKU, "B", 1, 2, 1)
                .field(BONUS, "5", 1, 2, 1).field(EXTRA, "5", 1, 2, 1)
        }

    /**
     * **THE REFERENCE-CHANNEL row — [IG136] item (g), and a possible regression of my own.** A `$`-marked GROUP
     * reference inside a `Having` is deliberately kept `Ordinary` by `AstRefs`, precisely so it resolves in the
     * ENCLOSING row rather than iterating. `ModelStaticPlans.expandMessageReference` was then changed to convert an
     * expanded group reference into a `Wildcard` — measured on a TOP-LEVEL unstarred operand, which is a different
     * shape. If that conversion also catches this one, the reported pointers would carry wildcard coordinates
     * where the kernel writes concrete ones.
     *
     * So this row asserts `errorPath|type|referenced`, not a firing: the reference SET is the only channel the
     * defect would show in, and a boolean or even an `errorPath|type` projection is blind to it — which is how the
     * third instance of this class stayed hidden for nineteen cases.
     */
    @Test
    fun theOuterMarkedGroupReferenceKeepsItsConcreteCoordinates() {
        val condition = "FieldFilled($SKU) And Sum($LOTS*/Amount Having ([$SKU] == \"A\" " +
            "And Sum(\$$EXTRAS) > 0)) > 0"
        val kModel = RuntimeLaws.withRule(base, LOTS, "R", "../Sku", CODE, condition)
        val def = ModelLoader.load(kModel)

        val build: (Both) -> Unit = { b ->
            b.group("/Claim", 1).field(REF, "C-1", 1, 1)
                .group(EXTRAS, 1, 1).field(SPARE, "4", 1, 1, 1).field(RESERVE, "6", 1, 1, 1)
                .group(LOTS, 1, 1).field(AMOUNT, "10", 1, 1, 1).field(SKU, "A", 1, 1, 1)
                .group(LOTS, 1, 2).field(AMOUNT, "20", 1, 2, 1).field(SKU, "B", 1, 2, 1)
        }

        val dynamic = Both(RuntimeLaws.doc(), def.newDocument()).also(build)
        val kernelMessages = RuntimeLaws.messages(kModel, dynamic.kDoc)
        assertTrue(kernelMessages.map { it.code() }.none { it.startsWith("MVK_") }) {
            "the kernel REJECTED the \$-marked group reference inside the filter " +
                "(${kernelMessages.map { it.code() }.filter { it.startsWith("MVK_") }})"
        }
        val groovy = kernelMessages.filter { it.code() == CODE }
            .map { "${it.field()}|${it.type().name}|${it.referenced().sorted()}" }.sorted()

        val staticDoc = Both(RuntimeLaws.doc(), def.newDocument()).also(build).kDoc
        val javaStatic = RuntimeLaws.kernelJavaEngine(kModel)
            .messagesModern(staticDoc, null, Locale.US).filter { it.code() == CODE }
            .map { "${it.field()}|${it.type().name}|${it.referenced().sorted()}" }.sorted()

        val interpreter = DmInterpreter(def).validateFull(dynamic.iDoc).filter { it.code == CODE }
            .map {
                "${it.errorPath}|${if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR"}" +
                    "|${it.referenced.sorted()}"
            }.sorted()

        assertTrue(groovy.isNotEmpty()) { "the kernel did not fire, so this row measures no reference set" }
        // NON-VACUITY: the reference set must actually CONTAIN the $-marked group's expansion, or the row would
        // pass for a reason that has nothing to do with the conversion under test.
        assertTrue(groovy.any { it.contains("Extras") }) {
            "the reference set does not mention the \$-marked group's expansion at all: $groovy"
        }
        assertEquals(groovy, javaStatic, "groovy vs java-static (tri-check)")
        assertEquals(groovy, interpreter, "kernel vs INTERPRETER — the REFERENCE set of a \$-marked group ref")
    }

    /**
     * The positive control that the filtered aggregate evaluates at all: with the `$`-marked requirement dropped,
     * the filter keeps row 1 and its `Amount` of 10 clears the bar from every row.
     */
    @Test
    fun theFilteredAggregateFiresWithoutTheOuterRequirement() =
        agreeFireRows(
            "FieldFilled($SKU) And Sum($LOTS*/Amount Having [$SKU] == \"A\") > 0",
            expected = listOf(
                "/Claim[1]/Lots[1]/Sku|OMISSION_ERROR",
                "/Claim[1]/Lots[2]/Sku|OMISSION_ERROR",
            ),
            msg = "control: the filter keeps row 1, whose Amount is 10",
            build = ::onlyTheSecondRowCarriesABonus,
        )
}

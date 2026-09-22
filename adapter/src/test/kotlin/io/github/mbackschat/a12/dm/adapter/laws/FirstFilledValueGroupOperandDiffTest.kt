package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Differential + tri-check — **`FirstFilledValue` over a GROUP operand**, closing [IG135].
 *
 * **Why this needed its own measurement rather than an inference.** [KF173](../../../../../../../../../../../docs/KERNEL-FINDINGS.md#kf173)
 * records that `FirstFilledValue` routes through the same shared entity-list checker as `FieldValuesNotUnique`,
 * whose group-operand runtime contract is fully measured ([IF287](../../../../../../../../../../../docs/INTERPRETER-FINDINGS.md#if287)).
 * But routing through one checker is a **source-level reading, not a measured mechanism** — and the
 * `FieldValuesNotUnique` history is precisely the reason to distrust that inference: the same shape produced
 * **two** silent wrong answers in this evaluator, each found only by a differential, each reporting a missed
 * duplicate as "no error".
 *
 * **This operator can fail in a way a quantifier cannot, which is what makes it the most valuable member of the
 * family.** `FieldValuesNotUnique` answers a boolean, so a wrong expansion can only flip a firing. `FirstFilledValue`
 * answers a **value**, so a wrong expansion ORDER returns the wrong cell's contents while still firing — a defect
 * no fires/does-not-fire assertion can see. Both axes are measured here:
 *
 * - [theGroupOperandReachesAValueInANestedSecondRow] — the row-1-pin discriminator, the fourth defect of
 *   [IF287]'s class. The only filled cell lies in nested row 2.
 * - [theDirectFieldIsEncounteredBeforeTheNestedRows] — the ORDER discriminator. Both a direct field and a nested
 *   row hold a value, so the answer says which the expansion visits first, and the two candidate answers are
 *   different strings rather than different verdicts.
 *
 * The group form is authored through the typed surface (`m.firstFilledGroup`), which exists here — unlike the
 * date extrema and the value-list quantifiers, whose group slots are type-level unrepresentable
 * ([DG35](../../../../../../../../../../../docs/DEV-API-GAPS.md)) — so the dogfooding rule applies and no condition
 * below is hand-written.
 */
class FirstFilledValueGroupOperandDiffTest {

    private companion object {
        const val REF = "/Return/Reference"
        const val HANDLING = "/Return/Handling"
        const val PRIMARY_NOTE = "/Return/Handling/PrimaryNote"
        const val INSPECTIONS = "/Return/Handling/Inspections"
        const val FINDING = "/Return/Handling/Inspections/Finding"
        const val FINDING_STAR = "/Return/Handling/Inspections*/Finding"
        const val ENCOUNTER = "/Return/Encounter"
        const val ZETA_DIRECT = "/Return/Encounter/ZetaDirect"
        const val ALPHA_DIRECT = "/Return/Encounter/AlphaDirect"
        const val ZETA_NESTED_VALUE = "/Return/Encounter/ZetaNested/Value"
        const val ALPHA_NESTED_VALUE = "/Return/Encounter/AlphaNested/Value"
        const val ROWS = "/Return/Rows"
        const val FIRST = "/Return/Rows/First"
        const val SECOND = "/Return/Rows/Second"
        const val FALLBACK = "/Return/Fallback"
        const val CODE = "FFVG"
    }

    /**
     * A returns-handling model whose `Handling` group holds ONE direct String and a nested **repeatable**
     * `Inspections` carrying one String. The nesting is the point: it puts a repeatable level BETWEEN the operand
     * and one of its fields, which is the shape [IG136]'s discriminator recipe calls for and which every flat
     * group fixture is blind to. Homogeneous (all Strings) so no kind gate can be the answer.
     */
    private val base = DocumentModelDraft.named("return-handling").locale("en_US")
        .rootGroup("Return") { g ->
            g.stringField("Reference") { }
            g.group("Handling") { h ->
                h.stringField("PrimaryNote") { }
                h.group("Inspections") { i -> i.repeatable(3).stringField("Finding") { } }
            }
        }.build().toJson()

    private val m = BoundModel.ofJson(base)

    private val fixedBase = DocumentModelDraft.named("fixed-group-first-filled-order").locale("en_US")
        .rootGroup("Return") { g ->
            g.stringField("Reference") { }
            g.group("Encounter") { encounter ->
                encounter.stringField("ZetaDirect") { }
                encounter.stringField("AlphaDirect") { }
                encounter.group("ZetaNested") { nested -> nested.stringField("Value") { } }
                encounter.group("AlphaNested") { nested -> nested.stringField("Value") { } }
            }
        }.build().toJson()

    private val fixedModel = BoundModel.ofJson(fixedBase)

    private val starredBase = DocumentModelDraft.named("starred-group-first-filled-order").locale("en_US")
        .rootGroup("Return") { g ->
            g.stringField("Reference") { }
            g.group("Rows") { rows ->
                rows.repeatable(3)
                rows.stringField("First") { }
                rows.stringField("Second") { }
            }
            g.stringField("Fallback") { }
        }.build().toJson()

    private val starredModel = BoundModel.ofJson(starredBase)

    /** `FieldFilled(Reference) And FirstFilledValue(/Return/Handling) == expected` — the GROUP operand, typed. */
    private fun overGroup(expected: String): String = RuntimeLaws.render(
        m.field(REF).filled().and(Dsl.firstFilledString(m.firstFilledGroup(HANDLING)).eq(expected)),
    )

    /** The control: the same two entities listed explicitly, so a disagreement cannot be about the fixture. */
    private fun overExplicitFields(expected: String): String = RuntimeLaws.render(
        m.field(REF).filled().and(
            Dsl.firstFilledString(
                m.field(PRIMARY_NOTE).firstFilledOperand(),
                m.flatten(FINDING_STAR).firstFilledOperand(),
            ).eq(expected),
        ),
    )

    private fun overFixedGroup(expected: String): String = RuntimeLaws.render(
        fixedModel.field(REF).filled().and(
            Dsl.firstFilledString(fixedModel.firstFilledGroup(ENCOUNTER)).eq(expected),
        ),
    )

    private fun overStarredGroupAndFallback(expected: String): String = RuntimeLaws.render(
        starredModel.field(REF).filled().and(
            Dsl.firstFilledString(
                starredModel.firstFilledGroup("$ROWS*"),
                starredModel.field(FALLBACK).firstFilledOperand(),
            ).eq(expected),
        ),
    )

    private class Both(val kDoc: RuntimeLaws.Doc, val iDoc: Document) {
        fun group(path: String, vararg reps: Int): Both {
            kDoc.group(path, *reps); iDoc.group(path, *reps); return this
        }

        fun field(path: String, v: String, vararg reps: Int): Both {
            kDoc.field(path, v, *reps); iDoc.field(path, v, *reps); return this
        }
    }

    private fun assertAdmitted(codes: List<String>, msg: String) =
        assertTrue(codes.none { it.startsWith("MVK_") }) {
            "$msg — the kernel REJECTED the shape (${codes.filter { it.startsWith("MVK_") }}), so this row would " +
                "compare two silences while reading as agreement; that is a measurement about admission"
        }

    /**
     * The full `errorPath|type` signature of every message carrying [CODE], sorted — NOT a boolean.
     *
     * A boolean makes a message of the wrong TYPE read as agreement, and that is not hypothetical: the ninth
     * instance of this defect class shipped green under a boolean lock written in the same commit as its own fix,
     * and the tenth and thirteenth were both found by widening a projection rather than by adding a case. The
     * kernel's own verdict is still asserted as a boolean (`expected`), because that is what each row's document
     * is designed to produce; what is compared ACROSS engines is the signature.
     */
    private fun signature(messages: List<RuntimeLaws.Msg>): List<String> =
        messages.filter { it.code() == CODE }.map { "${it.field()}|${it.type().name}" }.sorted()

    private fun agreeFires(
        condition: String,
        expected: Boolean,
        msg: String,
        modelJson: String = base,
        expectedType: MsgType? = null,
        build: (Both) -> Unit,
    ) {
        val kModel = RuntimeLaws.withRule(modelJson, "/Return", "R", "../Reference", CODE, condition)
        val def = ModelLoader.load(kModel)

        val dynamic = Both(RuntimeLaws.doc(), def.newDocument()).also(build)
        val kernelMessages = RuntimeLaws.messages(kModel, dynamic.kDoc)
        assertAdmitted(kernelMessages.map { it.code() }, msg)
        val groovy = signature(kernelMessages)

        val staticDoc = Both(RuntimeLaws.doc(), def.newDocument()).also(build).kDoc
        val javaStatic = signature(
            RuntimeLaws.kernelJavaEngine(kModel).messagesModern(staticDoc, null, java.util.Locale.US))

        val interpreter = DmInterpreter(def).validateFull(dynamic.iDoc).filter { it.code == CODE }
            .map { "${it.errorPath}|${if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR"}" }
            .sorted()

        assertEquals(expected, groovy.isNotEmpty(), "$msg — the kernel groovy-dynamic verdict")
        if (expectedType != null) {
            val expectedKernelType = when (expectedType) {
                MsgType.OMISSION -> "OMISSION_ERROR"
                MsgType.VALUE -> "VALUE_ERROR"
            }
            assertEquals(listOf(expectedKernelType), groovy.map { it.substringAfter('|') },
                "$msg — selected-value polarity")
        }
        assertEquals(groovy, javaStatic, "$msg — groovy vs java-static (tri-check)")
        assertEquals(groovy, interpreter, "$msg — kernel vs INTERPRETER")
    }

    private fun reference(b: Both): Both =
        b.group("/Return", 1).field(REF, "RET-1", 1, 1)

    private fun fixedFields(
        b: Both,
        zetaDirect: String? = null,
        alphaDirect: String? = null,
        zetaNested: String? = null,
        alphaNested: String? = null,
    ) {
        reference(b).group(ENCOUNTER, 1, 1)
        zetaDirect?.let { b.field(ZETA_DIRECT, it, 1, 1, 1) }
        alphaDirect?.let { b.field(ALPHA_DIRECT, it, 1, 1, 1) }
        zetaNested?.let { b.field(ZETA_NESTED_VALUE, it, 1, 1, 1, 1) }
        alphaNested?.let { b.field(ALPHA_NESTED_VALUE, it, 1, 1, 1, 1) }
    }

    private fun starredRow(b: Both, row: Int, first: String? = null, second: String? = null) {
        b.group(ROWS, 1, row)
        first?.let { b.field(FIRST, it, 1, row, 1) }
        second?.let { b.field(SECOND, it, 1, row, 1) }
    }

    /** All three declared inspection rows instantiated; only nested row 2 carries a finding. */
    private fun onlyTheSecondNestedRowIsFilled(b: Both) {
        b.group("/Return", 1).field(REF, "RET-1", 1, 1)
            .group(INSPECTIONS, 1, 1, 1)
            .group(INSPECTIONS, 1, 1, 2).field(FINDING, "SCUFFED", 1, 1, 2, 1)
            .group(INSPECTIONS, 1, 1, 3)
    }

    /**
     * **THE ROW-1-PIN DISCRIMINATOR.** The operand's only filled cell sits in nested row 2, so an expansion that
     * pinned the repeatable level to row 1 finds nothing and the comparison cannot hold. The rival reading and the
     * correct one therefore predict opposite verdicts rather than the same one.
     */
    @Test
    fun theGroupOperandReachesAValueInANestedSecondRow() =
        agreeFires(overGroup("SCUFFED"), expected = true,
            msg = "GROUP: the only finding is in Inspections[2]; a row-1 read misses it",
            build = ::onlyTheSecondNestedRowIsFilled)

    /** The same document through the explicit entity list — the fixture control. */
    @Test
    fun theExplicitListReachesTheSameNestedValue() =
        agreeFires(overExplicitFields("SCUFFED"), expected = true,
            msg = "control: the explicit list reaches Inspections[2]/Finding",
            build = ::onlyTheSecondNestedRowIsFilled)

    /** The negative control: the value is compared against something the document does not hold. */
    @Test
    fun theGroupOperandDoesNotMatchAValueThatIsAbsent() =
        agreeFires(overGroup("PRISTINE"), expected = false,
            msg = "GROUP: the first filled value is SCUFFED, not PRISTINE",
            build = ::onlyTheSecondNestedRowIsFilled)

    /** A direct value AND a nested value, so the answer reports which the expansion encounters first. */
    private fun bothTheDirectFieldAndANestedRowAreFilled(b: Both) {
        b.group("/Return", 1).field(REF, "RET-1", 1, 1)
            .field(PRIMARY_NOTE, "DIRECT", 1, 1, 1)
            .group(INSPECTIONS, 1, 1, 1).field(FINDING, "NESTED", 1, 1, 1, 1)
            .group(INSPECTIONS, 1, 1, 2)
            .group(INSPECTIONS, 1, 1, 3)
    }

    /**
     * **THE ORDER DISCRIMINATOR**, and the axis no fires/does-not-fire row can reach. Both candidate cells hold a
     * value, so the operator's answer is a *value* and the two rival expansion orders return different strings:
     * declaration order gives `DIRECT`, a nested-first expansion gives `NESTED`. The paired negative below is what
     * makes the claim two-sided — asserting only the positive would also pass on an engine that returned neither.
     */
    @Test
    fun theDirectFieldIsEncounteredBeforeTheNestedRows() =
        agreeFires(overGroup("DIRECT"), expected = true,
            msg = "GROUP/order: the direct PrimaryNote is encountered before the nested rows",
            build = ::bothTheDirectFieldAndANestedRowAreFilled)

    @Test
    fun theNestedValueIsNotTheFirstOne() =
        agreeFires(overGroup("NESTED"), expected = false,
            msg = "GROUP/order: the nested finding is not the first filled value",
            build = ::bothTheDirectFieldAndANestedRowAreFilled)

    /** The control on the order axis, so a disagreement isolates the group spelling rather than the ordering law. */
    @Test
    fun theExplicitListOrdersTheSameWay() =
        agreeFires(overExplicitFields("DIRECT"), expected = true,
            msg = "control/order: the explicit list encounters PrimaryNote first",
            build = ::bothTheDirectFieldAndANestedRowAreFilled)

    /**
     * The empty control. Without it a `false` above could mean "the rule never evaluated" rather than a statement
     * about the value, and comparing against the empty string keeps the row about the operand rather than about
     * the comparison.
     */
    @Test
    fun anEmptyGroupOperandMatchesNothing() =
        agreeFires(overGroup("SCUFFED"), expected = false,
            msg = "GROUP: nothing below the group is filled") { b ->
            b.group("/Return", 1).field(REF, "RET-1", 1, 1)
                .group(INSPECTIONS, 1, 1, 1)
        }

    /** SPEC-2026-08-17-02: lexical path order loses to stable model declaration order at both fixed depths. */
    @Test
    fun aFixedGroupUsesStableDeclarationOrderAndPrefixPolarity() {
        agreeFires(overFixedGroup("zeta"), expected = true,
            msg = "fixed/direct both: ZetaDirect is declared before AlphaDirect",
            modelJson = fixedBase, expectedType = MsgType.VALUE) { b ->
            fixedFields(b, zetaDirect = "zeta", alphaDirect = "alpha")
        }
        agreeFires(overFixedGroup("alpha"), expected = true,
            msg = "fixed/direct second-only: AlphaDirect remains reachable",
            modelJson = fixedBase, expectedType = MsgType.OMISSION) { b ->
            fixedFields(b, alphaDirect = "alpha")
        }
        agreeFires(overFixedGroup("zeta"), expected = true,
            msg = "fixed/nested both: ZetaNested is declared before AlphaNested",
            modelJson = fixedBase, expectedType = MsgType.OMISSION) { b ->
            fixedFields(b, zetaNested = "zeta", alphaNested = "alpha")
        }
        agreeFires(overFixedGroup("alpha"), expected = true,
            msg = "fixed/nested second-only: AlphaNested remains reachable",
            modelJson = fixedBase, expectedType = MsgType.OMISSION) { b ->
            fixedFields(b, alphaNested = "alpha")
        }
    }

    /** SPEC-2026-08-17-03: group-star expansion is declaration-major, not row-major. */
    @Test
    fun aStarredGroupTraversesDeclarationMajorAcrossRows() {
        agreeFires(overStarredGroupAndFallback("first"), expected = true,
            msg = "star/cross-row: row 2 First precedes row 1 Second",
            modelJson = starredBase, expectedType = MsgType.OMISSION) { b ->
            reference(b)
            starredRow(b, 1, second = "second")
            starredRow(b, 2, first = "first")
        }
        agreeFires(overStarredGroupAndFallback("second"), expected = true,
            msg = "star/second-only: the later declaration is reachable",
            modelJson = starredBase, expectedType = MsgType.OMISSION) { b ->
            reference(b)
            starredRow(b, 1, second = "second")
            starredRow(b, 2)
        }
        agreeFires(overStarredGroupAndFallback("first"), expected = true,
            msg = "star/later-first-only: row 2 of the earlier declaration is reachable",
            modelJson = starredBase, expectedType = MsgType.OMISSION) { b ->
            reference(b)
            starredRow(b, 1)
            starredRow(b, 2, first = "first")
        }
        agreeFires(overStarredGroupAndFallback("first"), expected = true,
            msg = "star/immediate-first: later rows do not taint the selected value",
            modelJson = starredBase, expectedType = MsgType.VALUE) { b ->
            reference(b)
            starredRow(b, 1, first = "first")
            starredRow(b, 2, second = "second")
        }
        agreeFires(overStarredGroupAndFallback("fallback"), expected = true,
            msg = "star/no-row: the selected fallback is OMISSION",
            modelJson = starredBase, expectedType = MsgType.OMISSION) { b ->
            reference(b).field(FALLBACK, "fallback", 1, 1)
        }
        agreeFires(overStarredGroupAndFallback("fallback"), expected = true,
            msg = "star/empty-row: the selected fallback is OMISSION",
            modelJson = starredBase, expectedType = MsgType.OMISSION) { b ->
            reference(b)
            starredRow(b, 1)
            b.field(FALLBACK, "fallback", 1, 1)
        }
    }
}

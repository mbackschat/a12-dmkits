package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Differential + tri-check — the **value-list quantifiers over a GROUP operand**, the [IG136] candidate the
 * 2026-08-14 admission sweep promoted from speculation to live work: all three plural forms are ADMITTED in both
 * repetition shapes ([KF187](../../../../../../../../../docs/KERNEL-FINDINGS.md)).
 *
 * **The predicted symptom is an EMPTY SELECTION, and neither arm of `vlqOperands` escapes it.** Its `Expr.Star`
 * arm reads `c.starCells(e.path, …)` — cells AT the starred path, of which a group path has none, since no field
 * is declared there. Its `Expr.Field` arm takes `presenceTri(e)`, which is FALSE for a group (a group holds no
 * value of its own), contributing one EMPTY cell. Neither arm has a group check.
 *
 * **The assertion shape is what makes an empty selection provable rather than merely consistent.** A quantifier
 * over zero cells still returns a well-formed verdict, so a single row cannot distinguish "read nothing" from
 * "read everything and answered no". A pair of OPPOSITE-DIRECTION quantifiers can: on a document where one
 * listed value IS in the list, `AtLeastOne…` must fire and `No…` must not. If both invert together, the
 * selection was empty — that is the signature, and it is exactly the evidence that settled the seventh instance
 * of this class ([IF287](../../../../../../../../../docs/INTERPRETER-FINDINGS.md#if287)), where `NoFieldFilled`
 * answered TRUE and `AtLeastOneFieldFilled` answered FALSE on the same fully-filled document.
 *
 * **`NotAll…` is deliberately included as the third polarity and deliberately NOT trusted alone**: over zero
 * cells it is vacuously false, which is also the correct answer whenever every listed value is in the list — so
 * on its own it cannot discriminate, and it earns its place only beside the pair above.
 *
 * **Every group condition is derived from its typed control by substituting the operand alone.** The group form
 * itself is unauthorable — the three quantifiers carry six operand overload shapes and not one is a
 * `GroupListRef` ([DG35](../../../../../../../../../docs/DEV-API-GAPS.md)) — so deriving it by substitution is
 * what keeps the two conditions from differing in anything else, and [assertSubstituted] fails if the
 * substitution misses, which would otherwise compare each control to itself and pass.
 */
class GroupOperandValueListQuantifierDiffTest {

    private companion object {
        const val REF = "/Policy/Reference"
        const val ADJUSTERS = "/Policy/Adjusters"
        const val NAME_STAR = "/Policy/Adjusters*/Name"
        const val ADJUSTERS_STAR = "/Policy/Adjusters*"
        const val NAME = "/Policy/Adjusters/Name"
        const val REGION = "/Policy/Adjusters/Region"
        const val CODE = "VLQG"

        /** The value list every condition below screens against. */
        val WATCHED = listOf("NORTH", "SOUTH")
    }

    /**
     * A policy model whose repeatable `Adjusters` group holds ONLY String fields — homogeneous, so the category
     * gate cannot be the answer, and both declared rows are instantiated by every document below.
     */
    private val base = DocumentModelDraft.named("policy").locale("en_US")
        .rootGroup("Policy") { g ->
            g.stringField("Reference") { }
            g.group("Adjusters") { a ->
                a.repeatable(2).stringField("Name") { }.stringField("Region") { }
            }
        }.build().toJson()

    private val m = BoundModel.ofJson(base)

    /** The three quantifiers, each over the single-field control operand — authored through the typed surface. */
    private fun control(quantifier: Quantifier): String = DslRenderer().render(
        m.field(REF).filled().and(
            when (quantifier) {
                Quantifier.AT_LEAST_ONE ->
                    Dsl.atLeastOneFieldValueIncludedInValueList(WATCHED, m.flatten(NAME_STAR))
                Quantifier.NONE ->
                    Dsl.noFieldValueIncludedInValueList(WATCHED, m.flatten(NAME_STAR))
                Quantifier.NOT_ALL ->
                    Dsl.notAllFieldValuesIncludedInValueList(WATCHED, m.flatten(NAME_STAR))
            },
        ),
    )

    /** The same condition with the operand replaced by the GROUP the field lives in — the only difference. */
    private fun overGroup(quantifier: Quantifier): String =
        control(quantifier).replace(NAME_STAR, ADJUSTERS_STAR)

    private enum class Quantifier { AT_LEAST_ONE, NONE, NOT_ALL }

    private fun assertSubstituted(quantifier: Quantifier) {
        assertNotEquals(control(quantifier), overGroup(quantifier),
            "the group substitution did not apply — the row would compare the control to itself")
        assertTrue(overGroup(quantifier).contains(ADJUSTERS_STAR)) {
            "the derived condition does not name the group operand: ${overGroup(quantifier)}"
        }
    }

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
            "$msg — the kernel REJECTED the shape (${codes.filter { it.startsWith("MVK_") }}), so this row and " +
                "its control would compare two silences while reading as agreement"
        }

    private fun agreeFires(condition: String, expected: Boolean, msg: String, build: (Both) -> Unit) {
        val kModel = RuntimeLaws.withRule(base, "/Policy", "R", "../Reference", CODE, condition)
        val def = ModelLoader.load(kModel)

        val dynamic = Both(RuntimeLaws.doc(), def.newDocument()).also(build)
        val kernelMessages = RuntimeLaws.messages(kModel, dynamic.kDoc)
        assertAdmitted(kernelMessages.map { it.code() }, msg)
        val groovy = kernelMessages.any { it.code() == CODE }

        val staticDoc = Both(RuntimeLaws.doc(), def.newDocument()).also(build).kDoc
        val javaStatic = RuntimeLaws.kernelJavaEngine(kModel)
            .messagesModern(staticDoc, null, java.util.Locale.US).any { it.code() == CODE }

        val interpreter = DmInterpreter(def).validateFull(dynamic.iDoc).any { it.code == CODE }

        assertEquals(expected, groovy, "$msg — the kernel groovy-dynamic verdict")
        assertEquals(groovy, javaStatic, "$msg — groovy vs java-static (tri-check)")
        assertEquals(groovy, interpreter, "$msg — kernel vs INTERPRETER")
    }

    /**
     * Row 1 holds a WATCHED region and an unwatched name; row 2 holds neither. So the group operand's selection
     * contains a listed value while the control field's selection does not — the two spellings must disagree on
     * every one of the three quantifiers, which is what proves the group reaches `Region` at all.
     */
    private fun watchedRegionOnly(b: Both) {
        b.group("/Policy", 1).field(REF, "P-1", 1, 1)
            .group(ADJUSTERS, 1, 1)
            .field(NAME, "Alvarez", 1, 1, 1).field(REGION, "NORTH", 1, 1, 1)
            .group(ADJUSTERS, 1, 2)
            .field(NAME, "Brenner", 1, 2, 1).field(REGION, "WEST", 1, 2, 1)
    }

    /**
     * **THE OPPOSITE-DIRECTION PAIR.** `AtLeastOne…` must fire over the group (row 1's `NORTH` is in the list)
     * and `No…` must not. An empty selection inverts BOTH — that is the signature this row exists to detect, and
     * neither quantifier alone can distinguish it from a correct verdict.
     */
    @Test
    fun theGroupOperandsSelectionIsNotEmpty() {
        assertSubstituted(Quantifier.AT_LEAST_ONE)
        agreeFires(overGroup(Quantifier.AT_LEAST_ONE), expected = true,
            msg = "GROUP/AtLeastOne: Adjusters[1]/Region is NORTH, which is in the list",
            build = ::watchedRegionOnly)
        agreeFires(overGroup(Quantifier.NONE), expected = false,
            msg = "GROUP/No: a listed value IS present, so the quantifier must not fire",
            build = ::watchedRegionOnly)
    }

    /**
     * The control on the same document, which is what isolates the group operand: the single `Name` field's
     * selection holds no listed value, so the pair's verdicts are the exact mirror of the group's.
     */
    @Test
    fun theSingleFieldControlSeesNoListedValue() {
        agreeFires(control(Quantifier.AT_LEAST_ONE), expected = false,
            msg = "control/AtLeastOne: no Name is a listed value", build = ::watchedRegionOnly)
        agreeFires(control(Quantifier.NONE), expected = true,
            msg = "control/No: no Name is a listed value, so the quantifier fires", build = ::watchedRegionOnly)
    }

    /** The third polarity, which earns its place only beside the pair above (it is vacuous over zero cells). */
    @Test
    fun theThirdPolarityAgreesOverTheGroup() {
        assertSubstituted(Quantifier.NOT_ALL)
        agreeFires(overGroup(Quantifier.NOT_ALL), expected = true,
            msg = "GROUP/NotAll: Alvarez is not a listed value", build = ::watchedRegionOnly)
    }

    /**
     * The ROW-2 discriminator, against a group operand that expands but pins the repeatable level to row 1 — the
     * fourth defect of this class, which every flat fixture is blind to. The only listed value lives in row 2.
     */
    @Test
    fun theGroupOperandReachesTheSecondRow() {
        assertSubstituted(Quantifier.AT_LEAST_ONE)
        agreeFires(overGroup(Quantifier.AT_LEAST_ONE), expected = true,
            msg = "GROUP/AtLeastOne: the only listed value is in Adjusters[2]; a row-1 read misses it") { b ->
            b.group("/Policy", 1).field(REF, "P-1", 1, 1)
                .group(ADJUSTERS, 1, 1)
                .field(NAME, "Alvarez", 1, 1, 1).field(REGION, "WEST", 1, 1, 1)
                .group(ADJUSTERS, 1, 2)
                .field(NAME, "Brenner", 1, 2, 1).field(REGION, "SOUTH", 1, 2, 1)
        }
    }

    /**
     * The UNSTARRED spelling over a non-repeatable group, which is the arm `presenceTri` decides rather than
     * `starCells` — a different code path to the same predicted empty selection, so it needs its own row.
     */
    @Test
    fun theUnstarredGroupOperandsSelectionIsNotEmpty() {
        val nonRepeatable = DocumentModelDraft.named("policy-flat").locale("en_US")
            .rootGroup("Policy") { g ->
                g.stringField("Reference") { }
                g.group("Contact") { c -> c.stringField("Email") { }.stringField("Region") { } }
            }.build().toJson()
        val flat = BoundModel.ofJson(nonRepeatable)
        val emailOnly = DslRenderer().render(
            flat.field(REF).filled().and(
                Dsl.atLeastOneFieldValueIncludedInValueList(WATCHED, flat.str("/Policy/Contact/Email")),
            ),
        )
        val overContact = emailOnly.replace("/Policy/Contact/Email", "/Policy/Contact")
        assertNotEquals(emailOnly, overContact, "the unstarred group substitution did not apply")

        fun build(b: Both) {
            b.group("/Policy", 1).field(REF, "P-1", 1, 1)
                .field("/Policy/Contact/Email", "ops@example.com", 1, 1, 1)
                .field("/Policy/Contact/Region", "NORTH", 1, 1, 1)
        }

        val kModel = RuntimeLaws.withRule(nonRepeatable, "/Policy", "R", "../Reference", CODE, overContact)
        val def = ModelLoader.load(kModel)
        val dynamic = Both(RuntimeLaws.doc(), def.newDocument()).also(::build)
        val kernelMessages = RuntimeLaws.messages(kModel, dynamic.kDoc)
        assertAdmitted(kernelMessages.map { it.code() }, "unstarred group operand")
        val groovy = kernelMessages.any { it.code() == CODE }

        val staticDoc = Both(RuntimeLaws.doc(), def.newDocument()).also(::build).kDoc
        val javaStatic = RuntimeLaws.kernelJavaEngine(kModel)
            .messagesModern(staticDoc, null, java.util.Locale.US).any { it.code() == CODE }

        val interpreter = DmInterpreter(def).validateFull(dynamic.iDoc).any { it.code == CODE }

        assertEquals(true, groovy, "the unstarred group's Region is NORTH, a listed value → the kernel fires")
        assertEquals(groovy, javaStatic, "groovy vs java-static (tri-check)")
        assertEquals(groovy, interpreter, "kernel vs INTERPRETER — the unstarred group operand")
    }
}

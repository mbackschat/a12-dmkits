package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of where an up-step may stand in a path, and what a path answers when one stands
 * anywhere else.
 *
 * Every row mirrors one measured against the real kernel by `rulekit.validate.laws.PathSegmentWalkLawsTest`,
 * on the same policy model ([KERNEL-FINDINGS KF465]). An up-step is admitted only as a relative path's leading
 * run. Anywhere else A12 refuses it as `MVK_UNEXPECTED_TOKEN`, and only a star on a named segment before it, or
 * a leading up-run, makes A12 judge the segments before it first. The checker used to resolve such a path by
 * cancelling each `X/..` pair, which read `Claims/..` as the rule's own group.
 */
class MisplacedUpStepTest {

    private fun fromRoot(operand: String) =
        PolicyPathSource.rejections("../Holder", "FieldFilled(Holder) And FieldFilled($operand)")

    private fun fromInsured(operand: String) =
        PolicyPathSource.rejections("../Remark", "FieldFilled(Remark) And FieldFilled($operand)", inInsured = true)

    private fun judged(rows: Map<String, List<String>>, read: (String) -> List<String>) =
        rows.keys.associateWith(read)

    @Test
    fun withNothingStarredBeforeItTheUpStepIsATokenWhateverThePrefixResolvesTo() {
        val rows = listOf(
            "Claims/..", "Holder/../Amount", "Nope/../Holder", "Claims/..*/Nope*", "Nope/../Claims*",
            "Holder/../Claims*", "Claims/Nope/..", "Claims/Amount/..", "Claims/..*", "Nope/..*",
            "Nope/../Claims.*", "Insured/Nope/../Claims*",
            "/Policy/Insured/..", "/Policy/../Claims.*", "/../Holder", "/Nope/..", "/Nope/../Claims*",
            "/Policy/Nope/..",
        ).associateWith { listOf(TOKEN) }
        assertEquals(rows, judged(rows, ::fromRoot), "an up-step with no star before it")
    }

    @Test
    fun aStarBeforeTheUpStepHasItsPrefixJudgedFirst() {
        val rows = linkedMapOf(
            "Claims*/.." to listOf(TOKEN),
            "Claims*/../Amount" to listOf(TOKEN),
            "/Policy/Claims*/.." to listOf(TOKEN),
            "Claims*/Amount/.." to listOf(TOKEN),
            "Claims*/../.." to listOf(TOKEN),
            "Claims*/..*" to listOf(TOKEN),
            "Nope*/.." to listOf("MVK_INVALID_ENTITY"),
            "Nope*/../Holder" to listOf("MVK_INVALID_ENTITY"),
            "Claims*/Nope/.." to listOf("MVK_INVALID_ENTITY"),
            "Nope/Claims*/.." to listOf("MVK_INVALID_ENTITY"),
            "/Claims*/.." to listOf("MVK_INVALID_ROOT_GROUP"),
            "/Nope/Claims*/.." to listOf("MVK_INVALID_ROOT_GROUP"),
            "Claims.*/.." to listOf("MVK_POINT_IN_A12_MODEL"),
            "Claims.*/Nope/.." to listOf("MVK_POINT_IN_A12_MODEL"),
            "Insured*/.." to listOf("MVK_INVALID_WILDCARD"),
            "Holder*/.." to listOf("MVK_INVALID_WILDCARD"),
            "Claims*/Amount*/.." to listOf("MVK_INVALID_WILDCARD"),
            "Claims/Amount*/.." to listOf("MVK_INVALID_WILDCARD"),
        )
        assertEquals(rows, judged(rows, ::fromRoot), "an up-step after a starred prefix")
    }

    @Test
    fun aLeadingUpRunHasTheRestOfThePrefixJudgedFirstToo() {
        val rows = linkedMapOf(
            "../Holder" to emptyList(),
            "../Claims/.." to listOf(TOKEN),
            "../Claims*/.." to listOf(TOKEN),
            "../Claims*/Amount/.." to listOf(TOKEN),
            "../Nope/.." to listOf("MVK_INVALID_ENTITY"),
            "../Nope*/.." to listOf("MVK_INVALID_ENTITY"),
            "..*/Claims/.." to listOf("MVK_INVALID_WILDCARD_REL"),
            "Remark/.." to listOf(TOKEN),
            "Remark*/.." to listOf("MVK_INVALID_WILDCARD"),
            "Claims.*/.." to listOf("MVK_INVALID_ENTITY"),
        )
        assertEquals(rows, judged(rows, ::fromInsured), "an up-step from a nested rule")
    }

    @Test
    fun aNamedTurningPointAfterANameIsMisplacedToo() {
        val rows = linkedMapOf(
            "Claims/..Policy/Holder" to listOf(TOKEN),
            "Claims/..Policy*/Holder" to listOf(TOKEN),
            "Claims/..Nope/Holder" to listOf(TOKEN),
            "Claims*/..Policy/Holder" to listOf(TOKEN),
            "Nope*/..Policy/Holder" to listOf("MVK_INVALID_ENTITY"),
            "/Policy/..Policy/Holder" to listOf(TOKEN),
        )
        assertEquals(rows, judged(rows, ::fromRoot), "a named turning point after a name")
    }

    @Test
    fun theRefusalDoesNotDependOnTheCarrier() {
        val rows = linkedMapOf(
            "AtLeastOneGroupFilled(Claims/.., Insured)" to listOf(TOKEN),
            "Sum(Claims/../Claims*/Amount) > 0" to listOf(TOKEN),
        )
        val actual = rows.keys.associateWith { PolicyPathSource.rejections("../Holder", "FieldFilled(Holder) And $it") }
        assertEquals(rows, actual, "a misplaced up-step on two more carriers")
    }

    /**
     * The one row where the order matters: the up-step's token class is also a class the checker withholds
     * behind a reference that does not resolve, and that reference is this one.
     */
    @Test
    fun theTokenClassIsNotWithheldBehindItsOwnReference() {
        assertEquals(listOf(TOKEN), fromRoot("Claims/.."), "the up-step's own class")
    }

    /**
     * A plain field parameter predicts its complete entity before lookup, so a misplaced up-step after a miss
     * is a token error where
     * a condition names the entity class. The controls are the surface's own vocabulary.
     */
    @Test
    fun aMessageParameterRefusesEveryMisplacedUpStepAsAToken() {
        val rows = linkedMapOf(
            "see \$Holder\$" to emptyList(),
            "see \$Claims/../Holder\$" to listOf(TOKEN),
            "see \$Nope/../Holder\$" to listOf(TOKEN),
            "see \$Insured/../Holder\$" to listOf(TOKEN),
            "see \$Claims/..\$" to listOf(TOKEN),
            "see \$/Policy/../Holder\$" to listOf(TOKEN),
            "see \$/../Holder\$" to listOf(TOKEN),
        )
        val actual = rows.keys.associateWith {
            PolicyPathSource.rejections("../Holder", "FieldFilled(Holder)", message = it)
        }
        assertEquals(rows, actual, "a message parameter from a root-level rule")
        val nested = linkedMapOf(
            "see \$../Holder\$" to emptyList(),
            "see \$../Nope\$" to listOf("INVALID_ENTITY"),
            "see \$../../Holder\$" to listOf("ERROR_TEXT_PARAMETER_TOO_MANY_PARENT_GROUPS"),
            "see \$../Insured/../Holder\$" to listOf(TOKEN),
            "see \$../Claims/..\$" to listOf(TOKEN),
            "see \$../Nope/../Holder\$" to listOf(TOKEN),
            "see \$../Nope/..\$" to listOf(TOKEN),
        )
        val nestedActual = nested.keys.associateWith {
            PolicyPathSource.rejections("../Remark", "FieldFilled(Remark) And FieldFilled(../Holder)",
                inInsured = true, message = it)
        }
        assertEquals(nested, nestedActual, "a message parameter from a nested rule")
    }

    private companion object {
        const val TOKEN = "MVK_UNEXPECTED_TOKEN"
    }
}

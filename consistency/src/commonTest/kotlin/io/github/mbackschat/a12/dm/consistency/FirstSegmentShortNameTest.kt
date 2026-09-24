package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The checker's half of where the model-wide short-name fallback applies: a relative path's FIRST segment,
 * starred or not and whatever follows it.
 *
 * Every row mirrors one measured against the real kernel by `rulekit.validate.laws.PathSegmentWalkLawsTest`,
 * on the same policy model ([KERNEL-FINDINGS KF465]). The checker used to give the fallback to a single
 * unstarred name alone, so `Holder*` from a nested rule was an entity miss where A12 lands it on the root field
 * and refuses the star there, and an ambiguous first segment of a longer path was a miss where A12 names the
 * ambiguity.
 */
class FirstSegmentShortNameTest {

    @Test
    fun aStarredFirstSegmentReachesTheFallback() {
        val rows = linkedMapOf(
            "Holder*" to listOf(WILDCARD),
            "Holder*/Claims" to listOf(WILDCARD),
            "Holder*/.." to listOf(WILDCARD),
            "BrokerName*" to listOf(WILDCARD),
            "Nope*" to listOf("MVK_INVALID_ENTITY"),
        )
        val actual = rows.keys.associateWith {
            PolicyPathSource.rejections("../Remark", "FieldFilled(Remark) And FieldFilled($it)", inInsured = true)
        }
        assertEquals(rows, actual, "from a nested rule")
    }

    @Test
    fun theFirstSegmentOfALongerPathReachesItToo() {
        val rows = linkedMapOf(
            "Remark/Nope" to listOf(NOT_UNIQUE),
            "Remark*/Nope" to listOf(NOT_UNIQUE),
            "Remark/Claims*" to listOf(NOT_UNIQUE),
            "Age/Nope" to listOf("MVK_INVALID_ENTITY"),
            "Age*/Nope" to listOf(WILDCARD),
            "Age*" to listOf(WILDCARD),
            "Remark*" to listOf(NOT_UNIQUE),
        )
        val actual = rows.keys.associateWith {
            PolicyPathSource.rejections("../Holder", "FieldFilled(Holder) And FieldFilled($it)")
        }
        assertEquals(rows, actual, "from a root-level rule")
    }

    /** A later segment is looked up below the one before it and nowhere else, so a unique short name there misses. */
    @Test
    fun aLaterSegmentNeverReachesIt() {
        val rows = listOf("Insured/Holder", "Broker/Age", "Insured/ClaimRef", "Claims*/Age")
            .associateWith { listOf("MVK_INVALID_ENTITY") }
        val actual = rows.keys.associateWith {
            PolicyPathSource.rejections("../Holder", "FieldFilled(Holder) And FieldFilled($it)")
        }
        assertEquals(rows, actual, "a unique short name after the first segment")
    }

    @Test
    fun aMessageParameterReachesItOnItsOwnVocabulary() {
        val rows = linkedMapOf(
            "see \$Remark\$" to listOf("ERROR_TEXT_FIELDNAME_NOT_UNIQUE"),
            "see \$Remark/Nope\$" to listOf("ERROR_TEXT_FIELDNAME_NOT_UNIQUE"),
            "see \$Age/Nope\$" to listOf("INVALID_ENTITY"),
        )
        val actual = rows.keys.associateWith {
            PolicyPathSource.rejections("../Holder", "FieldFilled(Holder)", message = it)
        }
        assertEquals(rows, actual, "a message parameter")
    }

    /**
     * What the fallback reaching a longer path means for change impact: a namesake declared anywhere moves that
     * path's verdict, so the rule has to be reported although nothing it names was touched. The check is the
     * oracle that it did move.
     */
    @Test
    fun aNamesakeOfTheFirstSegmentReachesTheRule() {
        val base = PolicyPathSource.model(
            ruleInPolicy = PolicyPathSource.rule("../Holder", "FieldFilled(Holder) And FieldFilled(Age/Nope)"))
        val namesake = base.replace(
            """{"type":"Field","id":"f3","name":"BrokerName",""",
            """{"type":"Field","id":"f7","name":"Age","Field":{"fieldType":{"type":"NumberType"}}},
               {"type":"Field","id":"f3","name":"BrokerName",""")
        assertTrue(namesake != base, "the namesake was spliced in")

        val report = impactOf(ModelSource(base), ModelSource(namesake))

        assertEquals(listOf(NOT_UNIQUE), report.edit.introduced.map { it.code }, "the verdict moved: ${report.edit}")
        assertTrue("/Policy/Probe" in report.affected, "and the rule is reached: ${report.impacts}")
    }

    private companion object {
        const val WILDCARD = "MVK_INVALID_WILDCARD"
        const val NOT_UNIQUE = "MVK_FIELDNAME_NOT_UNIQUE"
    }
}

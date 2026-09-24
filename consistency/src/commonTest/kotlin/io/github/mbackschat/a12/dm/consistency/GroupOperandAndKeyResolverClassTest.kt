package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The checker's half of the resolver's segment classes on a GROUP operand and a `RepetitionNotUnique` KEY.
 *
 * Every row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.GroupOperandAndKeyResolverClassLawsTest`, on the same policy model: a miss draws the
 * class of the segment that missed, so an absolute path with an undeclared first segment is the root class, a
 * walk above the root the parent class, and any other miss the entity class.
 */
class GroupOperandAndKeyResolverClassTest {

    private fun rejections(errorField: String, condition: String, inInsured: Boolean = false) =
        PolicyPathSource.rejections(errorField, condition, inInsured)

    private val fixed = linkedMapOf(
        "/Policy/Insured" to emptyList(),
        "/Nope/Insured" to listOf("MVK_INVALID_ROOT_GROUP"),
        "/Nope" to listOf("MVK_INVALID_ROOT_GROUP"),
        "/Claims" to listOf("MVK_INVALID_ROOT_GROUP"),
        "../Insured" to listOf("MVK_TOO_MANY_PARENT_GROUPS"),
        "/Policy/Nope" to listOf("MVK_INVALID_ENTITY"),
        "Nope" to listOf("MVK_INVALID_ENTITY"),
    )

    @Test
    fun aFixedGroupOperandDrawsTheClassOfTheSegmentThatMissed() {
        val actual = linkedMapOf<String, List<String>>()
        val expected = linkedMapOf<String, List<String>>()
        for (carrier in listOf(
            "AtLeastOneGroupFilled(%s, Broker)", "AllGroupsFilled(%s, Broker)", "NotAllGroupsFilled(%s, Broker)",
            "GroupsNotCollectivelyFilled(%s, Broker)", "NoGroupFilled(%s, Broker)",
            "NumberOfFilledGroups(%s, Broker) > 0", "GroupFilled(%s)",
        )) {
            fixed.forEach { (operand, codes) ->
                val row = carrier.replace("%s", operand)
                actual[row] = rejections("../Holder", "FieldFilled(Holder) And $row")
                expected[row] = codes
            }
        }
        assertEquals(expected, actual, "what each fixed group operand draws on every group carrier")
    }

    @Test
    fun aStarredGroupOperandIsJudgedSegmentBySegment() {
        val starred = linkedMapOf(
            "Claims*" to emptyList(),
            "Nope*" to listOf("MVK_INVALID_ENTITY"),
            "Claims*/Nope" to listOf("MVK_INVALID_ENTITY"),
            "Nope/Claims*" to listOf("MVK_INVALID_ENTITY"),
            "/Nope/Claims*" to listOf("MVK_INVALID_ROOT_GROUP"),
            "/Claims*" to listOf("MVK_INVALID_ROOT_GROUP"),
            "Insured*" to listOf("MVK_INVALID_WILDCARD"),
            "Insured*/Nope" to listOf("MVK_INVALID_WILDCARD"),
            "/Policy*/Claims*" to listOf("MVK_INVALID_WILDCARD"),
            "Holder*/Nope" to listOf("MVK_INVALID_WILDCARD"),
            "../Claims*" to listOf("MVK_TOO_MANY_PARENT_GROUPS"),
        )
        val actual = linkedMapOf<String, List<String>>()
        val expected = linkedMapOf<String, List<String>>()
        for (carrier in listOf("AtLeastOneGroupFilled(%s)", "NumberOfFilledGroups(%s) > 0")) {
            starred.forEach { (operand, codes) ->
                val row = carrier.replace("%s", operand)
                actual[row] = rejections("../Holder", "FieldFilled(Holder) And $row")
                expected[row] = codes
            }
        }
        assertEquals(expected, actual, "what each starred group operand draws on both starred carriers")
    }

    @Test
    fun aFieldPathAnswersAtTheFirstGatedSegmentBeforeALaterMiss() {
        val rows = linkedMapOf(
            "Sum(Claims*/Amount) > 0" to emptyList(),
            "Sum(Claims*/Nope) > 0" to listOf("MVK_INVALID_ENTITY"),
            "Sum(Nope*/Amount) > 0" to listOf("MVK_INVALID_ENTITY"),
            "Sum(Insured*/Age) > 0" to listOf("MVK_INVALID_WILDCARD"),
            "Sum(Insured*/Nope) > 0" to listOf("MVK_INVALID_WILDCARD"),
            "Sum(Holder*/Nope) > 0" to listOf("MVK_INVALID_WILDCARD"),
            "Sum(Claims.*/Amount) > 0" to listOf("MVK_POINT_IN_A12_MODEL"),
            "Sum(Claims.*/Nope) > 0" to listOf("MVK_POINT_IN_A12_MODEL"),
            // A12 names the suffix alone; the checker adds the landing class on purpose, as it does where the
            // path resolves (`HModelPathSuffixTest`), because that defect survives fixing the suffix.
            "Sum(Insured.*/Nope) > 0" to listOf("MVK_INVALID_WILDCARD", "MVK_POINT_IN_A12_MODEL"),
            "FieldFilled(Insured*/Nope)" to listOf("MVK_INVALID_WILDCARD"),
            "FieldFilled(Claims.*/Nope)" to listOf("MVK_POINT_IN_A12_MODEL"),
        )
        val actual = linkedMapOf<String, List<String>>()
        rows.keys.forEach { actual[it] = rejections("../Holder", "FieldFilled(Holder) And $it") }
        assertEquals(rows, actual, "what each field path draws once a gated segment precedes a miss")
    }

    @Test
    fun aStarredUpStepAnswersBeforeALaterMiss() {
        val rows = linkedMapOf(
            "Sum(../Claims*/Amount) > 0" to emptyList(),
            "Sum(../Nope) > 0" to listOf("MVK_INVALID_ENTITY"),
            "Sum(..*/Claims*/Amount) > 0" to listOf("MVK_INVALID_WILDCARD_REL"),
            "Sum(..*/Nope) > 0" to listOf("MVK_INVALID_WILDCARD_REL"),
        )
        val actual = linkedMapOf<String, List<String>>()
        rows.keys.forEach { actual[it] = rejections("../Remark", "FieldFilled(Remark) And $it", inInsured = true) }
        assertEquals(rows, actual, "what each up-step path draws from a nested rule group")
    }

    /**
     * The invariant the rows above rest on. Withholding a miss's entity class behind an earlier gate is sound
     * only because another family then names that gate, and the two ask the question in separate code, so no
     * reference that fails to resolve may be left without a rejection. The grid spells every path of up to
     * two segments over this model's vocabulary, relative and absolute, plus a third that misses or gates,
     * from both rule groups; a gate the composition withholds for and no family reports goes red here, where
     * no single row would have been written for it.
     */
    @Test
    fun noUnresolvableReferenceIsLeftWithoutARejection() {
        val vocabulary = listOf(
            "Claims", "Claims*", "Claims.*", "Insured", "Insured*", "Insured.*",
            "Holder", "Holder*", "Amount", "Nope", "Nope*", "..", "..*",
        )
        // A third segment only has to miss or gate once more, so four spellings cover it; the full cube took
        // 5.0 s of the node lane's 10 s per-test timeout (2026-09-24).
        val third = listOf("Amount", "Nope", "Nope*", "Insured*")
        val shorter = vocabulary + vocabulary.flatMap { a -> vocabulary.map { "$a/$it" } }
        val paths = shorter + shorter.drop(vocabulary.size).flatMap { a -> third.map { "$a/$it" } } +
            shorter.flatMap { listOf("/Policy/$it", "/$it") }
        fun rules(errorField: String) = paths.mapIndexed { i, path ->
            """,{"type":"Rule","id":"r$i","name":"Probe$i","Rule":{"errorCondition":"FieldFilled($path)",
               "errorEntityRelPath":"$errorField","errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}"""
        }.joinToString("")
        val unresolved = mutableListOf<String>()
        val silent = listOf(
            PolicyPathSource.model(ruleInPolicy = rules("../Holder")),
            PolicyPathSource.model(ruleInInsured = rules("../Remark")),
        ).flatMap { json ->
            val source = ModelSource(json)
            val rejected = check(source).findings.filter { it.justifiesRejection }.mapNotNull { it.at }.toSet()
            prepare(admit(source) as Admission.Admitted).conditionReferences()
                .filter { it.target !is Reference.Resolved }
                .onEach { unresolved += it.written }
                .filter { it.declaredAt !in rejected }
                .map { "${it.declaredAt}: ${it.written}" }
        }
        // A floor, not a count: it answers whether the grid reached the checker at all.
        assertTrue(unresolved.size > 500, "the grid spelled only ${unresolved.size} unresolvable references")
        assertEquals(emptyList(), silent, "unresolvable references no family rejects")
    }

    @Test
    fun aKeyDrawsTheSegmentClassesAndIsResolvedBeforeTheWildcardGate() {
        val keys = linkedMapOf(
            "Claims/ClaimRef" to emptyList(),
            "/Policy/Claims/ClaimRef" to emptyList(),
            "ClaimRef" to emptyList(),
            "/Policy/Claims/Nope" to listOf("MVK_INVALID_ENTITY"),
            "/Policy/Nope/ClaimRef" to listOf("MVK_INVALID_ENTITY"),
            "Nope" to listOf("MVK_INVALID_ENTITY"),
            "/Nope/Claims/ClaimRef" to listOf("MVK_INVALID_ROOT_GROUP"),
            "../Claims/ClaimRef" to listOf("MVK_TOO_MANY_PARENT_GROUPS"),
            "Remark" to listOf("MVK_FIELDNAME_NOT_UNIQUE"),
            // A12 answers `MVK_NO_WILDCARDS_ALLOWED`. The checker does not carry the key list's own wildcard
            // gate (DEV-API-GAPS DG80), so this row locks a disclosed under-report, never a wrong class.
            "Claims*/ClaimRef" to emptyList(),
            "Insured*/Remark" to listOf("MVK_INVALID_WILDCARD"),
            "Insured*/Nope" to listOf("MVK_INVALID_WILDCARD"),
            "Claims*/Nope" to listOf("MVK_INVALID_ENTITY"),
            "Nope*/ClaimRef" to listOf("MVK_INVALID_ENTITY"),
        )
        val actual = linkedMapOf<String, List<String>>()
        keys.keys.forEach { actual[it] = rejections("../Claims/ClaimRef", "RepetitionNotUnique($it)") }
        assertEquals(keys, actual, "what each RepetitionNotUnique key draws")
    }
}

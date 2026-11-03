package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of the group-list duplicate and overlap gates.
 *
 * Every row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.GroupListDuplicateReachLawsTest`. The ADMITTED rows carry the risk and each is a
 * different mistake: two groups whose names share a prefix (a text comparison), one repeatable group named
 * twice with its wildcard (comparing every operand), and one group named once in each of two calls (reading
 * the condition's names instead of each call's operand list).
 */
class GroupListOperandsTest {

    private val exact = "MVK_DUPLICATE_PARAM1"
    private val overlap = "MVK_DUPLICATE_PARAM2"

    /**
     * A motor-policy model whose one rule carries [condition].
     *
     * `Garage` and `GarageBay` are siblings whose names share a prefix, `Coverage` ▸ `Vehicle` ▸ `Driver` is
     * a three-deep nest, and `Claims` is repeatable. Every condition is conjoined with a reference to the
     * rule's own error field, so no row also draws the unreferenced-error-field finding.
     */
    private fun codes(condition: String): List<String> = checkRead(
        ModelSource(
            """
            {"header":{"modelVersion":"29.4.0","id":"policy","locales":[{"code":"en_US"}]},
             "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Policy","Group":{"elements":[
                {"type":"Field","id":"f0","name":"PolicyNumber","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Group","id":"g1","name":"Coverage","Group":{"elements":[
                  {"type":"Field","id":"f1","name":"Tier","Field":{"fieldType":{"type":"StringType"}}},
                  {"type":"Group","id":"g2","name":"Vehicle","Group":{"elements":[
                    {"type":"Field","id":"f2","name":"Plate","Field":{"fieldType":{"type":"StringType"}}},
                    {"type":"Group","id":"g3","name":"Driver","Group":{"elements":[
                      {"type":"Field","id":"f3","name":"Licence","Field":{"fieldType":{"type":"StringType"}}}
                    ]}}
                  ]}}
                ]}},
                {"type":"Group","id":"g4","name":"Garage","Group":{"elements":[
                  {"type":"Field","id":"f4","name":"GarageName","Field":{"fieldType":{"type":"StringType"}}}
                ]}},
                {"type":"Group","id":"g5","name":"GarageBay","Group":{"elements":[
                  {"type":"Field","id":"f5","name":"BayNumber","Field":{"fieldType":{"type":"StringType"}}}
                ]}},
                {"type":"Group","id":"g6","name":"Claims","Group":{"repeatability":12,
                  "elements":[
                  {"type":"Field","id":"f6","name":"ClaimAmount","Field":{"fieldType":{"type":"NumberType"}}},
                  {"type":"Group","id":"g7","name":"Witness","Group":{"elements":[
                    {"type":"Field","id":"f7","name":"WitnessName","Field":{"fieldType":{"type":"StringType"}}}
                  ]}}
                ]}},
                {"type":"Rule","id":"r0","name":"Probe","Rule":{
                  "errorCondition":"FieldFilled(PolicyNumber) And ${'$'}CONDITION",
                  "errorEntityRelPath":"../PolicyNumber",
                  "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}
              ]}}
             ]}}}
            """.trimIndent().replace("\$CONDITION", condition),
        ),
    ).findings.map { it.code }

    @Test
    fun whichOperandPairsCountAsTheSameGroupTwice() {
        val actual = mutableMapOf<String, List<String>>()
        actual["the same group twice, both relative — the control"] = codes("AllGroupsFilled(Garage, Garage)")
        actual["two distinct groups — the control"] = codes("AllGroupsFilled(Garage, Coverage)")
        actual["a relative path beside the ABSOLUTE spelling of the same group"] =
            codes("AllGroupsFilled(Garage, /Policy/Garage)")
        actual["an ancestor and its CHILD"] = codes("AllGroupsFilled(Coverage, Coverage/Vehicle)")
        actual["an ancestor and its GRANDCHILD"] = codes("AllGroupsFilled(Coverage, Coverage/Vehicle/Driver)")
        actual["the descendant named FIRST"] = codes("AllGroupsFilled(Coverage/Vehicle, Coverage)")
        actual["two groups whose NAMES share a prefix"] = codes("AllGroupsFilled(Garage, GarageBay)")

        val expected = mutableMapOf<String, List<String>>()
        expected["the same group twice, both relative — the control"] = listOf(exact)
        expected["two distinct groups — the control"] = emptyList()
        // Identity is the RESOLVED element: comparing the authored text would miss this one.
        expected["a relative path beside the ABSOLUTE spelling of the same group"] = listOf(exact)
        expected["an ancestor and its CHILD"] = listOf(overlap)
        expected["an ancestor and its GRANDCHILD"] = listOf(overlap)
        expected["the descendant named FIRST"] = listOf(overlap)
        // …and containment is by path STEP: `Garage` does not contain `GarageBay`, and A12 admits the pair.
        expected["two groups whose NAMES share a prefix"] = emptyList()
        assertEquals(expected, actual, "which operand pairs count as the same group twice")
    }

    /**
     * The shapes this family stays SILENT on, each because A12 answers them with something else.
     *
     * The starred row is the over-rejection guard: A12 ADMITS one repeatable group named twice with its
     * wildcard, so an implementation comparing every operand refuses a legal model.
     */
    @Test
    fun theShapesItDeliberatelyDoesNotReport() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a starred repeatable named twice"] = codes("NumberOfFilledGroups(Claims*, Claims*) > 0")
        actual["a starred repeatable beside its UNSTARRED spelling"] =
            codes("NumberOfFilledGroups(Claims*, Claims) > 0")
        actual["an UNRESOLVABLE operand beside an exact duplicate"] =
            codes("AllGroupsFilled(Nowhere, Garage, Garage)")
        actual["the same group once in EACH of two calls"] =
            codes("AllGroupsFilled(Garage, Coverage) And AllGroupsFilled(Garage, GarageBay)")

        val expected = mutableMapOf<String, List<String>>()
        expected["a starred repeatable named twice"] = emptyList()
        // After the starred operand is skipped one operand is left, so there is no pair: A12 answers the
        // mixed row with the operand-scope walk on the unstarred spelling rather than with a duplicate
        // (`rulekit.validate.laws.GroupListDuplicateReachLawsTest`).
        expected["a starred repeatable beside its UNSTARRED spelling"] = listOf("MVK_NO_WILDCARD")
        // A12 reports the entity and nothing about the duplicate, so reporting both would name two fixes.
        expected["an UNRESOLVABLE operand beside an exact duplicate"] = listOf("MVK_INVALID_ENTITY")
        expected["the same group once in EACH of two calls"] = emptyList()
        assertEquals(expected, actual, "the shapes the group-list operand gates deliberately do not report")
    }

    /**
     * A star and a `$` capture each take an operand out of the exact test and leave it in the nested one, as
     * `adapter.laws.CheckerOverlapIdentityAgreementTest` measures against the kernel (KF325, corrected 2026-10-09).
     */
    @Test
    fun aStarAndACaptureLeaveTheExactTestAndStayInTheNestedOne() {
        fun filtered(leaf: String) = codes("Sum(Claims*/ClaimAmount Having [Claims/ClaimAmount] > 0 And $leaf) > 0")
        val actual = linkedMapOf(
            "a starred group beside its starred subgroup" to codes("NoGroupFilled(Claims*, Claims*/Witness)"),
            "a group beside its own capture" to filtered("AllGroupsFilled(Coverage, ${'$'}/Policy/Coverage)"),
            "a group beside its captured subgroup" to filtered("AllGroupsFilled(Coverage, ${'$'}/Policy/Coverage/Vehicle)"),
            "a captured group beside its subgroup" to filtered("AllGroupsFilled(${'$'}/Policy/Coverage, Coverage/Vehicle)"),
            "a capture twice" to filtered("AllGroupsFilled(${'$'}/Policy/Coverage, ${'$'}/Policy/Coverage)"),
        )
        val expected = linkedMapOf(
            "a starred group beside its starred subgroup" to listOf(overlap),
            "a group beside its own capture" to emptyList(),
            "a group beside its captured subgroup" to listOf(overlap),
            "a captured group beside its subgroup" to listOf(overlap),
            "a capture twice" to listOf(exact),
        )
        assertEquals(expected, actual, "the star and the capture split the two duplicate tests")
    }

    /**
     * A call read to its end ahead of a syntax defect keeps its duplicate gate, which A12 names in place of
     * the syntax class (`rulekit.validate.laws.GroupListDuplicateReachLawsTest
     * .theSyntaxPhasesDoNotPreEmptTheDuplicateGate`): the gate is raised at the call's closing parenthesis,
     * before the parser meets the stray one or the lexer the unterminated quote.
     */
    @Test
    fun aDuplicateAheadOfASyntaxDefectIsTheClassReported() {
        val actual = mutableMapOf<String, List<String>>()
        actual["an UNGRAMMATICAL condition with a duplicate"] = codes("AllGroupsFilled(Garage, Garage))")
        actual["an UNLEXABLE condition with a duplicate"] =
            codes("""AllGroupsFilled(Garage, Garage) And FieldFilled(\"GarageName)""")

        val expected = mutableMapOf<String, List<String>>()
        expected["an UNGRAMMATICAL condition with a duplicate"] = listOf("MVK_DUPLICATE_PARAM1")
        expected["an UNLEXABLE condition with a duplicate"] = listOf("MVK_DUPLICATE_PARAM1")
        assertEquals(expected, actual, "a duplicate ahead of a syntax defect is the class reported")
    }

    /** The gates hold on every carrier that takes a group list, including the count's `> 0` tail. */
    @Test
    fun everyCarrierThatTakesAGroupList() {
        val actual = mutableMapOf<String, List<String>>()
        actual["AllGroupsFilled"] = codes("AllGroupsFilled(Garage, Garage)")
        actual["NotAllGroupsFilled"] = codes("NotAllGroupsFilled(Garage, Garage)")
        actual["GroupsNotCollectivelyFilled"] = codes("GroupsNotCollectivelyFilled(Garage, Garage)")
        actual["AtLeastOneGroupFilled"] = codes("AtLeastOneGroupFilled(Coverage, Coverage/Vehicle)")
        actual["NoGroupFilled"] = codes("NoGroupFilled(Coverage, Coverage/Vehicle)")
        actual["NumberOfFilledGroups"] = codes("NumberOfFilledGroups(Garage, Garage) > 0")

        val expected = mutableMapOf<String, List<String>>()
        expected["AllGroupsFilled"] = listOf(exact)
        expected["NotAllGroupsFilled"] = listOf(exact)
        expected["GroupsNotCollectivelyFilled"] = listOf(exact)
        expected["AtLeastOneGroupFilled"] = listOf(overlap)
        expected["NoGroupFilled"] = listOf(overlap)
        expected["NumberOfFilledGroups"] = listOf(exact)
        assertEquals(expected, actual, "every carrier that takes a group list")
    }

    /**
     * Within one call the exact duplicate wins, and across calls the first violation answers, as in A12
     * (`rulekit.validate.laws.GroupListDuplicateReachLawsTest.theGateReadsOneCallsOperandList`). Equal codes
     * at separate calls still stop at the first action (A12ReportStreamTest's R5-LC callback cross).
     */
    @Test
    fun exactWinsInsideACallAndTheFirstViolatingCallAnswers() {
        val actual = mutableMapOf<String, List<String>>()
        actual["an exact duplicate and an overlap in ONE call"] =
            codes("AllGroupsFilled(Coverage, Coverage/Vehicle, Garage, Garage)")
        actual["an overlap in one call and an exact duplicate in another"] =
            codes("AllGroupsFilled(Coverage, Coverage/Vehicle) And AllGroupsFilled(Garage, Garage)")
        actual["an exact duplicate in each of two calls"] =
            codes("AllGroupsFilled(Garage, Garage) And AllGroupsFilled(Coverage, Coverage)")
        val expected = mutableMapOf<String, List<String>>()
        expected["an exact duplicate and an overlap in ONE call"] = listOf(exact)
        expected["an overlap in one call and an exact duplicate in another"] = listOf(overlap)
        expected["an exact duplicate in each of two calls"] = listOf(exact)
        assertEquals(expected, actual, "which violating calls are reported")
    }

    /**
     * The FIELD-operand law, whose content is which carriers do NOT have it.
     *
     * Five group-list quantifiers admit a field operand and only the count and the two scalars refuse one, so
     * the five ADMITTED rows are the ones that carry the risk: a family built from the operators' names
     * would refuse every one of them.
     */
    @Test
    fun whichCarriersRefuseAFieldOperand() {
        val noFields = "MVK_NO_FIELDS_ALLOWED"
        val actual = mutableMapOf<String, List<String>>()
        actual["all | a FIELD beside a group"] = codes("AllGroupsFilled(Garage/GarageName, Coverage)")
        actual["notAll | a FIELD beside a group"] = codes("NotAllGroupsFilled(Garage/GarageName, Coverage)")
        actual["notCollectively | a FIELD beside a group"] =
            codes("GroupsNotCollectivelyFilled(Garage/GarageName, Coverage)")
        actual["atLeastOne | a FIELD beside a group"] =
            codes("AtLeastOneGroupFilled(Garage/GarageName, Coverage)")
        actual["noGroup | a FIELD beside a group"] = codes("NoGroupFilled(Garage/GarageName, Coverage)")
        actual["count | a FIELD beside a group"] =
            codes("NumberOfFilledGroups(Garage/GarageName, Coverage) > 0")
        actual["scalar GroupFilled | a FIELD"] = codes("GroupFilled(Garage/GarageName)")
        actual["scalar GroupNotFilled | a FIELD"] = codes("GroupNotFilled(Garage/GarageName)")
        actual["scalar GroupFilled | a group — the control"] = codes("GroupFilled(Garage)")
        actual["scalar GroupFilled | the RuleGroup keyword — the control"] = codes("GroupFilled(RuleGroup)")

        val expected = mutableMapOf<String, List<String>>()
        expected["all | a FIELD beside a group"] = emptyList()
        expected["notAll | a FIELD beside a group"] = emptyList()
        expected["notCollectively | a FIELD beside a group"] = emptyList()
        expected["atLeastOne | a FIELD beside a group"] = emptyList()
        expected["noGroup | a FIELD beside a group"] = emptyList()
        expected["count | a FIELD beside a group"] = listOf(noFields)
        expected["scalar GroupFilled | a FIELD"] = listOf(noFields)
        expected["scalar GroupNotFilled | a FIELD"] = listOf(noFields)
        expected["scalar GroupFilled | a group — the control"] = emptyList()
        // `RuleGroup` is a keyword rather than an entity, so the call names nothing to judge.
        expected["scalar GroupFilled | the RuleGroup keyword — the control"] = emptyList()
        assertEquals(expected, actual, "which carriers refuse a field operand")
    }

    /** The kind gate runs last of the gates on one list, and outruns only the arity class A12 owns alone. */
    @Test
    fun theKindGateRunsAfterEveryOtherGateOnTheSameList() {
        val actual = mutableMapOf<String, List<String>>()
        actual["count | the same field twice"] =
            codes("NumberOfFilledGroups(Garage/GarageName, Garage/GarageName) > 0")
        actual["count | a field beside its own enclosing group"] =
            codes("NumberOfFilledGroups(Garage/GarageName, Garage) > 0")
        actual["count | a field as the SOLE operand"] = codes("NumberOfFilledGroups(Garage/GarageName) > 0")

        val expected = mutableMapOf<String, List<String>>()
        expected["count | the same field twice"] = listOf(exact)
        expected["count | a field beside its own enclosing group"] = listOf(overlap)
        // A12 answers the sole FIXED operand with its arity class ordinarily and with the kind class here,
        // so the kind gate outruns arity — the one gate it is ahead of.
        expected["count | a field as the SOLE operand"] = listOf("MVK_NO_FIELDS_ALLOWED")
        assertEquals(expected, actual, "the kind gate runs after every other gate on the same list")
    }

    /**
     * A GERMAN model draws the same finding, and the carrier is recognised by its German spelling.
     *
     * The family names its six carriers by catalog id and resolves each to the model's own language, so this
     * row is what stops that resolution from being an English-only lookup dressed as a general one. The
     * Kernel binds a name against exactly one language's terminals, so `AlleKontexteAngegeben` is an
     * operator here and would be an ordinary field name in the English model above.
     */
    @Test
    fun aGermanModelDrawsTheSameFinding() {
        val finding = checkRead(
            ModelSource(
                """
                {"header":{"modelVersion":"29.4.0","id":"police","locales":[{"code":"de_DE"}]},
                 "content":{"modelInfo":{"name":"model"},"modelConfig":{"conditionLanguage":{"code":"de_DE"}},"modelRoot":{"rootGroups":[
                  {"type":"Group","id":"g0","name":"Police","Group":{"elements":[
                    {"type":"Field","id":"f0","name":"Nummer","Field":{"fieldType":{"type":"StringType"}}},
                    {"type":"Group","id":"g1","name":"Werkstatt","Group":{"elements":[
                      {"type":"Field","id":"f1","name":"Ort","Field":{"fieldType":{"type":"StringType"}}}
                    ]}},
                    {"type":"Rule","id":"r0","name":"Probe","Rule":{
                      "errorCondition":"FeldAngegeben(Nummer) Und AlleKontexteAngegeben(Werkstatt, Werkstatt)",
                      "errorEntityRelPath":"../Nummer",
                      "errorCode":"C1","errorMessage":[{"locale":"de_DE","text":"x"}]}}
                  ]}}
                 ]}}}
                """.trimIndent(),
            ),
        ).findings.single()
        assertEquals(exact, finding.code)
        assertEquals("/Police/Probe", finding.at)
        assertEquals(true, finding.explain.contains("AlleKontexteAngegeben"), finding.explain)
    }

    /**
     * The ROOT-operand law over the same list — three carriers refuse a root outright, three admit a sole one.
     *
     * The fixture's second root `/Broker` is disjoint from `/Policy`, which is what lets a root operand be
     * seen at all: beside the rule's own root every co-operand is also its descendant, and the overlap gate
     * answers first. `/Broker` is FIXED and the third root `/Agencies` repeats, the shape of
     * `rulekit.validate.laws.GroupListRootOperandLawsTest`: this fixture once made `/Broker` repeatable for its
     * one starred row, which turned every unstarred row into one no lock had measured.
     */
    private fun rootCodes(condition: String, inAgencies: Boolean = false): List<String> {
        val probe = """
                {"type":"Rule","id":"r0","name":"Probe","Rule":{
                  "errorCondition":"FieldFilled(${if (inAgencies) "AgencyCode" else "PolicyNumber"}) And ${'$'}CONDITION",
                  "errorEntityRelPath":"../${if (inAgencies) "AgencyCode" else "PolicyNumber"}",
                  "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}"""
        return checkRead(
            ModelSource(
                """
                {"header":{"modelVersion":"29.4.0","id":"policy","locales":[{"code":"en_US"}]},
                 "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
                  {"type":"Group","id":"g0","name":"Policy","Group":{"elements":[
                    {"type":"Field","id":"f0","name":"PolicyNumber","Field":{"fieldType":{"type":"StringType"}}},
                    {"type":"Group","id":"g1","name":"Garage","Group":{"elements":[
                      {"type":"Field","id":"f1","name":"GarageName","Field":{"fieldType":{"type":"StringType"}}}
                    ]}},
                    {"type":"Group","id":"g3","name":"Workshop","Group":{"elements":[
                      {"type":"Field","id":"f3","name":"Bench","Field":{"fieldType":{"type":"StringType"}}}
                    ]}}${if (inAgencies) "" else ",$probe"}
                  ]}},
                  {"type":"Group","id":"g2","name":"Broker","Group":{"elements":[
                    {"type":"Field","id":"f2","name":"BrokerName","Field":{"fieldType":{"type":"StringType"}}}
                  ]}},
                  {"type":"Group","id":"g4","name":"Agencies","Group":{"repeatability":4,"elements":[
                    {"type":"Field","id":"f4","name":"AgencyCode","Field":{"fieldType":{"type":"StringType"}}}${if (inAgencies) ",$probe" else ""}
                  ]}}
                 ]}}}
                """.trimIndent().replace("\$CONDITION", condition),
            ),
        ).findings.map { it.code }
    }

    @Test
    fun whichCarriersRefuseARootOperandAndWhichAdmitASoleOne() {
        val referenced = "MVK_ROOT_GROUP_REFERENCED"
        val withOthers = "MVK_ROOT_GROUP_WITH_OTHER_PARAMETERS"
        val actual = mutableMapOf<String, List<String>>()
        actual["all | sole root"] = rootCodes("AllGroupsFilled(/Broker)")
        actual["notAll | root + disjoint"] = rootCodes("NotAllGroupsFilled(/Broker, Garage)")
        actual["notCollectively | root + disjoint"] = rootCodes("GroupsNotCollectivelyFilled(/Broker, Garage)")
        actual["atLeastOne | sole root"] = rootCodes("AtLeastOneGroupFilled(/Broker)")
        actual["noGroup | sole root"] = rootCodes("NoGroupFilled(/Broker)")
        actual["atLeastOne | root + disjoint"] = rootCodes("AtLeastOneGroupFilled(/Broker, Garage)")
        actual["count | root + disjoint"] = rootCodes("NumberOfFilledGroups(/Broker, Garage) > 0")
        actual["all | root named SECOND"] = rootCodes("AllGroupsFilled(Garage, /Broker)")
        actual["all | a STARRED root beside another operand"] = rootCodes("AllGroupsFilled(/Agencies*, Garage)")
        actual["all | two groups BELOW the root — the control"] =
            rootCodes("AllGroupsFilled(Garage, /Policy/Workshop)")

        val expected = mutableMapOf<String, List<String>>()
        expected["all | sole root"] = listOf(referenced)
        expected["notAll | root + disjoint"] = listOf(referenced)
        expected["notCollectively | root + disjoint"] = listOf(referenced)
        // The three carriers that admit a sole root. A12 answers the count's sole FIXED operand with its
        // arity class instead, which this family does not report either way.
        expected["atLeastOne | sole root"] = emptyList()
        expected["noGroup | sole root"] = emptyList()
        expected["atLeastOne | root + disjoint"] = listOf(withOthers)
        expected["count | root + disjoint"] = listOf(withOthers)
        expected["all | root named SECOND"] = listOf(referenced)
        // The asymmetry against the duplicate gate: a wildcard takes an operand out of that comparison and
        // leaves it fully inside this one.
        expected["all | a STARRED root beside another operand"] = listOf(referenced)
        expected["all | two groups BELOW the root — the control"] = emptyList()
        assertEquals(expected, actual, "which carriers refuse a root operand")
    }

    /**
     * A REPEATABLE root read without its star is walked first, like any repeatable group, and judged as a root
     * only behind the walk: from a rule inside it the walk passes and the root classes answer as for a fixed
     * root. Row for row with `rulekit.validate.laws.GroupListRootOperandLawsTest`: the walk answers alone from
     * outside, because an operand refused on read silences every gate of the operator's own ([KF443]).
     */
    @Test
    fun anUnstarredRepeatableRootIsWalkedBeforeItIsJudgedAsARoot() {
        val noWildcard = "MVK_NO_WILDCARD"
        val referenced = "MVK_ROOT_GROUP_REFERENCED"
        val withOthers = "MVK_ROOT_GROUP_WITH_OTHER_PARAMETERS"
        val outside = linkedMapOf(
            "AllGroupsFilled(/Agencies)" to listOf(noWildcard),
            "AtLeastOneGroupFilled(/Agencies)" to listOf(noWildcard),
            "NoGroupFilled(/Agencies)" to listOf(noWildcard),
            "AtLeastOneGroupFilled(/Agencies, Garage)" to listOf(noWildcard),
            "NumberOfFilledGroups(/Agencies, Garage) > 0" to listOf(noWildcard),
            "GroupFilled(/Agencies)" to listOf(noWildcard),
            "FieldFilled(/Agencies)" to listOf(noWildcard),
            "AtLeastOneFieldFilled(/Agencies)" to listOf(noWildcard),
            "Sum(/Agencies) > 0" to listOf(noWildcard),
            "FieldFilled(/Agencies/AgencyCode)" to listOf(noWildcard),
        )
        assertEquals(outside, outside.keys.associateWith { rootCodes(it) }, "from a rule in another root")
        val inside = linkedMapOf(
            "AllGroupsFilled(/Agencies)" to listOf(referenced),
            "AtLeastOneGroupFilled(/Agencies)" to emptyList(),
            "AtLeastOneGroupFilled(/Agencies, /Broker)" to listOf(withOthers),
            "GroupFilled(/Agencies)" to emptyList(),
            "FieldFilled(/Agencies)" to listOf(referenced),
        )
        assertEquals(inside, inside.keys.associateWith { rootCodes(it, inAgencies = true) }, "from a rule inside it")
        val starred = linkedMapOf(
            "AllGroupsFilled(/Agencies*)" to listOf(referenced),
            "AtLeastOneGroupFilled(/Agencies*)" to emptyList(),
            "AtLeastOneGroupFilled(/Agencies*, Garage)" to listOf(withOthers),
            "NumberOfFilledGroups(/Agencies*, Garage) > 0" to listOf(withOthers),
            "FieldFilled(/Agencies*)" to listOf(referenced),
        )
        assertEquals(starred, starred.keys.associateWith { rootCodes(it) }, "the root starred")
    }

    /** An overlap pre-empts the root class in A12, so the finding for that list names the overlap. */
    @Test
    fun anOverlapPreEmptsTheRootClass() {
        assertEquals(listOf(overlap), rootCodes("AllGroupsFilled(/Policy, Garage)"))
    }

    /** The finding names both operands and the carrier, since a condition can hold several calls. */
    @Test
    fun theFindingNamesTheOperandsAndTheCarrier() {
        val finding = checkRead(
            ModelSource(
                """
                {"header":{"modelVersion":"29.4.0","id":"policy","locales":[{"code":"en_US"}]},
                 "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
                  {"type":"Group","id":"g0","name":"Policy","Group":{"elements":[
                    {"type":"Field","id":"f0","name":"Number","Field":{"fieldType":{"type":"StringType"}}},
                    {"type":"Group","id":"g1","name":"Coverage","Group":{"elements":[
                      {"type":"Field","id":"f1","name":"Tier","Field":{"fieldType":{"type":"StringType"}}},
                      {"type":"Group","id":"g2","name":"Vehicle","Group":{"elements":[
                        {"type":"Field","id":"f2","name":"Plate","Field":{"fieldType":{"type":"StringType"}}}
                      ]}}
                    ]}},
                    {"type":"Rule","id":"r0","name":"Probe","Rule":{
                      "errorCondition":"FieldFilled(Number) And AllGroupsFilled(Coverage, Coverage/Vehicle)",
                      "errorEntityRelPath":"../Number",
                      "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}
                  ]}}
                 ]}}}
                """.trimIndent(),
            ),
        ).findings.single()
        assertEquals(overlap, finding.code)
        assertEquals("/Policy/Probe", finding.at)
        assertEquals(true, finding.explain.contains("/Policy/Coverage`"), finding.explain)
        assertEquals(true, finding.explain.contains("/Policy/Coverage/Vehicle"), finding.explain)
        assertEquals(true, finding.explain.contains("AllGroupsFilled"), finding.explain)
    }
}

package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of "a repeatable level a star leaves UNSTARRED is a per-row read" ([KERNEL-FINDINGS KF466]):
 * containment answers it into a target outside the declaring group, and the level must be bound otherwise.
 *
 * Mirrors `rulekit.validate.laws.ComputationStarBindingLawsTest` row for row, then
 * `rulekit.validate.laws.PartialStarContainmentLawsTest` on the same sheet with a fixed `Ledger` added. The
 * spellings are the ones those locks pin, row by row, as the text the kernel was handed.
 *
 * Every row compares the checker's COMPLETE class list against A12's one class, so a co-firing second class
 * is as visible as a missing one.
 */
class StarBindingContainmentTest {

    private val noWildcard = "MVK_NO_WILDCARD"
    private val notInRuleGroup = "MVK_ERROR_FIELD_NOT_IN_RULEGROUP"
    private val noIndex = "MVK_ERROR_FIELD_NO_INDEX"

    private val summary = "/Fulfilment/Summary"
    private val shipments = "/Fulfilment/Shipments"
    private val parcels = "/Fulfilment/Shipments/Parcels"
    private val ledger = "/Fulfilment/Ledger"

    @Test
    fun belowTheFirstStarAContainedTargetRefusesTheLevelWhateverBindsIt() {
        val actual = mutableMapOf<String, List<String>>()
        actual["Sum into the fixed target"] = codes(Sheet(), summary, "../Result", "Sum(../Shipments*/Parcels/Weight)")
        actual["Sum into a target in Shipments"] =
            codes(Sheet(), shipments, "../ShipResult", "Sum(../Shipments*/Parcels/Weight)")
        actual["Sum into a target in Parcels, the level itself"] =
            codes(Sheet(), parcels, "../Share", "Sum(../../Shipments*/Parcels/Weight)")
        actual["Sum, Shipments index-keyed"] =
            codes(Sheet(shipmentsKeyed = true), summary, "../Result", "Sum(../Shipments*/Parcels/Weight)")
        actual["Sum, Parcels index-keyed"] =
            codes(Sheet(parcelsKeyed = true), summary, "../Result", "Sum(../Shipments*/Parcels/Weight)")
        actual["the group count into the fixed target"] =
            codes(Sheet(), summary, "../Result", "NumberOfFilledGroups(../Shipments*/Parcels/Seal)")

        val expected = actual.keys.associateWith { listOf(noWildcard) }
        assertEquals(expected, actual, "an unstarred level below the first star")
    }

    @Test
    fun aboveTheFirstStarContainmentAnswersBeforeTheOutermostLevel() {
        val actual = mutableMapOf<String, List<String>>()
        actual["declared in Shipments, relative"] =
            codes(Sheet(), shipments, "../../Summary/Result", "Sum(Parcels*/Weight)")
        actual["declared in Shipments, absolute"] =
            codes(Sheet(), shipments, "../../Summary/Result", "Sum(/Fulfilment/Shipments/Parcels*/Weight)")
        actual["declared in Summary"] = codes(Sheet(), summary, "../Result", "Sum(../Shipments/Parcels*/Weight)")
        actual["declared in Summary, Shipments index-keyed"] =
            codes(Sheet(shipmentsKeyed = true), summary, "../Result", "Sum(../Shipments/Parcels*/Weight)")
        actual["declared in Summary, Parcels index-keyed"] =
            codes(Sheet(parcelsKeyed = true), summary, "../Result", "Sum(../Shipments/Parcels*/Weight)")
        actual["the group count, declared in Shipments"] =
            codes(Sheet(), shipments, "../../Summary/Result", "NumberOfFilledGroups(Parcels*/Seal)")
        actual["the group count, declared in Summary"] =
            codes(Sheet(), summary, "../Result", "NumberOfFilledGroups(../Shipments/Parcels*/Seal)")
        actual["the group count, declared in Summary, Shipments index-keyed"] = codes(
            Sheet(shipmentsKeyed = true), summary, "../Result", "NumberOfFilledGroups(../Shipments/Parcels*/Seal)",
        )

        val expected = mutableMapOf<String, List<String>>()
        expected["declared in Shipments, relative"] = listOf(notInRuleGroup)
        expected["declared in Shipments, absolute"] = listOf(notInRuleGroup)
        expected["declared in Summary"] = listOf(noWildcard)
        expected["declared in Summary, Shipments index-keyed"] = listOf(noIndex)
        expected["declared in Summary, Parcels index-keyed"] = listOf(noWildcard)
        expected["the group count, declared in Shipments"] = listOf(notInRuleGroup)
        expected["the group count, declared in Summary"] = listOf(noWildcard)
        expected["the group count, declared in Summary, Shipments index-keyed"] = listOf(noIndex)
        assertEquals(expected, actual, "an unstarred level above the first star, into a fixed target")
    }

    @Test
    fun aTargetThatBindsTheLevelAdmitsTheSameOperand() {
        val actual = mutableMapOf<String, List<String>>()
        actual["Sum"] = codes(Sheet(), shipments, "../ShipResult", "Sum(Parcels*/Weight)")
        actual["the group count"] = codes(Sheet(), shipments, "../ShipResult", "NumberOfFilledGroups(Parcels*/Seal)")
        actual["Sum, Shipments index-keyed"] =
            codes(Sheet(shipmentsKeyed = true), shipments, "../ShipResult", "Sum(Parcels*/Weight)")
        actual["a plain read of the deeper level"] = codes(Sheet(), shipments, "../ShipResult", "[Parcels/Weight] + 1")
        actual["a plain read of the deeper level, Parcels index-keyed"] =
            codes(Sheet(parcelsKeyed = true), shipments, "../ShipResult", "[Parcels/Weight] + 1")
        actual["a plain read of the deeper level, Shipments index-keyed"] =
            codes(Sheet(shipmentsKeyed = true), shipments, "../ShipResult", "[Parcels/Weight] + 1")

        val expected = mutableMapOf<String, List<String>>()
        expected["Sum"] = emptyList()
        expected["the group count"] = emptyList()
        expected["Sum, Shipments index-keyed"] = emptyList()
        expected["a plain read of the deeper level"] = listOf(noWildcard)
        expected["a plain read of the deeper level, Parcels index-keyed"] = listOf(noWildcard)
        expected["a plain read of the deeper level, Shipments index-keyed"] = listOf(noWildcard)
        assertEquals(expected, actual, "a target inside the unstarred level")
    }

    @Test
    fun aGroupOperandsOwnLevelFollowsTheSameTable() {
        val actual = mutableMapOf<String, List<String>>()
        actual["the group itself"] = codes(Sheet(), summary, "../Result", "NumberOfFilledGroups(../Shipments)")
        actual["the group itself, index-keyed"] =
            codes(Sheet(shipmentsKeyed = true), summary, "../Result", "NumberOfFilledGroups(../Shipments)")
        actual["a starred group below it"] =
            codes(Sheet(), summary, "../Result", "NumberOfFilledGroups(../Shipments/Parcels*)")
        actual["a starred group below it, index-keyed"] =
            codes(Sheet(shipmentsKeyed = true), summary, "../Result", "NumberOfFilledGroups(../Shipments/Parcels*)")

        val expected = mutableMapOf<String, List<String>>()
        expected["the group itself"] = listOf(noWildcard)
        expected["the group itself, index-keyed"] = listOf(noIndex)
        expected["a starred group below it"] = listOf(noWildcard)
        expected["a starred group below it, index-keyed"] = listOf(noIndex)
        assertEquals(expected, actual, "the group count's own level, from Summary")
    }

    @Test
    fun aPlainReadFollowsTheSameTable() {
        val actual = mutableMapOf<String, List<String>>()
        actual["own-level read, declared in Shipments"] =
            codes(Sheet(), shipments, "../../Summary/Result", "[ShipWeight] + 1")
        actual["own-level read, declared in Summary"] =
            codes(Sheet(), summary, "../Result", "[../Shipments/ShipWeight] + 1")
        actual["own-level read, declared in Summary, Shipments index-keyed"] =
            codes(Sheet(shipmentsKeyed = true), summary, "../Result", "[../Shipments/ShipWeight] + 1")
        actual["deeper read, declared in Shipments"] =
            codes(Sheet(), shipments, "../../Summary/Result", "[Parcels/Weight] + 1")
        actual["deeper read, declared in Shipments, Parcels index-keyed"] =
            codes(Sheet(parcelsKeyed = true), shipments, "../../Summary/Result", "[Parcels/Weight] + 1")
        actual["deeper read, declared in Summary, Parcels index-keyed"] =
            codes(Sheet(parcelsKeyed = true), summary, "../Result", "[../Shipments/Parcels/Weight] + 1")

        val expected = mutableMapOf<String, List<String>>()
        expected["own-level read, declared in Shipments"] = listOf(notInRuleGroup)
        expected["own-level read, declared in Summary"] = listOf(noWildcard)
        expected["own-level read, declared in Summary, Shipments index-keyed"] = listOf(noIndex)
        expected["deeper read, declared in Shipments"] = listOf(notInRuleGroup)
        expected["deeper read, declared in Shipments, Parcels index-keyed"] = listOf(notInRuleGroup)
        expected["deeper read, declared in Summary, Parcels index-keyed"] = listOf(noWildcard)
        assertEquals(expected, actual, "a plain read into the fixed target")
    }

    /**
     * `PartialStarContainmentLawsTest`: with the target outside the declaring group, a partial star is per-row;
     * each uncontained row has its contained twin, declared in `Summary`, on the same sheet.
     */
    @Test
    fun onTheComputationArmEveryUnstarredLevelIsPerRow() {
        val sheet = Sheet(ledger = true)
        val keyed = Sheet(shipmentsKeyed = true, ledger = true)
        val target = "../../Summary/Result"
        val actual = mutableMapOf<String, List<String>>()
        actual["Ledger | Sum, above the first star"] = codes(sheet, ledger, target, "Sum(../Shipments/Parcels*/Weight)")
        actual["Summary | Sum, above, contained — control"] =
            codes(sheet, summary, "../Result", "Sum(../Shipments/Parcels*/Weight)")
        actual["Ledger | Sum, below the first star"] = codes(sheet, ledger, target, "Sum(../Shipments*/Parcels/Weight)")
        actual["Shipments | Sum, below the first star"] =
            codes(sheet, shipments, target, "Sum(../Shipments*/Parcels/Weight)")
        actual["Summary | Sum, below, contained — control"] =
            codes(sheet, summary, "../Result", "Sum(../Shipments*/Parcels/Weight)")
        actual["Ledger | the group count, above"] =
            codes(sheet, ledger, target, "NumberOfFilledGroups(../Shipments/Parcels*/Seal)")
        actual["Ledger | the group count, below"] =
            codes(sheet, ledger, target, "NumberOfFilledGroups(../Shipments*/Parcels/Seal)")
        actual["Ledger | Sum, above, Shipments index-keyed"] =
            codes(keyed, ledger, target, "Sum(../Shipments/Parcels*/Weight)")
        actual["Summary | Sum, above, Shipments index-keyed, contained — control"] =
            codes(keyed, summary, "../Result", "Sum(../Shipments/Parcels*/Weight)")
        actual["Ledger | Sum, every level starred — control"] =
            codes(sheet, ledger, target, "Sum(../Shipments*/Parcels*/Weight)")
        actual["Shipments | Sum, every level starred — control"] =
            codes(sheet, shipments, target, "Sum(../Shipments*/Parcels*/Weight)")

        val expected = actual.keys.associateWith { listOf(notInRuleGroup) }.toMutableMap()
        expected["Summary | Sum, above, contained — control"] = listOf(noWildcard)
        expected["Summary | Sum, below, contained — control"] = listOf(noWildcard)
        expected["Summary | Sum, above, Shipments index-keyed, contained — control"] = listOf(noIndex)
        expected["Ledger | Sum, every level starred — control"] = emptyList()
        expected["Shipments | Sum, every level starred — control"] = emptyList()
        assertEquals(expected, actual, "a partial star into a target outside the declaring group")
    }

    /** A repeatable target outside the declaring group is refused whatever the operand stars (KF212). */
    @Test
    fun aTargetBindingTheLevelFromOutsideTheDeclaringGroupIsRefusedToo() {
        val sheet = Sheet(ledger = true)
        val target = "../../Shipments/ShipResult"
        val actual = mutableMapOf<String, List<String>>()
        actual["Ledger | Sum, above"] = codes(sheet, ledger, target, "Sum(../Shipments/Parcels*/Weight)")
        actual["Ledger | Sum, every level starred"] = codes(sheet, ledger, target, "Sum(../Shipments*/Parcels*/Weight)")

        val expected = actual.keys.associateWith { listOf(notInRuleGroup) }
        assertEquals(expected, actual, "into a repeatable target outside the declaring group")
    }

    /** A star on a level that does not repeat is the wildcard family's, never a star spent on a repeatable level. */
    @Test
    fun aStarOnALevelThatDoesNotRepeatIsTheWildcardGatesAlone() {
        val sheet = Sheet(ledger = true)
        val target = "../../Summary/Result"
        val actual = mutableMapOf<String, List<String>>()
        actual["Ledger | a star on the field"] = codes(sheet, ledger, target, "Sum(../Shipments/Parcels/Weight*)")
        actual["Summary | the same, contained"] =
            codes(sheet, summary, "../Result", "Sum(../Shipments/Parcels/Weight*)")
        actual["Ledger | a star on the fixed group"] =
            codes(sheet, ledger, target, "NumberOfFilledGroups(../Shipments/Parcels/Seal*)")
        actual["Ledger | a star on each level and on the field"] =
            codes(sheet, ledger, target, "Sum(../Shipments*/Parcels*/Weight*)")

        val expected = actual.keys.associateWith { listOf("MVK_INVALID_WILDCARD") }
        assertEquals(expected, actual, "a star on a level that does not repeat")
    }

    /**
     * A filter's own leaf and a semantic-index read name `Shipments` unstarred and read no row of it, so the
     * construct stays non-iterating and may write outside its group. The control row keeps the checker from
     * passing by never reading the operation: a plain unstarred read of the same field is refused.
     */
    @Test
    fun aFilterLeafAndAKeyedReadAreNotPerRowReads() {
        val filtered = "Sum(../Shipments*/ShipWeight Having [../Shipments/ShipKey] == \\\"X\\\")"
        val keyed = "[../Shipments/ShipWeight For \\\"A\\\"] + 1"
        val target = "../../Summary/Result"
        val plain = Sheet(ledger = true)
        val keyedSheet = Sheet(shipmentsKeyed = true, ledger = true)
        val reach = "FieldFilled(../Summary/Result) And"
        val actual = mutableMapOf<String, List<String>>()
        actual["Ledger | a plain unstarred read — the control"] =
            codes(plain, ledger, target, "[../Shipments/ShipWeight] + 1")
        actual["Ledger | a filtered aggregate"] = codes(plain, ledger, target, filtered)
        actual["Summary | a filtered aggregate, contained"] = codes(plain, summary, "../Result", filtered)
        actual["Ledger | a keyed read"] = codes(keyedSheet, ledger, target, keyed)
        actual["Summary | a keyed read, contained"] = codes(keyedSheet, summary, "../Result", keyed)
        actual["Ledger | rule, a filtered aggregate"] = ruleCodes(plain, ledger, "$reach $filtered > 0")
        actual["Ledger | rule, a keyed read"] =
            ruleCodes(keyedSheet, ledger, "$reach [../Shipments/ShipWeight For \\\"A\\\"] > 0")

        val expected = actual.keys.associateWith { emptyList<String>() }.toMutableMap()
        expected["Ledger | a plain unstarred read — the control"] = listOf(notInRuleGroup)
        assertEquals(expected, actual, "a filter leaf and a keyed read")
    }

    @Test
    fun onTheRuleArmTheSameReadsArePerRow() {
        val sheet = Sheet(ledger = true)
        val reach = "FieldFilled(../Summary/Result) And"
        val actual = mutableMapOf<String, List<String>>()
        actual["Shipments | Sum, above"] = ruleCodes(sheet, shipments, "$reach Sum(Parcels*/Weight) > 0")
        actual["Shipments | Sum, below"] = ruleCodes(sheet, shipments, "$reach Sum(../Shipments*/Parcels/Weight) > 0")
        actual["Shipments | the group count, above"] =
            ruleCodes(sheet, shipments, "$reach NumberOfFilledGroups(Parcels*/Seal) > 0")
        actual["Shipments | the group count, below"] =
            ruleCodes(sheet, shipments, "$reach NumberOfFilledGroups(../Shipments*/Parcels/Seal) > 0")
        actual["Ledger | Sum, above"] = ruleCodes(sheet, ledger, "$reach Sum(../Shipments/Parcels*/Weight) > 0")
        actual["Ledger | Sum, below"] = ruleCodes(sheet, ledger, "$reach Sum(../Shipments*/Parcels/Weight) > 0")
        actual["Ledger | the group count, above"] =
            ruleCodes(sheet, ledger, "$reach NumberOfFilledGroups(../Shipments/Parcels*/Seal) > 0")
        actual["Ledger | the group count, below"] =
            ruleCodes(sheet, ledger, "$reach NumberOfFilledGroups(../Shipments*/Parcels/Seal) > 0")
        actual["Shipments | Sum, every level starred — control"] =
            ruleCodes(sheet, shipments, "$reach Sum(../Shipments*/Parcels*/Weight) > 0")
        actual["Ledger | Sum, every level starred — control"] =
            ruleCodes(sheet, ledger, "$reach Sum(../Shipments*/Parcels*/Weight) > 0")

        val expected = actual.keys.associateWith { listOf(notInRuleGroup) }.toMutableMap()
        expected["Shipments | Sum, every level starred — control"] = emptyList()
        expected["Ledger | Sum, every level starred — control"] = emptyList()
        assertEquals(expected, actual, "a partial star in a rule reporting outside its group")
    }

    /**
     * `PartialStarContainmentLawsTest.everyValidationStarCarrierPlacesAnUnstarredLevelBelowTheFirstStar`, where
     * a12-kernel-lean's `SPEC-2026-09-24-06` was measured: every validation star carrier places a level left
     * unstarred below the first star, containment from a rule group the error field lies outside and the missing
     * star from the one it lies in. `@` stands for the level list, starred or not below the first star.
     */
    @Test
    fun everyValidationStarCarrierPlacesAnUnstarredLevelBelowTheFirstStar() {
        val sheet = Sheet(ledger = true)
        val reach = "FieldFilled(/Fulfilment/Summary/Result) And"
        val starForbidding =
            setOf("AllGroupsFilled(@/Seal)", "NotAllGroupsFilled(@/Seal)", "GroupsNotCollectivelyFilled(@/Seal)")
        val actual = linkedMapOf<String, List<String>>()
        val expected = linkedMapOf<String, List<String>>()
        VALIDATION_STAR_CARRIERS.forEach { carrier ->
            val below = carrier.replace("@", "/Fulfilment/Shipments*/Parcels")
            val every = carrier.replace("@", "/Fulfilment/Shipments*/Parcels*")
            listOf(shipments, ledger).forEach { group ->
                val from = group.substringAfterLast('/')
                actual["$from | $below"] = ruleCodes(sheet, group, "$reach $below")
                expected["$from | $below"] = listOf(notInRuleGroup)
                actual["$from | $every — control"] = ruleCodes(sheet, group, "$reach $every")
                expected["$from | $every — control"] =
                    if (carrier in starForbidding) listOf("MVK_NO_WILDCARDS_G_ALLOWED") else emptyList()
            }
            actual["Summary | $below"] = ruleCodes(sheet, "/Fulfilment/Summary", "$reach $below")
            expected["Summary | $below"] = listOf("MVK_NO_WILDCARD")
        }
        assertEquals(expected, actual, "the placed class on every validation star carrier")
    }

    /**
     * `PartialStarContainmentLawsTest.anOperatorsOwnGatesWaitForItsOperandsRead`: an operator's own gates run
     * only once its operands have been read, so an operand the read refuses answers for the whole call, and
     * each control shows the gate answering over an operand A12 reads.
     */
    @Test
    fun anOperatorsOwnGatesWaitForItsOperandsRead() {
        val sheet = Sheet(ledger = true)
        val reach = "FieldFilled(/Fulfilment/Summary/Result) And"
        val behind = listOf(
            "GroupFilled(/Fulfilment/Shipments*/Parcels/Seal)",
            "GroupNotFilled(/Fulfilment/Shipments/Parcels*/Seal)",
            "AllGroupsFilled(/Fulfilment/Shipments/Parcels*/Seal)",
            "AllGroupsFilled(/Fulfilment/Shipments*/Parcels/Seal, /Fulfilment)",
            "AllGroupsFilled(/Fulfilment/Shipments/Parcels/Seal, /Fulfilment/Shipments/Parcels/Seal)",
            "AtLeastOneGroupFilled(/Fulfilment, /Fulfilment/Shipments/Parcels/Seal)",
            "GroupFilled(/Fulfilment/Shipments/ShipWeight)",
            "NumberOfFilledGroups(/Fulfilment/Shipments/ShipWeight) > 0",
        )
        val controls = linkedMapOf(
            "GroupFilled(/Fulfilment/Shipments*/Parcels*/Seal)" to "MVK_NO_WILDCARDS_ALLOWED",
            "AllGroupsFilled(/Fulfilment/Shipments*/Parcels*/Seal)" to "MVK_NO_WILDCARDS_G_ALLOWED",
            "AllGroupsFilled(/Fulfilment)" to "MVK_ROOT_GROUP_REFERENCED",
            "AllGroupsFilled(/Fulfilment/Ledger, /Fulfilment/Ledger)" to "MVK_DUPLICATE_PARAM1",
            "AtLeastOneGroupFilled(/Fulfilment, /Fulfilment/Ledger)" to "MVK_DUPLICATE_PARAM2",
            "GroupFilled(/Fulfilment/Ledger/Total)" to "MVK_NO_FIELDS_ALLOWED",
        )
        val actual = linkedMapOf<String, List<String>>()
        val expected = linkedMapOf<String, List<String>>()
        behind.forEach { read ->
            actual["Ledger | $read"] = ruleCodes(sheet, ledger, "$reach $read")
            expected["Ledger | $read"] = listOf(notInRuleGroup)
            actual["Summary | $read"] = ruleCodes(sheet, "/Fulfilment/Summary", "$reach $read")
            expected["Summary | $read"] = listOf("MVK_NO_WILDCARD")
        }
        controls.forEach { (read, code) ->
            actual["control | $read"] = ruleCodes(sheet, "/Fulfilment/Summary", "$reach $read")
            expected["control | $read"] = listOf(code)
        }
        assertEquals(expected, actual, "an operator's own gates behind a refused read")
    }

    private fun codes(sheet: Sheet, group: String, computedField: String, operation: String): List<String> =
        a12Codes(sheet.json(group, computation(computedField, operation)))

    private fun ruleCodes(sheet: Sheet, group: String, condition: String): List<String> =
        a12Codes(sheet.json(group, rule("../../Summary/Result", condition)))

    private fun a12Codes(source: String): List<String> = check(ModelSource(source)).findings
        .filter { it.category == FindingCategory.A12_CONSTRAINT }.map { it.code }.distinct()

    private fun computation(computedField: String, operation: String) = """
        {"type":"Computation","id":"c0","name":"Probe","Computation":{
          "computedFieldRelPath":"$computedField",
          "computationAlternatives":[{"operation":"$operation"}],
          "errorCode":"PROBE","errorMessage":[{"locale":"en_US","text":"x"}]}}
    """.trimIndent()

    private fun rule(errorField: String, condition: String) = """
        {"type":"Rule","id":"r0","name":"Probe","Rule":{
          "errorCondition":"$condition","errorEntityRelPath":"$errorField",
          "errorCode":"PROBE","errorMessage":[{"locale":"en_US","text":"x"}]}}
    """.trimIndent()

    private companion object {
        /** The lock's carriers, with `@` for the level list. */
        val VALIDATION_STAR_CARRIERS = listOf(
            "Sum(@/Weight) > 0",
            "MaxValue(@/Weight) > 0",
            "FirstFilledValue(@/Weight) > 0",
            "NumberOfValueInFields(0 In @/Weight) > 0",
            "NumberOfFilledGroups(@/Seal) > 0",
            "AtLeastOneFieldFilled(@/Weight)",
            "AllFieldsFilled(@/Weight)",
            "NoFieldFilled(@/Weight)",
            "NotAllFieldsFilled(@/Weight)",
            "FieldsNotCollectivelyFilled(@/Weight)",
            "NotExactlyOneFieldFilled(@/Weight)",
            "MoreThanOneFieldFilled(@/Weight)",
            "NumberOfFilledFields(@/Weight) > 0",
            "AtLeastOneFieldValueIncludedInValueList(@/ParcelKey In \\\"X\\\")",
            "NoFieldValueIncludedInValueList(@/ParcelKey In \\\"X\\\")",
            "NotAllFieldValuesIncludedInValueList(@/ParcelKey In \\\"X\\\")",
            "AtLeastOneGroupFilled(@/Seal)",
            "NoGroupFilled(@/Seal)",
            "AllGroupsFilled(@/Seal)",
            "NotAllGroupsFilled(@/Seal)",
            "GroupsNotCollectivelyFilled(@/Seal)",
            "FieldValuesNotUnique(@/ParcelKey)",
            "NumberOfDifferentValues(@/ParcelKey) < 2",
        )
    }

    /**
     * The locks' fulfilment sheet. Both key fields are always present and the flags only declare them their
     * group's index; `ledger` adds the fixed sibling `PartialStarContainmentLawsTest` declares from.
     */
    private data class Sheet(
        val shipmentsKeyed: Boolean = false,
        val parcelsKeyed: Boolean = false,
        val ledger: Boolean = false,
    ) {
        fun json(into: String, declaration: String): String {
            fun body(path: String) = if (path == into) ",$declaration" else ""
            fun number(id: String, name: String) = """{"type":"Field","id":"$id","name":"$name","Field":{"fieldType":
                {"type":"NumberType","NumberType":{"maxFractionalDigits":0}}}}"""
            fun string(id: String, name: String) =
                """{"type":"Field","id":"$id","name":"$name","Field":{"fieldType":{"type":"StringType"}}}"""
            val ledgerGroup = if (!ledger) "" else """
                {"type":"Group","id":"g5","name":"Ledger","Group":{"elements":[
                  ${number("f9", "Total")} ${body("/Fulfilment/Ledger")}]}},
            """.trimIndent()
            val shipmentsIndex = if (shipmentsKeyed) "\"indexFieldName\":\"ShipKey\"," else ""
            val parcelsIndex = if (parcelsKeyed) "\"indexFieldName\":\"ParcelKey\"," else ""
            return """
                {"header":{"id":"fulfilment","locales":[{"code":"en_US"}]},
                 "content":{"modelRoot":{"rootGroups":[
                  {"type":"Group","id":"g0","name":"Fulfilment","Group":{"elements":[
                    {"type":"Group","id":"g1","name":"Summary","Group":{"elements":[
                      ${number("f0", "Result")} ${body("/Fulfilment/Summary")}]}},
                    $ledgerGroup
                    {"type":"Group","id":"g2","name":"Shipments","Group":{"repeatability":2,$shipmentsIndex
                      "elements":[
                        ${string("f1", "ShipKey")}, ${number("f2", "ShipWeight")}, ${number("f3", "ShipResult")},
                        {"type":"Group","id":"g3","name":"Parcels","Group":{"repeatability":2,$parcelsIndex
                          "elements":[
                            ${string("f4", "ParcelKey")}, ${number("f5", "Weight")}, ${number("f6", "Share")},
                            {"type":"Group","id":"g4","name":"Seal","Group":{"elements":[
                              ${string("f7", "SealCode")}]}}
                            ${body("/Fulfilment/Shipments/Parcels")}
                        ]}}
                        ${body("/Fulfilment/Shipments")}
                    ]}}
                  ]}}
                 ]}}}
            """.trimIndent()
        }
    }
}

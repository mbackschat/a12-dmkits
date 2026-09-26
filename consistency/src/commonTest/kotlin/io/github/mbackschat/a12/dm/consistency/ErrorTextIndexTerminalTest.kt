package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of the `$index(…)$` terminal's five gates.
 *
 * Every row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.RuleMessageIndexTerminalLawsTest` and, for the two gates behind the argument,
 * `…MessageIndexTerminalRepeatabilityLawsTest`. No row filters by code: the grid asks what the checker says
 * about the declaration, because filtering is what hid the missing-suffix class here while it was unreported.
 */
class ErrorTextIndexTerminalTest {

    @Test
    fun whichArgumentTheIndexTerminalTakes() {
        val actual = mutableMapOf<String, List<String>>()
        actual["the index field of the error field's own group"] = reporting("row \$index(ClaimNo).value\$")
        actual["a non-index field of the same group"] = reporting("row \$index(Payout).value\$")
        actual["a field outside the group"] = reporting("row \$index(Reference).value\$")
        actual["a name no field carries"] = reporting("row \$index(Nope).value\$")
        actual["a name no field carries, and NO suffix either"] = reporting("row \$index(Nope)\$")
        actual["the index field spelled as a PATH"] = reporting("row \$index(/Policy/Claims/ClaimNo).value\$")
        actual["the index field with the suffix OMITTED"] = reporting("row \$index(ClaimNo)\$")
        actual["the terminal CAPITALISED"] = reporting("row \$Index(Nope).value\$")
        actual["no parameter at all — the control"] = reporting("row")

        val refused = listOf("INVALID_INDEX_FIELD")
        val expected = mutableMapOf<String, List<String>>()
        expected["the index field of the error field's own group"] = emptyList()
        expected["a non-index field of the same group"] = refused
        expected["a field outside the group"] = refused
        expected["a name no field carries"] = refused
        // The ARGUMENT gate is first and the missing-suffix gate is last, so this draws this class and not
        // `INVALID_INDEX_FIELD_PARAMETER` — the row that makes reporting the first gate alone sound.
        expected["a name no field carries, and NO suffix either"] = refused
        // Matched by SHORT NAME, so the path spelling names nothing — A12 refuses it and so does this.
        expected["the index field spelled as a PATH"] = refused
        // The LAST of the five gates, reachable only from behind the repeatability one — here the condition
        // reads the index field plainly, which is the measured shape that clears it.
        expected["the index field with the suffix OMITTED"] = listOf("INVALID_INDEX_FIELD_PARAMETER")
        // `$Index(…)$` is not this terminal at all: the lexer refuses it with `MVK_UNEXPECTED_TOKEN` before
        // any gate runs, and the condition-syntax family owns that.
        expected["the terminal CAPITALISED"] = emptyList()
        expected["no parameter at all — the control"] = emptyList()
        assertEquals(expected, actual, "which argument the index terminal takes")
    }

    /**
     * The SECOND gate, on its own fixture: two groups on one path declaring an index field of the same name.
     *
     * The control is the same terminal on the shape where only ONE group declares the name, which is the
     * unambiguous row above — so what moves between them is the path's second declaration, nothing else.
     */
    @Test
    fun aShortNameTwoGroupsOnThePathDeclareIsAmbiguous() {
        val actual = mutableMapOf<String, List<String>>()
        actual["the short name both groups declare"] = ambiguityIn("row \$index(Ref).value\$")
        actual["no parameter at all — the control"] = ambiguityIn("row")

        val expected = mutableMapOf<String, List<String>>()
        expected["the short name both groups declare"] = listOf("INVALID_INDEX_FIELD_AMBIGUOUS")
        expected["no parameter at all — the control"] = emptyList()
        assertEquals(expected, actual, "a short name two groups on the path declare is ambiguous")
    }

    /**
     * The FOURTH gate's repeatable arm, which reads the declaration's own condition.
     *
     * A plain reference iterates the level and is admitted; a wildcard or a semantic-index key does not, and
     * draws the same class either way. The no-suffix row is the ORDER row: both gates would fire and this one
     * answers, so a family reporting the missing suffix has to have walked this gate first.
     */
    @Test
    fun whatAStarredOrKeyedReferenceDoesToARepeatableIndexField() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a plain reference — the control"] =
            inWarehouse(READS_SLOT, "row \$index(Slot).value\$")
        actual["a STARRED reference"] = inWarehouse(READS_SLOT_STARRED, "row \$index(Slot).value\$")
        actual["a STARRED reference and NO suffix"] = inWarehouse(READS_SLOT_STARRED, "row \$index(Slot)\$")
        actual["a For-KEYED reference"] = inWarehouse(READS_SLOT_KEYED, "row \$index(Slot).value\$")
        actual["a STARRED reference, parameter-free — the control"] = inWarehouse(READS_SLOT_STARRED, "row")

        val refused = listOf("INVALID_INDEX_FIELD_REP_ITER")
        val expected = mutableMapOf<String, List<String>>()
        expected["a plain reference — the control"] = emptyList()
        expected["a STARRED reference"] = refused
        // The repeatability gate runs before the missing-suffix one, so the class here is this one and not
        // `INVALID_INDEX_FIELD_PARAMETER` — measured, and the reason the last gate is safe to report at all.
        expected["a STARRED reference and NO suffix"] = refused
        expected["a For-KEYED reference"] = refused
        expected["a STARRED reference, parameter-free — the control"] = emptyList()
        assertEquals(expected, actual, "what a starred or keyed reference does to a repeatable index field")
    }

    /**
     * The FOURTH gate's non-repeatable arm, which reads the DECLARATION and not the condition.
     *
     * Dropping the condition's reference changes nothing, which is the row separating this arm from the one
     * above; and the admitted shape — one value carrying its own `sValue` — is what makes the refusals the
     * gate's rather than the fixture's.
     */
    @Test
    fun whatANonRepeatableGroupsIndexFieldHasToDeclare() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a two-value Enumeration the condition reads"] =
            inBay(READS_TIER, "row \$index(Tier).value\$", cleanBayIndex = false)
        actual["the same declaration, NOT read"] =
            inBay(READS_NOTE, "row \$index(Tier).value\$", cleanBayIndex = false)
        actual["one value carrying its own sValue"] =
            inBay(READS_NOTE, "row \$index(Tier).value\$", cleanBayIndex = true)
        actual["the offending declaration, parameter-free — the control"] =
            inBay(READS_NOTE, "row", cleanBayIndex = false)

        val refused = listOf("INVALID_INDEX_FIELD_NONREP_ENUM")
        val expected = mutableMapOf<String, List<String>>()
        expected["a two-value Enumeration the condition reads"] = refused
        expected["the same declaration, NOT read"] = refused
        expected["one value carrying its own sValue"] = emptyList()
        expected["the offending declaration, parameter-free — the control"] = emptyList()
        assertEquals(expected, actual, "what a non-repeatable group's index field has to declare")
    }

    /**
     * The MIXED shape, where a second group declares the same index-field short name.
     *
     * A12 answers `INVALID_INDEX_FIELD_REP` from a list this family does not reproduce, so both rows are a
     * disclosed under-report — and the point is that the arms above must NOT answer here, since either would
     * be the wrong class rather than silence.
     */
    @Test
    fun theMixedShapeThisFamilyLeavesAlone() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a repeatable namesake beside an offending declaration"] =
            inBay(READS_RACK_TIER, "row \$index(Tier).value\$", cleanBayIndex = false, withNamesake = true)
        actual["a repeatable namesake beside an admitted one"] =
            inBay(READS_RACK_TIER, "row \$index(Tier).value\$", cleanBayIndex = true, withNamesake = true)
        actual["an unknown argument on that same model — the positive control"] =
            inBay(READS_RACK_TIER, "row \$index(Nope).value\$", cleanBayIndex = true, withNamesake = true)

        val expected = mutableMapOf<String, List<String>>()
        expected["a repeatable namesake beside an offending declaration"] = emptyList()
        expected["a repeatable namesake beside an admitted one"] = emptyList()
        // Without this row two silent expectations would also pass on a fixture that never parsed.
        expected["an unknown argument on that same model — the positive control"] =
            listOf("INVALID_INDEX_FIELD")
        assertEquals(expected, actual, "the mixed shape this family leaves alone")
    }

    /** The finding names what the error entity's path DOES offer, since that is the repair. */
    @Test
    fun theFindingNamesTheIndexFieldsThatPathOffers() {
        val finding = checkRead(ModelSource(model("row \$index(Nope).value\$")))
            .findings.single { it.code == "INVALID_INDEX_FIELD" }
        assertEquals("/Policy/Claims/ClaimRule", finding.at)
        assertEquals(true, finding.explain.contains("ClaimNo"), finding.explain)
    }

    /** An insurance claims desk mirroring the laws test's carrier. */
    private fun reporting(text: String): List<String> =
        checkRead(ModelSource(model(text))).findings.filter { it.at == "/Policy/Claims/ClaimRule" }.map { it.code }

    private fun ambiguityIn(text: String): List<String> = checkRead(ModelSource(ambiguousModel(text)))
        .findings.filter { it.at == "/Policy/Claims/Items/ItemRule" }.map { it.code }

    private fun model(text: String): String = """
        {"header":{"id":"policy-index-terminal","locales":[{"code":"en_US"}]},
         "content":{"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Policy","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Group","id":"g1","name":"Claims","Group":{"repeatability":20,
              "indexFieldName":"ClaimNo","elements":[
              {"type":"Field","id":"f1","name":"ClaimNo","Field":{"fieldType":{"type":"StringType"}}},
              {"type":"Field","id":"f2","name":"Payout","Field":{"fieldType":{"type":"StringType"}}},
              {"type":"Rule","id":"r0","name":"ClaimRule","Rule":{
                "errorCondition":"FieldFilled(Payout) And FieldFilled(ClaimNo)","errorEntityRelPath":"../Payout",
                "errorCode":"PROBE","errorMessage":[{"locale":"en_US","text":"$text"}]}}
            ]}}
          ]}}
         ]}}}
    """.trimIndent()

    /** The same desk with the inner group re-declaring its parent's index-field NAME. */
    private fun ambiguousModel(text: String): String = """
        {"header":{"id":"policy-ambiguous-index","locales":[{"code":"en_US"}]},
         "content":{"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Policy","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Group","id":"g1","name":"Claims","Group":{"repeatability":20,
              "indexFieldName":"Ref","elements":[
              {"type":"Field","id":"f1","name":"Ref","Field":{"fieldType":{"type":"StringType"}}},
              {"type":"Group","id":"g2","name":"Items","Group":{"repeatability":9,
                "indexFieldName":"Ref","elements":[
                {"type":"Field","id":"f2","name":"Ref","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f3","name":"Payout","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Rule","id":"r0","name":"ItemRule","Rule":{
                  "errorCondition":"FieldFilled(Payout)","errorEntityRelPath":"../Payout",
                  "errorCode":"PROBE","errorMessage":[{"locale":"en_US","text":"$text"}]}}
              ]}}
            ]}}
          ]}}
         ]}}}
    """.trimIndent()

    // ── the two gates behind the argument ────────────────────────────────────────────────────────────────

    private val READS_SLOT = "FieldFilled(Weight) And FieldFilled(Slot)"
    private val READS_SLOT_STARRED =
        "FieldFilled(Weight) And AtLeastOneFieldFilled(/Warehouse/Pallets*/Slot)"
    private val READS_SLOT_KEYED = "FieldFilled(Weight) And FieldFilled([/Warehouse/Pallets/Slot For \"S-1\"])"
    private val READS_TIER = "FieldFilled(Note) And FieldFilled(Tier)"
    private val READS_NOTE = "FieldFilled(Note)"
    private val READS_RACK_TIER = "FieldFilled(Note) And AtLeastOneFieldFilled(/Warehouse/Racks*/Tier)"

    /** The rule in the REPEATABLE indexed group, whose index field is an ordinary string. */
    private fun inWarehouse(condition: String, text: String): List<String> =
        checkRead(ModelSource(warehouse(condition, text, inBay = false, cleanBayIndex = false, withNamesake = false)))
            .findings.filter { it.at == "/Warehouse/Pallets/Probe" }.map { it.code }

    /** …and in the NON-repeatable one, whose index field is the Enumeration the gate reads. */
    private fun inBay(
        condition: String,
        text: String,
        cleanBayIndex: Boolean,
        withNamesake: Boolean = false,
    ): List<String> =
        checkRead(ModelSource(warehouse(condition, text, inBay = true, cleanBayIndex, withNamesake)))
            .findings.filter { it.at == "/Warehouse/Bays/Probe" }.map { it.code }

    /** The warehouse desk the repeatability laws test measures, with its three variables. */
    private fun warehouse(
        condition: String,
        text: String,
        inBay: Boolean,
        cleanBayIndex: Boolean,
        withNamesake: Boolean,
    ): String {
        val tier = if (cleanBayIndex) {
            """"values":[{"value":"BASIC","label":[{"locale":"en_US","text":"Basic"}]}],"sValue":"BASIC""""
        } else {
            """"values":[{"value":"BASIC","label":[{"locale":"en_US","text":"Basic"}]},
                  {"value":"PREMIUM","label":[{"locale":"en_US","text":"Premium"}]}]"""
        }
        val probe = """{"type":"Rule","id":"r0","name":"Probe","Rule":{
              "errorCondition":"${condition.replace("\"", "\\\"")}",
              "errorEntityRelPath":"${if (inBay) "../Note" else "../Weight"}","errorCode":"PROBE",
              "errorMessage":[{"locale":"en_US","text":"${text.replace("\"", "\\\"")}"}]}}"""
        val racks = if (!withNamesake) "" else """,
            {"type":"Group","id":"g3","name":"Racks","Group":{"repeatability":9,"indexFieldName":"Tier",
              "elements":[
                {"type":"Field","id":"f5","name":"Tier","Field":{"fieldType":{"type":"EnumerationType",
                  "EnumerationType":{"values":[{"value":"BASIC","label":[{"locale":"en_US","text":"Basic"}]},
                    {"value":"PREMIUM","label":[{"locale":"en_US","text":"Premium"}]}]}}}},
                {"type":"Field","id":"f6","name":"Label","Field":{"fieldType":{"type":"StringType"}}}
              ]}}"""
        return """
        {"header":{"id":"warehouse-index","locales":[{"code":"en_US"}]},
         "content":{"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Warehouse","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Group","id":"g1","name":"Pallets","Group":{"repeatability":20,"indexFieldName":"Slot",
              "elements":[
                {"type":"Field","id":"f1","name":"Slot","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f2","name":"Weight","Field":{"fieldType":{"type":"StringType"}}}
                ${if (inBay) "" else ",$probe"}
              ]}},
            {"type":"Group","id":"g2","name":"Bays","Group":{"indexFieldName":"Tier","elements":[
                {"type":"Field","id":"f3","name":"Tier","Field":{"fieldType":{"type":"EnumerationType",
                  "EnumerationType":{$tier}}}},
                {"type":"Field","id":"f4","name":"Note","Field":{"fieldType":{"type":"StringType"}}}
                ${if (inBay) ",$probe" else ""}
              ]}}$racks
          ]}}
         ]}}}
        """.trimIndent()
    }
}

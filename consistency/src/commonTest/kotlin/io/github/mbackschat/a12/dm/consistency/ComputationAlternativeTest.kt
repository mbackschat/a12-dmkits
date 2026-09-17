package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A computation's `computationAlternatives` — the list that carries its work, and the guards A12 demands of
 * it once there is more than one.
 *
 * Every verdict below is the Kernel's, measured on the raw stored shapes by
 * `rulekit.validate.laws.ComputationAlternativeSlotLawsTest` (kernel 30.8.1, 2026-09-12). The typed
 * `Computation.Builder` can author none of them — it requires at least one alternative with a non-blank
 * operation — so this family is entirely about models a checker meets and an author cannot write.
 *
 * Each table states one verdict per shape rather than one test per shape, because the claim is about which
 * shapes are SEPARATED.
 */
class ComputationAlternativeTest {

    /**
     * A one-computation model.
     *
     * @param alternatives the whole `computationAlternatives` member, raw — the shapes A12 admits and the
     *     shapes its reader refuses are exactly what is under test — or null to omit the member.
     */
    private fun model(alternatives: String?) = prepare(
        assertIs<Admission.Admitted>(
            admit(
                ModelSource(
                    """
                    {"header":{"id":"catalog","locales":[{"code":"en_US"}]},
                     "content":{"modelRoot":{"rootGroups":[
                      {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[
                        {"type":"Field","id":"f0","name":"Price","Field":{"fieldType":{"type":"StringType"}}},
                        {"type":"Computation","id":"c0","name":"PriceCalc","Computation":{${
                        alternatives?.let { """"computationAlternatives":$it""" } ?: ""
                    }}}
                      ]}}
                     ]}}}
                    """.trimIndent(),
                ),
            ),
        ),
    )

    /** The codes reported for one shape, in report order, so a missing and an extra row both show. */
    private fun codesFor(alternatives: String?) =
        model(alternatives).tracked().computationAlternativeFindings().map { it.code }

    /** A scale-free constant operation, so no operand law can move a row that is about structure. */
    private val work = """{"operation":"42.00"}"""

    private fun guarded(precondition: String) =
        """{"precondition":"$precondition","operation":"42.00"}"""

    @Test
    fun aComputationWithNothingToComputeCarriesTwoVerdicts() {
        val actual = mutableMapOf<String, List<String>>()
        actual["one alternative — the control"] = codesFor("[$work]")
        actual["the member absent"] = codesFor(null)
        actual["an empty list"] = codesFor("[]")
        actual["an alternative with no operation"] = codesFor("[{}]")
        actual["an alternative with a blank operation"] = codesFor("""[{"operation":""}]""")
        actual["an alternative with a whitespace operation"] = codesFor("""[{"operation":"   "}]""")
        actual["an alternative with a null operation"] = codesFor("""[{"operation":null}]""")

        // With NO alternative the precondition slot does not exist either, so A12 reports the missing
        // condition beside the incompleteness; with one PRESENT the condition gate is satisfied and only
        // the incompleteness remains. That pair is what separates "nothing to compute" from "an alternative
        // with no work in it", and a checker reporting one code for both loses the distinction.
        val nothingAtAll = listOf("MVK_INCOMPLETE_INPUT", "MVK_CONDITION_MISSING")
        val expected = mapOf(
            "one alternative — the control" to listOf(),
            "the member absent" to nothingAtAll,
            "an empty list" to nothingAtAll,
            "an alternative with no operation" to listOf("MVK_INCOMPLETE_INPUT"),
            "an alternative with a blank operation" to listOf("MVK_INCOMPLETE_INPUT"),
            "an alternative with a whitespace operation" to listOf("MVK_INCOMPLETE_INPUT"),
            "an alternative with a null operation" to listOf("MVK_INCOMPLETE_INPUT"),
        )
        assertEquals(expected, actual, "what a computation's alternative list must carry")
    }

    /**
     * A single alternative may be unguarded; from two on, EVERY one owes its own precondition.
     *
     * The control is the row that makes this worth locking: it is legal, so "an alternative may be
     * unguarded" looks settled, and the rival reading — that the LAST alternative is a fallback owing
     * nothing — fits it exactly while disagreeing on every model with two. Unguarding the first, the last
     * and both are therefore three separate rows rather than one.
     */
    @Test
    fun everyOneOfSeveralAlternativesOwesItsOwnPrecondition() {
        val actual = mutableMapOf<String, List<String>>()
        actual["one, unguarded — the control"] = codesFor("[$work]")
        actual["two, both guarded"] =
            codesFor("[${guarded("FieldFilled(../Price)")},${guarded("FieldNotFilled(../Price)")}]")
        actual["two, the LAST unguarded"] = codesFor("[${guarded("FieldFilled(../Price)")},$work]")
        actual["two, the FIRST unguarded"] = codesFor("[$work,${guarded("FieldFilled(../Price)")}]")
        actual["two, both unguarded"] = codesFor("[$work,$work]")
        actual["THREE, the middle unguarded"] = codesFor(
            "[${guarded("FieldFilled(../Price)")},$work,${guarded("FieldNotFilled(../Price)")}]")
        actual["THREE, all guarded"] = codesFor("[${guarded("FieldFilled(../Price)")}," +
            "${guarded("FieldNotFilled(../Price)")},${guarded("FieldFilled(../Price)")}]")
        actual["two, one precondition null"] =
            codesFor("""[${guarded("FieldFilled(../Price)")},{"precondition":null,"operation":"42.00"}]""")

        val oneMissing = listOf("MVK_ERROR_EMPTY_PRECONDITION")
        val expected = mapOf(
            "one, unguarded — the control" to listOf(),
            "two, both guarded" to listOf(),
            "two, the LAST unguarded" to oneMissing,
            "two, the FIRST unguarded" to oneMissing,
            // ONE finding, not one per alternative: A12 raises a single notification for the computation,
            // and reporting two would claim it said twice what it said once.
            "two, both unguarded" to oneMissing,
            // Every measured multi row had exactly two alternatives, so the demand's SIZE-independence was a
            // claim rather than an observation until these two.
            "THREE, the middle unguarded" to oneMissing,
            "THREE, all guarded" to listOf(),
            "two, one precondition null" to oneMissing,
        )
        assertEquals(expected, actual, "which alternatives owe a precondition")
    }

    /**
     * A BLANK precondition is not an empty one — the row that refuses the sibling families' law.
     *
     * A blank `errorCode` and a blank message `text` each count as absent, so carrying that across reads as
     * obvious. A12 disagrees: a precondition that is PRESENT reaches its condition parser whatever it
     * spells, and `""` fails there as `MVK_UNEXPECTED_TOKEN` rather than as an empty precondition. Reporting
     * this family's code for it would name the wrong defect, so it is left to the syntax layer and the
     * report says so.
     */
    @Test
    fun aBlankPreconditionIsLeftToTheSyntaxFamilyRatherThanCalledEmpty() {
        val actual = mutableMapOf<String, List<String>>()
        actual["two, one precondition blank"] = codesFor("[${guarded("FieldFilled(../Price)")},${guarded("")}]")
        actual["two, one precondition whitespace"] =
            codesFor("[${guarded("FieldFilled(../Price)")},${guarded("   ")}]")

        val expected = mapOf(
            "two, one precondition blank" to listOf<String>(),
            "two, one precondition whitespace" to listOf(),
        )
        assertEquals(expected, actual, "a present but blank precondition is not this family's finding")
    }

    /**
     * A shape A12's own READER refuses is not a verdict about the model, so it never draws an `MVK_*` code.
     *
     * The object row is the one that had to be measured rather than inferred: `errorMessage` ADMITS an
     * object, because its deserializer iterates the node's elements and an object's elements are its values,
     * and reading one list-shaped member's rules off the other would have produced findings about a model
     * A12 never loads. The two list members have two readers.
     */
    @Test
    fun aShapeA12sReaderRefusesIsReportedAsSuchRatherThanAsMissingWork() {
        val actual = mutableMapOf<String, List<String>>()
        actual["the member as an object"] = codesFor("""{"a":$work}""")
        actual["the member as a scalar"] = codesFor(""""42.00"""")
        actual["the member as an explicit null"] = codesFor("null")
        actual["an alternative that is not an object"] = codesFor("""["42.00"]""")

        val refused = listOf("CK_MODEL_NOT_BUILDABLE")
        val expected = mapOf(
            "the member as an object" to refused,
            "the member as a scalar" to refused,
            "the member as an explicit null" to refused,
            "an alternative that is not an object" to refused,
        )
        assertEquals(expected, actual, "shapes A12's reader refuses")
    }

    /**
     * An envelope A12 cannot bind is a tier ABOVE any verdict about the member inside it.
     *
     * The two shapes look identical to a reader that casts `declaration["Computation"]` to an object and
     * takes `null` for "the member is absent": one is legal and one is unreadable. A12 DEFAULT-CONSTRUCTS a
     * computation whose envelope is omitted, so every member of it genuinely reads as absent and the no-work
     * verdict is right; a present non-object envelope never loads at all, and reporting the same two codes
     * there publishes a verdict about a model A12 never examined.
     */
    @Test
    fun anEnvelopeA12CannotBindIsNotAnAbsentMember() {
        val actual = mutableMapOf<String, List<String>>()
        actual["the envelope omitted"] = codesForRaw("")
        actual["the envelope as a string"] = codesForRaw(""""Computation":"oops"""")
        actual["the envelope as an array"] = codesForRaw(""""Computation":[]""")

        val expected = mapOf(
            "the envelope omitted" to listOf("MVK_INCOMPLETE_INPUT", "MVK_CONDITION_MISSING"),
            "the envelope as a string" to listOf("CK_MODEL_NOT_BUILDABLE"),
            "the envelope as an array" to listOf("CK_MODEL_NOT_BUILDABLE"),
        )
        assertEquals(expected, actual, "a computation's own envelope")
    }

    /** A one-computation model whose whole `Computation` member is written verbatim, or omitted. */
    private fun codesForRaw(envelope: String) = prepare(
        assertIs<Admission.Admitted>(
            admit(
                ModelSource(
                    """
                    {"header":{"id":"catalog","locales":[{"code":"en_US"}]},
                     "content":{"modelRoot":{"rootGroups":[
                      {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[
                        {"type":"Field","id":"f0","name":"Price","Field":{"fieldType":{"type":"StringType"}}},
                        {"type":"Computation","id":"c0","name":"PriceCalc"${
                        if (envelope.isEmpty()) "" else ",$envelope"
                    }}
                      ]}}
                     ]}}}
                    """.trimIndent(),
                ),
            ),
        ),
    ).tracked().computationAlternativeFindings().map { it.code }

    /** A rule carries no alternative list, and nothing here may report one missing from it. */
    @Test
    fun aRuleIsNotAskedForAnAlternativeList() {
        val prepared = prepare(
            assertIs<Admission.Admitted>(
                admit(
                    ModelSource(
                        """
                        {"header":{"id":"catalog","locales":[{"code":"en_US"}]},
                         "content":{"modelRoot":{"rootGroups":[
                          {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[
                            {"type":"Field","id":"f0","name":"Price","Field":{"fieldType":{"type":"StringType"}}},
                            {"type":"Rule","id":"r0","name":"PriceCheck","Rule":{"errorCode":"E1"}}
                          ]}}
                         ]}}}
                        """.trimIndent(),
                    ),
                ),
            ),
        )
        assertEquals(emptyList(), prepared.tracked().computationAlternativeFindings())
    }
}

package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A computation's `roundingMode` and its alternatives' `toleranceRangeOp` — the two halves of one A12 gate.
 *
 * Every verdict below is the Kernel's, measured on the raw stored shapes by
 * `rulekit.validate.laws.ComputationRoundingModeLawsTest` (kernel 30.8.1, 2026-09-12). Neither member exists
 * anywhere in the Java authoring surface, so this family is entirely about models a checker meets and an
 * author here cannot write — which is also why every shape had to be measured rather than derived from a
 * builder's rules.
 *
 * Each table states one verdict per shape rather than one test per shape, because the claim is about which
 * shapes are SEPARATED.
 */
class ComputationRoundingTest {

    /**
     * A one-computation model.
     *
     * @param mode the whole `roundingMode` member, raw, or null to omit it.
     * @param alternatives the whole `computationAlternatives` member, raw.
     */
    private fun model(mode: String?, alternatives: String) = prepare(
        assertIs<Admission.Admitted>(
            admit(
                ModelSource(
                    """
                    {"header":{"modelVersion":"28.4.0","id":"catalog","locales":[{"code":"en_US"}]},
                     "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
                      {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[
                        {"type":"Field","id":"f0","name":"Price","Field":{"fieldType":{"type":"StringType"}}},
                        {"type":"Computation","id":"c0","name":"PriceCalc","Computation":{
                          "computationAlternatives":$alternatives${
                        mode?.let { ""","roundingMode":$it""" } ?: ""
                    }}}
                      ]}}
                     ]}}}
                    """.trimIndent(),
                ),
            ),
        ),
    )

    private fun codesFor(mode: String?, alternatives: String = "[$strict]") =
        model(mode, alternatives).tracked().computationRoundingFindings().map { it.code }

    /** An alternative comparing exactly — no band, which is what makes a rounding mode's pair wrong. */
    private val strict = """{"operation":"42.00"}"""

    private fun banded(band: String = "DiffersWithToleranceRange1") =
        """{"operation":"42.00","toleranceRangeOp":"$band"}"""

    @Test
    fun aRoundingModeOutsideA12sInternalValuesMakesTheModelUnbuildable() {
        val actual = mutableMapOf<String, List<String>>()
        actual["absent — the control"] = codesFor(null)
        actual["Exact"] = codesFor(""""Exact"""")
        actual["an explicit null"] = codesFor("null")
        actual["roundup — the right value, wrong case"] = codesFor(""""roundup"""")
        actual["ROUND_UP — the enum CONSTANT rather than its internal value"] = codesFor(""""ROUND_UP"""")
        actual["empty"] = codesFor("""""""")
        actual["a number"] = codesFor("7")
        actual["a JSON object"] = codesFor("""{"mode":"Exact"}""")

        val expected = mutableMapOf<String, List<String>>()
        expected["absent — the control"] = emptyList()
        expected["Exact"] = emptyList()
        // An explicit null is ABSENCE here, unlike every rule slot, because the accessor is
        // `Optional.ofNullable` rather than an enum binding. Reporting it would refuse a model A12 admits.
        expected["an explicit null"] = emptyList()
        expected["roundup — the right value, wrong case"] = listOf(CK_MODEL_NOT_BUILDABLE)
        expected["ROUND_UP — the enum CONSTANT rather than its internal value"] = listOf(CK_MODEL_NOT_BUILDABLE)
        expected["empty"] = listOf(CK_MODEL_NOT_BUILDABLE)
        // A scalar is coerced to the String member and then fails the set; a container refuses the reader a
        // tier earlier. Two tiers, one code — the only one this checker owns, and it covers both.
        expected["a number"] = listOf(CK_MODEL_NOT_BUILDABLE)
        expected["a JSON object"] = listOf(CK_MODEL_NOT_BUILDABLE)
        assertEquals(expected, actual, "what this checker reports for each spelling of `roundingMode`")
    }

    /**
     * A scalar is named by its CONTENT and a container by its absence of one — the distinction the codes
     * above cannot show, since both land in the same tier.
     *
     * It is asserted because the report is what an author acts on: A12 coerces `7` to the spelling "7" and
     * then fails it against the set, so calling it "not a string" would name a defect the model does not
     * have and point at the wrong fix.
     */
    @Test
    fun anUnknownModeIsNamedAsA12ReadsIt() {
        assertEquals(
            true,
            model("7", "[$strict]").tracked().computationRoundingFindings().single().explain.contains("is `7`"),
            "a coerced scalar is named by its content",
        )
        assertEquals(
            true,
            model("""{"mode":"Exact"}""", "[$strict]").tracked().computationRoundingFindings().single()
                .explain.contains("is not a string"),
            "a container has no spelling to name",
        )
    }

    @Test
    fun aToleranceBandOutsideA12sWireValuesMakesTheModelUnbuildable() {
        val actual = mutableMapOf<String, List<String>>()
        actual["absent — the control"] = codesFor(null, "[$strict]")
        actual["DiffersWithToleranceRange1"] = codesFor(null, "[${banded()}]")
        actual["DiffersWithToleranceRange10"] = codesFor(null, "[${banded("DiffersWithToleranceRange10")}]")
        actual["a band that does not exist"] = codesFor(null, "[${banded("DiffersWithToleranceRange3")}]")
        actual["TR1 — our own constant NAME"] = codesFor(null, "[${banded("TR1")}]")
        actual["an explicit null"] =
            codesFor(null, """[{"operation":"42.00","toleranceRangeOp":null}]""")
        actual["a number"] = codesFor(null, """[{"operation":"42.00","toleranceRangeOp":7}]""")

        val expected = mutableMapOf<String, List<String>>()
        expected["absent — the control"] = emptyList()
        expected["DiffersWithToleranceRange1"] = emptyList()
        expected["DiffersWithToleranceRange10"] = emptyList()
        expected["a band that does not exist"] = listOf(CK_MODEL_NOT_BUILDABLE)
        expected["TR1 — our own constant NAME"] = listOf(CK_MODEL_NOT_BUILDABLE)
        expected["an explicit null"] = emptyList()
        expected["a number"] = listOf(CK_MODEL_NOT_BUILDABLE)
        assertEquals(expected, actual, "what this checker reports for each spelling of `toleranceRangeOp`")
    }

    /**
     * The pair, which is the only thing either member means — and the grid that keeps this checker from
     * refusing `RoundUp` on sight.
     *
     * The diagonal is what matters: the same mode is reported against a strict comparison and silent against
     * a banded one, so the finding cannot be attributed to the mode alone.
     */
    @Test
    fun aRoundingModeIsOnlyWrongAgainstAnAlternativeThatComparesExactly() {
        val actual = mutableMapOf<String, List<String>>()
        actual["strict · Exact"] = codesFor(""""Exact"""", "[$strict]")
        actual["strict · RoundUp"] = codesFor(""""RoundUp"""", "[$strict]")
        actual["strict · RoundDown"] = codesFor(""""RoundDown"""", "[$strict]")
        actual["banded · Exact"] = codesFor(""""Exact"""", "[${banded()}]")
        actual["banded · RoundUp"] = codesFor(""""RoundUp"""", "[${banded()}]")
        actual["banded · RoundDown"] = codesFor(""""RoundDown"""", "[${banded()}]")
        actual["absent mode · strict"] = codesFor(null, "[$strict]")

        val expected = mutableMapOf<String, List<String>>()
        expected["strict · Exact"] = emptyList()
        expected["strict · RoundUp"] = listOf("MVK_ERROR_WRONG_ROUNDING_MODE")
        expected["strict · RoundDown"] = listOf("MVK_ERROR_WRONG_ROUNDING_MODE")
        expected["banded · Exact"] = emptyList()
        // The discriminating cells: the mode refused above is silent here.
        expected["banded · RoundUp"] = emptyList()
        expected["banded · RoundDown"] = emptyList()
        // An absent mode is `Exact`, so the strict comparison it meets is legal — the default arm of the
        // same gate, and the row that would fire if absence were treated as unknown.
        expected["absent mode · strict"] = emptyList()
        assertEquals(expected, actual, "the mode and each alternative's comparison are judged together")
    }

    @Test
    fun everyAlternativeOwesItsOwnBandAndTheFindingNamesTheOnesThatDoNot() {
        val actual = mutableMapOf<String, List<String>>()
        actual["both banded"] = codesFor(""""RoundUp"""", "[${banded()},${banded("DiffersWithToleranceRange2")}]")
        actual["banded then strict"] = codesFor(""""RoundUp"""", "[${banded()},$strict]")
        actual["strict then banded"] = codesFor(""""RoundUp"""", "[$strict,${banded()}]")
        actual["both strict"] = codesFor(""""RoundUp"""", "[$strict,$strict]")

        val expected = mutableMapOf<String, List<String>>()
        expected["both banded"] = emptyList()
        expected["banded then strict"] = listOf("MVK_ERROR_WRONG_ROUNDING_MODE")
        expected["strict then banded"] = listOf("MVK_ERROR_WRONG_ROUNDING_MODE")
        // ONE finding however many alternatives are unbanded — the count belongs in the text, not in a claim
        // about how many notifications A12 raises, which no row here measures.
        expected["both strict"] = listOf("MVK_ERROR_WRONG_ROUNDING_MODE")
        assertEquals(expected, actual, "one unbanded alternative is enough, in any position")

        val named = model(""""RoundUp"""", "[${banded()},$strict]").tracked().computationRoundingFindings().single()
        assertEquals("/Catalog/PriceCalc", named.at, "the finding names the computation that carries the mode")
        assertEquals(
            true,
            named.explain.contains("alternative 2"),
            "the text must name WHICH alternative compares exactly: ${named.explain}",
        )
    }

    /**
     * A model A12 cannot build is not one this family may then judge the pair of.
     *
     * Both arms report the unbuildable member and stop, because a verdict about the pair would be this
     * checker's own reading presented as A12's about a model A12 never examined.
     */
    @Test
    fun anUnreadableMemberStopsThePairFromBeingJudged() {
        assertEquals(
            listOf(CK_MODEL_NOT_BUILDABLE),
            codesFor(""""Bankers"""", "[$strict]"),
            "an unknown mode is reported alone — not beside a pair verdict A12 never reached",
        )
        assertEquals(
            listOf(CK_MODEL_NOT_BUILDABLE),
            codesFor(""""RoundUp"""", "[${banded("DiffersWithToleranceRange3")}]"),
            "an unknown band is reported alone, though the mode would otherwise need a band here",
        )
    }

    /**
     * The shapes of `computationAlternatives` itself, which this family deliberately says nothing about.
     *
     * Each is already a finding of the alternatives family; reporting it again would name one unreadable
     * computation twice in one report, and a band cannot be read off a list A12 itself refuses.
     */
    @Test
    fun anAlternativeListThisFamilyCannotReadIsLeftToTheFamilyThatOwnsIt() {
        assertEquals(emptyList(), codesFor(""""RoundUp"""", "null"))
        assertEquals(emptyList(), codesFor(""""RoundUp"""", """{"operation":"42.00"}"""))
        assertEquals(emptyList(), codesFor(""""RoundUp"""", """["42.00"]"""))
        // …but the MODE is still reported through those shapes, because its own refusal does not depend on
        // being able to read the list.
        assertEquals(listOf(CK_MODEL_NOT_BUILDABLE), codesFor(""""Bankers"""", "null"))
    }
}

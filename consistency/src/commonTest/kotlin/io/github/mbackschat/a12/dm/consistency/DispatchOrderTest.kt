package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The order the dispatcher reports in: producer-major, declarations in declaration order.
 *
 * **This exists because a mutant proved nothing held it (2026-09-18).** Moving a family's walk into
 * the dispatcher made the walk's order a property of ONE function instead of ninety-eight, and reversing it
 * there — `declarations().reversed()` — survived `ParityCorpusTest` and every family test in the module. The
 * one test that failed was `FamilyCellReuseTest`'s, incidentally, because the paths in its diagnostic list
 * came back the other way round; that is not a control over the report's order, it is a control over which
 * cells fall whose assertion happens to be a list. So the order the acceptance matrix advertises had no
 * enforcement locus at all, which is the "parsed but never consumed" class one layer over.
 *
 * **Two declarations per family, and two families, is the smallest fixture that can fail.** One declaration
 * per family cannot see a reversed walk, and one family cannot see producer-major reporting — the corpus
 * models are exactly that shape, which is why they let the mutant through.
 *
 * **The rule NAMES are load-bearing, and the first set of them was wrong in a way the assertion hid.** They
 * were `EndsEarlyFirst`, `MixesFirst`, `EndsEarlySecond`, `MixesSecond`, and the KDoc claimed the model's
 * interleaving made a declaration-major report a different list. It does for a dispatcher walking
 * declarations in MODEL order — and not for one that sorts by path, because those names sort into their
 * family groups, so producer-major and path-major produced the identical list and a path-sorting mutant
 * survived. The names now interleave ALPHABETICALLY as well as in the model, so neither reordering can
 * coincide with the expected list.
 */
class DispatchOrderTest {

    private val source = ModelSource(
        """
        {"header":{"id":"order","locales":[{"code":"en_US"}]},
         "content":{"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Code","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Rule","id":"r0","name":"AmountRequired","Rule":{
              "errorCondition":"FieldFilled(Code) And","errorEntityRelPath":"../Code",
              "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r1","name":"BillingCheck","Rule":{
              "errorCondition":"FieldFilled(Code) And FieldFilled(Code) Or FieldFilled(Code)",
              "errorEntityRelPath":"../Code",
              "errorCode":"C2","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r2","name":"CouponRequired","Rule":{
              "errorCondition":"FieldFilled(Code) And","errorEntityRelPath":"../Code",
              "errorCode":"C3","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r3","name":"DeliveryCheck","Rule":{
              "errorCondition":"FieldFilled(Code) And FieldFilled(Code) Or FieldFilled(Code)",
              "errorEntityRelPath":"../Code",
              "errorCode":"C4","errorMessage":[{"locale":"en_US","text":"x"}]}}
          ]}}
         ]}}}
        """.trimIndent(),
    )

    @Test
    fun aPerDeclarationFamilyReportsInDeclarationOrderAndFamiliesStayProducerMajor() {
        val ofInterest = setOf("MVK_INCOMPLETE_INPUT", "MVK_BRACKET_MISSING")

        val reported = checkRead(source).findings.filter { it.code in ofInterest }.map { it.code to it.at }

        // The two claims in one list, which is why it is asserted as a list rather than as two properties:
        // completeness before connective agreement is the REGISTRY's order, and within each family the two
        // declarations appear in the order the model declares them. The four rules interleave the two
        // families both in the model and alphabetically, so a dispatcher reporting declaration-major — in
        // model order or sorted by path — produces a different list without changing either family's own
        // order.
        assertEquals(
            listOf(
                "MVK_INCOMPLETE_INPUT" to "/Order/AmountRequired",
                "MVK_INCOMPLETE_INPUT" to "/Order/CouponRequired",
                "MVK_BRACKET_MISSING" to "/Order/BillingCheck",
                "MVK_BRACKET_MISSING" to "/Order/DeliveryCheck",
            ),
            reported,
            "the report must stay producer-major with each family's declarations in declaration order",
        )
    }
}

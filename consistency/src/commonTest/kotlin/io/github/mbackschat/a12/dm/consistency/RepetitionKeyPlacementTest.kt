package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of "where a `RepetitionNotUnique` key has to sit".
 *
 * Mirrors `rulekit.validate.laws.RepetitionNotUniqueKeyPathLawsTest`, row for row, on the same model shape —
 * so the cells where A12 reports a DIFFERENT class are here as explicit empty expectations rather than as
 * rows nobody wrote. Those are the family's whole risk: `MVK_REPEATABLE_GROUP_MISSING` is drawn by a key
 * with no repetition between it and the rule's group, but a key OUTSIDE that group hands the verdict to
 * another class entirely, and a checker built from the first half alone reports where A12 accepts.
 */
class RepetitionKeyPlacementTest {

    private val missing = "MVK_REPEATABLE_GROUP_MISSING"

    @Test
    fun aKeyWithNoRepetitionBetweenItAndTheRuleGroupIsReported() {
        val actual = mutableMapOf<String, List<String>>()
        actual["above | sole key in the repeated group"] =
            codes("Basket", "../Lines/Sku", "RepetitionNotUnique(Lines/Sku)")
        actual["above | second key in the same repeated group"] =
            codes("Basket", "../Lines/Sku", "RepetitionNotUnique(Lines/Sku, Lines/Variant)")
        actual["above | second key in a non-repeatable group"] =
            codes("Basket", "../Lines/Sku", "RepetitionNotUnique(Lines/Sku, Buyer/BuyerEmail)")
        actual["above | second key in a parallel repeatable group"] =
            codes("Basket", "../Lines/Sku", "RepetitionNotUnique(Lines/Sku, Coupons/CouponCode)")
        actual["above | TWO keys each lacking a repetition"] =
            codes("Basket", "../Buyer/BuyerEmail", "RepetitionNotUnique(Buyer/BuyerEmail, BasketRef)")
        actual["at | sole key in the repeated group"] =
            codes("Lines", "../Sku", "RepetitionNotUnique(Sku)")
        actual["at | second key in the same repeated group"] =
            codes("Lines", "../Sku", "RepetitionNotUnique(Sku, Variant)")
        actual["at | second key in a non-repeatable group"] =
            codes("Lines", "../Sku", "RepetitionNotUnique(Sku, /Basket/Buyer/BuyerEmail)")
        actual["at | second key in a parallel repeatable group"] =
            codes("Lines", "../Sku", "RepetitionNotUnique(Sku, /Basket/Coupons/CouponCode)")
        actual["at | key below a NESTED repeatable group"] =
            codes("Lines", "../Parts/PartCode", "RepetitionNotUnique(Parts/PartCode)")
        actual["at | key in a NESTED non-repeatable group"] =
            codes("Lines", "../Meta/MetaNote", "RepetitionNotUnique(Meta/MetaNote)")
        actual["at | key TWO levels below a nested repeatable group"] =
            codes("Lines", "../Parts/Detail/DetailNote", "RepetitionNotUnique(Parts/Detail/DetailNote)")

        val expected = mutableMapOf<String, List<String>>()
        expected["above | sole key in the repeated group"] = emptyList()
        expected["above | second key in the same repeated group"] = emptyList()
        expected["above | second key in a non-repeatable group"] = listOf(missing)
        // A12 draws MVK_NO_WILDCARD here — a law about what the error field leaves unpinned, which no family
        // owns yet. Reporting this class instead would name the wrong fix.
        expected["above | second key in a parallel repeatable group"] = emptyList()
        // ONE finding, not one per key: A12's own multiplicity, and the message names both keys.
        expected["above | TWO keys each lacking a repetition"] = listOf(missing)
        expected["at | sole key in the repeated group"] = listOf(missing)
        expected["at | second key in the same repeated group"] = listOf(missing)
        // A12 draws MVK_NOT_IN_RULEGROUP: the outside key is judged, not the one without a repetition. This
        // is the cell that separated the two readings of the pre-emption, and the checker must stay silent.
        expected["at | second key in a non-repeatable group"] = emptyList()
        expected["at | second key in a parallel repeatable group"] = emptyList()
        expected["at | key below a NESTED repeatable group"] = emptyList()
        expected["at | key in a NESTED non-repeatable group"] = listOf(missing)
        // The repeatable group is an ANCESTOR here, not the key's own parent — the row that separates
        // "a repeatable group anywhere between" from "the key's parent is repeatable". A checker reading
        // only the parent reports where A12 admits, and nothing else in this grid can see that.
        expected["at | key TWO levels below a nested repeatable group"] = emptyList()
        assertEquals(expected, actual, "RepetitionNotUnique key placement")
    }

    /**
     * …and the shapes this family declines, each because A12 answers it with a class of its own.
     *
     * They are asserted rather than left unwritten because an empty expectation nobody states reads the same
     * as a row nobody thought of, and three of these four would otherwise be silently reachable.
     */
    @Test
    fun theShapesAnotherClassOwnsAreLeftToA12() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a STARRED key"] = codes("Basket", "../Lines/Sku", "RepetitionNotUnique(Lines*/Sku)")
        actual["a key repeated in the list"] =
            codes("Basket", "../Buyer/BuyerEmail", "RepetitionNotUnique(Buyer/BuyerEmail, Buyer/BuyerEmail)")
        actual["the @From scope form"] =
            codes("Basket", "../Lines/Sku", "RepetitionNotUnique(Lines/Sku @From Lines)")

        val expected = mutableMapOf<String, List<String>>()
        // MVK_NO_WILDCARDS_ALLOWED is A12's, and the wildcard family already reports the star.
        expected["a STARRED key"] = emptyList()
        // MVK_DUPLICATE_PARAM1. The duplicate was measured only where the key ALSO had its repetition, so
        // whether it still wins without one is unmeasured — the one silence here resting on that rather than
        // on a measured row.
        expected["a key repeated in the list"] = emptyList()
        // The scope form changes what the keys are read against, and the law was measured without it. The
        // reader now reduces a clause-carrying operand — `RepetitionScopeGroupTest` is built on that — but
        // `CallOperand.path` deliberately keeps its narrower "a bare spec and nothing else" contract, so the
        // silence still needs no branch of its own.
        expected["the @From scope form"] = emptyList()
        assertEquals(expected, actual, "the shapes another class owns")
    }

    /** Every code this family draws about the rule, which is the only declaration these models defect in. */
    private fun codes(inGroup: String, errorField: String, condition: String): List<String> =
        checkRead(ModelSource(model(inGroup, errorField, condition))).findings
            .filter { it.code == missing }
            .map { it.code }

    /**
     * The cart shape the laws test measures on, with the rule placed in [inGroup].
     *
     * `Lines` carries both depth carriers — a repeatable `Parts` and a non-repeatable `Meta` — because the
     * measured claim is about a repeatable group ANYWHERE between the rule's group and the key, which one
     * level cannot separate from "the key's own parent".
     */
    private fun model(inGroup: String, errorField: String, condition: String): String {
        val rule = """
            {"type":"Rule","id":"r0","name":"Probe","Rule":{
              "errorCondition":"$condition","errorEntityRelPath":"$errorField",
              "errorCode":"C0","errorMessage":[{"locale":"en_US","text":"x"}]}}
        """.trimIndent()
        val inBasket = if (inGroup == "Basket") ",$rule" else ""
        val inLines = if (inGroup == "Lines") ",$rule" else ""
        return """
            {"header":{"id":"cart","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Basket","Group":{"elements":[
                {"type":"Field","id":"f0","name":"BasketRef","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Group","id":"g1","name":"Buyer","Group":{"elements":[
                  {"type":"Field","id":"f1","name":"BuyerEmail","Field":{"fieldType":{"type":"StringType"}}}
                ]}},
                {"type":"Group","id":"g2","name":"Lines","Group":{"repeatability":30,"elements":[
                  {"type":"Field","id":"f2","name":"Sku","Field":{"fieldType":{"type":"StringType"}}},
                  {"type":"Field","id":"f3","name":"Variant","Field":{"fieldType":{"type":"StringType"}}},
                  {"type":"Group","id":"g3","name":"Parts","Group":{"repeatability":5,"elements":[
                    {"type":"Field","id":"f4","name":"PartCode","Field":{"fieldType":{"type":"StringType"}}},
                    {"type":"Group","id":"g6","name":"Detail","Group":{"elements":[
                      {"type":"Field","id":"f7","name":"DetailNote","Field":{"fieldType":{"type":"StringType"}}}
                    ]}}
                  ]}},
                  {"type":"Group","id":"g4","name":"Meta","Group":{"elements":[
                    {"type":"Field","id":"f5","name":"MetaNote","Field":{"fieldType":{"type":"StringType"}}}
                  ]}}$inLines
                ]}},
                {"type":"Group","id":"g5","name":"Coupons","Group":{"repeatability":10,"elements":[
                  {"type":"Field","id":"f6","name":"CouponCode","Field":{"fieldType":{"type":"StringType"}}}
                ]}}$inBasket
              ]}}
             ]}}}
        """.trimIndent()
    }
}

package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of "an A12 model DOES reach the H-model form checks, and is refused by name there".
 *
 * Mirrors `rulekit.validate.laws.HModelFormConditionReachabilityLawsTest`. The row that carries the whole
 * family is the LANGUAGE one: the identical condition text is this class in a German model and a parser
 * refusal in an English one, where the spelling is not a keyword at all — so an implementation keyed on the
 * spelling alone would name this class on a model A12 refuses for an entirely different reason.
 *
 * The other discriminating row is the ROW construct: it shares the German bundle, the H-model heritage and
 * the legacy feel, and the grammar predicate-gates it, so A12 refuses it at the parser even in German.
 */
class HModelFormConstructTest {

    private val refused = "MVK_INVALID_ROOT_GROUP_CONSTR"

    private val forms = listOf(
        "VordruckAngegeben", "VordruckNichtAngegeben", "KeinVordruckAngegeben",
        "MindestensEinVordruckAngegeben", "VordruckNichtFortlaufend", "UsbNichtFortlaufend",
    )

    @Test
    fun allSixFormConditionsAreReportedInAGermanModel() {
        assertEquals(forms.associateWith { listOf(refused) },
            forms.associateWith { codes(germanRule("$it(Bestellung)")) },
            "every measured form condition draws the A12-facing rejection")
    }

    /**
     * A construct's argument is a bare NAME, never an entity, so it is not a reference whatever it spells:
     * the COMPLETE class list is the construct's — `rulekit.validate.laws.HModelFormConditionReachabilityLawsTest
     * .theArgumentIsNeverReadAsAnEntity`, whose conjunct naming the error field keeps the unreferenced-target
     * class out of the row. `Bestellung` does not resolve from inside its own group, so a reader taking it for a
     * reference names `MVK_INVALID_ENTITY` too, a class A12 never draws there.
     */
    @Test
    fun theConstructsArgumentIsNotAReference() {
        val actual = listOf("Bestellung", "NoSuchForm").associateWith { argument ->
            check(ModelSource(germanRule("VordruckAngegeben($argument) Oder FeldAngegeben(Kennzeichen)"))).findings
                .filter { it.category == FindingCategory.A12_CONSTRAINT }.map { it.code }
        }
        assertEquals(mapOf("Bestellung" to listOf(refused), "NoSuchForm" to listOf(refused)), actual,
            "the complete class list of a form construct in a German A12 model")
    }

    /**
     * The count operation in a rule CONDITION is the one measured shape this reader cannot see, disclosed.
     *
     * `conditionPart` consumes an UNCATALOGED call as a whole condition, so the `> 0` after it is leftover
     * and the text yields no calls — the family is silent and says so. The control is the same operator in a
     * COMPUTATION, where the operation entry rule reads it and the finding is reported normally.
     */
    @Test
    fun theCountOperationInAConditionIsDisclosedRatherThanReported() {
        val report = check(ModelSource(germanRule("AnzahlAngegebenerVordrucke(Bestellung) > 0")))
        assertEquals(emptyList(), report.findings.filter { it.code == refused }.map { it.code })
        assertEquals(
            listOf<String?>("/Bestellung/Probe"),
            report.gaps.filter { it.reason.contains("AnzahlAngegebenerVordrucke") }.map { it.at },
        )
        assertEquals(emptyList(), check(ModelSource(germanRule("FeldAngegeben(Kennzeichen)")))
            .gaps.filter { it.reason.contains("H-model form construct") }.map { it.at })
    }

    @Test
    fun theSameTextDrawsNothingHereOutsideAGermanModel() {
        val actual = mutableMapOf<String, List<String>>()
        actual["the count operation in a German COMPUTATION"] =
            codes(germanComputation("{ AnzahlAngegebenerVordrucke(Bestellung) }"))
        actual["the same condition in an ENGLISH model"] = codes(englishRule("VordruckAngegeben(Order)"))
        actual["a German ROW construct, which the grammar predicate-gates"] =
            codes(germanRule("ZeilenLueckenlos(Kennzeichen)"))
        actual["an ordinary German condition — the liveness control"] =
            codes(germanRule("FeldAngegeben(Kennzeichen)"))

        val expected = mutableMapOf<String, List<String>>()
        // The second carrier agrees, measured: the operation entry rule reaches the count op's own copy of
        // the same `isA12Model()` throw.
        expected["the count operation in a German COMPUTATION"] = listOf(refused)
        // `LexerTerminals_en` carries none of these spellings, so A12 answers MVK_UNEXPECTED_TOKEN from the
        // parser — the grammar family's class, and naming this one here would be the wrong class on a
        // correct rejection.
        expected["the same condition in an ENGLISH model"] = emptyList()
        // Same bundle, same heritage, different layer: `einfacheBedingung` predicate-gates `zeilenBedingung`
        // on `isHModel()` while leaving `formularBedingung` un-predicated, so this one never reaches a check.
        expected["a German ROW construct, which the grammar predicate-gates"] = emptyList()
        expected["an ordinary German condition — the liveness control"] = emptyList()
        assertEquals(expected, actual, "where the form-construct class may and may not be named")
    }

    private fun codes(model: String): List<String> =
        check(ModelSource(model)).findings.filter { it.code == refused }.map { it.code }

    private fun germanRule(condition: String): String = german(
        """{"type":"Rule","id":"r0","name":"Probe","Rule":{
              "errorCondition":"${condition.replace("\"", "\\\"")}",
              "errorEntityRelPath":"../Kennzeichen",
              "errorCode":"D0","errorMessage":[{"locale":"de_DE","text":"x"}]}}""",
    )

    private fun germanComputation(operation: String): String = german(
        """{"type":"Computation","id":"c0","name":"Zaehler","Computation":{
              "computedFieldRelPath":"../Menge",
              "computationAlternatives":[{"operation":"${operation.replace("\"", "\\\"")}"}],
              "errorMessage":[{"locale":"de_DE","text":"x"}]}}""",
    )

    /** A German-language order sheet with a repeatable root group, so a level specifier is meaningful. */
    private fun german(declaration: String): String = """
        {"header":{"id":"bestellung","locales":[{"code":"de_DE"}]},
         "content":{"modelConfig":{"conditionLanguage":{"code":"de_DE"}},"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Bestellung","Group":{"repeatability":9,"elements":[
            {"type":"Field","id":"f0","name":"Kennzeichen","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Field","id":"f1","name":"Menge","Field":{"fieldType":{"type":"NumberType"}}},
            $declaration
          ]}}
         ]}}}
    """.trimIndent()

    /** The same sheet in English, which is where the spellings stop being keywords. */
    private fun englishRule(condition: String): String = """
        {"header":{"id":"order","locales":[{"code":"en_US"}]},
         "content":{"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Order","Group":{"repeatability":9,"elements":[
            {"type":"Field","id":"f0","name":"Marker","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Rule","id":"r0","name":"Probe","Rule":{
              "errorCondition":"${condition.replace("\"", "\\\"")}",
              "errorEntityRelPath":"../Marker",
              "errorCode":"C0","errorMessage":[{"locale":"en_US","text":"x"}]}}
          ]}}
         ]}}}
    """.trimIndent()
}

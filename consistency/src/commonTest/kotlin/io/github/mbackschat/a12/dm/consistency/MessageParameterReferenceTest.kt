package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of "a message parameter must name something its own declaration reads".
 *
 * Every row mirrors one measured against the real Kernel by
 * `rulekit.validate.laws.MessageParameterMembershipLawsTest`. The rows that report NOTHING carry most of the
 * weight: each is a shape that looks exactly like the membership failure and is a different class of A12's,
 * so a reader recognising only the plain form has to fall silent on them rather than answer with this one.
 */
class MessageParameterReferenceTest {

    private val invalidField = "INVALID_FIELD"

    private fun ruleSaying(text: String): List<String> =
        check(ModelSource(model(message = text))).findings.filter { it.at == "/Policy/Probe" }.map { it.code }

    /** The same desk in German, with the model's condition language declared — the other arm of the keying. */
    private fun germanSaying(text: String): List<String> = check(
        ModelSource(
            """
        {"header":{"id":"vertrag","locales":[{"code":"de_DE"}]},
         "content":{"modelConfig":{"conditionLanguage":{"code":"de_DE"}},"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Vertrag","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Referenz","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Rule","id":"r0","name":"Probe","Rule":{
              "errorCondition":"FeldAngegeben(Referenz)","errorEntityRelPath":"../Referenz",
              "errorCode":"P1","errorMessage":[{"locale":"de_DE","text":"$text"}]}}
          ]}}
         ]}}}
            """.trimIndent(),
        ),
    ).findings.filter { it.at == "/Vertrag/Probe" }.map { it.code }

    private fun computing(operation: String, text: String): List<String> =
        check(ModelSource(model(message = text, operation = operation)))
            .findings.filter { it.at == "/Policy/Probe" }.map { it.code }

    @Test
    fun whichParameterShapesThisFamilyReports() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a field the condition reads — the control"] = ruleSaying("see \$Reference\$")
        actual["no parameter at all — the control"] = ruleSaying("see nothing")
        actual["a sibling the condition does not read"] = ruleSaying("see \$Premium\$")
        actual["the same field with a value suffix"] = ruleSaying("see \$Premium.value\$")
        actual["a name that resolves to nothing"] = ruleSaying("see \$NoSuchField\$")
        actual["a GROUP the condition does not read"] = ruleSaying("see \$Claims\$")
        actual["a pathed field the condition does not read"] = ruleSaying("see \$Claims/Payout\$")
        actual["an escaped dollar"] = ruleSaying("cost 5\$\$")
        actual["a lone dollar"] = ruleSaying("cost 5\$")
        actual["an unterminated parameter around a real unread field"] = ruleSaying("see \$Premium")
        actual["an escaped dollar running into a name"] = ruleSaying("\$\$Premium\$ x")
        actual["a field named after a keyword"] = ruleSaying("see \$index\$")
        actual["a field named after a CATALOGED keyword"] = ruleSaying("see \$Date\$")
        actual["the same, single-quoted"] = ruleSaying("see \$'Date'\$")
        actual["two parameters sharing a dollar pair"] = ruleSaying("see \$Premium\$\$Adjuster\$")
        actual["a dollar pair inside a name"] = ruleSaying("see \$Prem\$\$ium\$")
        actual["a good parameter before an unterminated one"] = ruleSaying("see \$Premium\$ and \$Adjuster")
        actual["an ABSOLUTE path"] = ruleSaying("see \$/Policy/Premium\$")
        actual["a step above the root group"] = ruleSaying("see \$../Premium\$")
        actual["a bare step up, which here overshoots"] = ruleSaying("see \$..\$")
        actual["a non-local short name the model carries twice"] = ruleSaying("see \$Payout\$")
        actual["a name at ROOT-GROUP level that resolves to nothing"] = ruleSaying("see \$/NoSuchRoot\$")

        val expected = mutableMapOf<String, List<String>>()
        expected["a field the condition reads — the control"] = emptyList()
        expected["no parameter at all — the control"] = emptyList()
        expected["a sibling the condition does not read"] = listOf(invalidField)
        expected["the same field with a value suffix"] = listOf(invalidField)
        // Reported under A12's own class rather than the membership one: the ADDRESS is asked first, and
        // answering an unresolvable parameter with `INVALID_FIELD` would name a verdict A12 did not give.
        expected["a name that resolves to nothing"] = listOf("INVALID_ENTITY")
        // A GROUP draws the membership class too: the gate does not care what kind it resolved to.
        expected["a GROUP the condition does not read"] = listOf(invalidField)
        expected["a pathed field the condition does not read"] = listOf(invalidField)
        expected["an escaped dollar"] = emptyList()
        // The four dollar-count rows below are A12's `WRONG_NUMBER_OF_DOLLARS`, landed 2026-09-15 by
        // `MESSAGE_PARAMETER_SYNTAX`. They stay in THIS grid because what each locks is that the membership
        // family adds nothing on top — an unterminated parameter is not a reference at all.
        val dollars = listOf("WRONG_NUMBER_OF_DOLLARS")
        expected["a lone dollar"] = dollars
        // The row with teeth: the name IS a real unread field, so guessing where the parameter ended would
        // report the membership class on a text A12 refuses for a different reason entirely.
        expected["an unterminated parameter around a real unread field"] = dollars
        // `$$` is a literal dollar, so what follows is not a parameter — reading it as two delimiters would
        // lift `Premium` out of a text A12 answers with the dollar-count class instead.
        expected["an escaped dollar running into a name"] = dollars
        // The message grammar does not resolve a parameter against the CONDITION lexer's terminals, so a
        // name spelling a keyword is an ordinary reference — cataloged or not, quoted or not. Exempting such
        // names would drop real findings silently, which is the one thing silence is not free for.
        expected["a field named after a keyword"] = listOf(invalidField)
        expected["a field named after a CATALOGED keyword"] = listOf(invalidField)
        expected["the same, single-quoted"] = listOf(invalidField)
        // TWO findings, because the pair closes one parameter and opens the next — a reader treating it as
        // an escape would see one unresolvable name and report nothing on a model A12 refuses twice.
        expected["two parameters sharing a dollar pair"] = listOf(invalidField, invalidField)
        // …and the same pair mid-name leaves two names that resolve to nothing — reported under the address
        // class A12 gives, one per parameter, which is the verdict it answered this very text with.
        expected["a dollar pair inside a name"] = listOf("INVALID_ENTITY", "INVALID_ENTITY")
        // A12 refuses the whole text on the dollar count and never judges the parameter it DID close, so
        // keeping the names collected before the thread was lost would report on a model A12 admits for
        // this family's reason. The row exists because a mutant returning them survived every other one.
        expected["a good parameter before an unterminated one"] = dollars
        expected["an ABSOLUTE path"] = listOf(invalidField)
        // Only the OVERSHOOT is refused: a `..` landing inside the root group is ordinary and judged for
        // membership like any other address (measured from a rule in `Claims`).
        expected["a step above the root group"] = listOf("ERROR_TEXT_PARAMETER_TOO_MANY_PARENT_GROUPS")
        // A path ending in the step is an address too — from a deeper carrier it names the group it lands
        // on; from this root group it overshoots, so the same class answers it.
        expected["a bare step up, which here overshoots"] = listOf("ERROR_TEXT_PARAMETER_TOO_MANY_PARENT_GROUPS")
        // The short-name fallback is local-first and then model-wide, so a name the declaring group does not
        // own can match several fields — and A12 refuses the ADDRESS rather than choosing one.
        expected["a non-local short name the model carries twice"] = listOf("ERROR_TEXT_FIELDNAME_NOT_UNIQUE")
        expected["a name at ROOT-GROUP level that resolves to nothing"] = listOf("INVALID_ROOT_GROUP")
        assertEquals(expected, actual, "which parameter shapes this family reports")
    }

    /** …and the same gate on a COMPUTATION, whose membership set is its own target plus what it reads. */
    @Test
    fun aComputationsMessageAnswersToItsOwnMembers() {
        val actual = mutableMapOf<String, List<String>>()
        actual["the computed target — the control"] = computing("1", "see \$Premium\$")
        actual["a field the computation never touches"] = computing("1", "see \$Adjuster\$")
        actual["the field the operation reads"] = computing("[Bonus] + 1", "see \$Bonus\$")
        actual["a name that resolves to nothing"] = computing("1", "see \$NoSuchField\$")

        val expected = mutableMapOf<String, List<String>>()
        expected["the computed target — the control"] = emptyList()
        expected["a field the computation never touches"] = listOf(invalidField)
        expected["the field the operation reads"] = emptyList()
        expected["a name that resolves to nothing"] = listOf("INVALID_ENTITY")
        assertEquals(expected, actual, "a computation's message answers to its own members")
    }

    /**
     * The parameter forms this family deliberately does not read, and what the checker as a whole says.
     *
     * Each has its own gate in A12 with its own class, and none of them is the membership one — so a
     * membership class here would be an over-rejection on a model A12 may well admit. The grid reads EVERY
     * finding at the declaration, not just this family's, which is what makes it the tier's cross-family
     * silence check: when a sibling family lands a form, the row stops being empty and says whose class it
     * now carries.
     */
    /**
     * The name position's own TERMINALS, and the language that decides which words are terminals at all.
     *
     * Each language recognises only its own spelling; the other's falls through to an entity lookup. Both
     * directions are measured (`rulekit.validate.laws.RuleMessageParameterLanguageLawsTest`), and both are
     * mirrored, because a checker keyed on the model's language has two arms and a one-language grid tests
     * one of them.
     */
    @Test
    fun whichWordsAreTerminalsDependsOnTheModelsConditionLanguage() {
        val actual = mutableMapOf<String, List<String>>()
        actual["en model, the rule-group shorthand"] = ruleSaying("see \$RuleGroup\$")
        actual["en model, the GERMAN rule-group shorthand"] = ruleSaying("see \$RegelKontext\$")
        actual["en model, the GERMAN base-year terminal"] = ruleSaying("see \$Referenzjahr\$")
        actual["de model, the rule-group shorthand"] = germanSaying("siehe \$RegelKontext\$")
        actual["de model, the ENGLISH rule-group shorthand"] = germanSaying("siehe \$RuleGroup\$")
        actual["de model, the base-year terminal"] = germanSaying("siehe \$Referenzjahr\$")
        actual["de model, a field it reads — the control"] = germanSaying("siehe \$Referenz\$")

        val recognised = listOf("INVALID_PARAMETER")
        val notAnEntity = listOf("INVALID_ENTITY")
        val expected = mutableMapOf<String, List<String>>()
        expected["en model, the rule-group shorthand"] = recognised
        // The other language's spelling is an ordinary name here, so the reference class is the right one —
        // and excluding both spellings, which this reader did until 2026-09-15, lost exactly this finding.
        expected["en model, the GERMAN rule-group shorthand"] = notAnEntity
        expected["en model, the GERMAN base-year terminal"] = notAnEntity
        expected["de model, the rule-group shorthand"] = recognised
        expected["de model, the ENGLISH rule-group shorthand"] = notAnEntity
        expected["de model, the base-year terminal"] = listOf("NO_BASE_YEAR")
        expected["de model, a field it reads — the control"] = emptyList()
        assertEquals(expected, actual, "which words are terminals depends on the model's condition language")
    }

    @Test
    fun theFormsThisFamilyDoesNotRead() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a category suffix"] = ruleSaying("see \$Premium->Band\$")
        actual["a semantic-index key"] = ruleSaying("see \$Claims/Payout For Claims/ClaimNo For \\\"C-1\\\"\$")
        actual["a base-year terminal"] = ruleSaying("see \$BaseYear\$")
        actual["a base-year terminal with an offset"] = ruleSaying("see \$BaseYear+3\$")
        actual["a row specifier"] = ruleSaying("see \$#Zeile\$")

        val expected = actual.keys.associateWith { emptyList<String>() }.toMutableMap()
        // Landed 2026-09-15 by `BASE_YEAR_REQUIREMENT`, whose law is the MODEL's: this fixture declares no
        // base year, so the terminal has nothing to interpolate. The offset form draws the same class, which
        // is why both rows are kept — a reader recognising only the bare spelling would drop the second.
        expected["a base-year terminal"] = listOf("NO_BASE_YEAR")
        expected["a base-year terminal with an offset"] = listOf("NO_BASE_YEAR")
        // Landed 2026-09-15 by `ERROR_TEXT_SEMANTIC_INDEX`, which owns the chained key: A12 refuses it with
        // this class before membership is consulted at all, measured in
        // `rulekit.validate.laws.ErrorTextSemanticIndexLawsTest`. The row stays here because what it locks is
        // that THIS family adds nothing on top — not that the checker is silent.
        expected["a semantic-index key"] = listOf("ILLEGAL_SEMANTIC_INDEX")
        // Landed 2026-09-15 by `MESSAGE_GROUP_PARAMETER`: `$#Zeile$` is not a row specifier this checker
        // fails to read, it is a terminal A12 RECOGNISES and refuses on the modern route, measured in
        // `rulekit.validate.laws.RuleMessageGroupParameterLawsTest`. Same reason as the row above — what
        // stays locked here is that the membership family adds nothing on top.
        expected["a row specifier"] = listOf("PARAM_INVALID_IN_NEW_WORLD")
        assertEquals(expected, actual, "the parameter forms this family does not read")
    }

    /**
     * A declaration whose CONDITION is unsound has no parameter verdict at all — A12 withholds it.
     *
     * Both kinds of unsoundness, because they reach this family by different routes: a reference naming
     * nothing leaves the membership set short, and a text that does not parse leaves it EMPTY, so every
     * parameter would look unread. The second is the ordinary state of a model being edited.
     */
    @Test
    fun aDeclarationWhoseConditionIsUnsoundHasNoParameterVerdict() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a condition naming nothing"] =
            check(ModelSource(model(message = "see \$Premium\$", condition = "FieldFilled(NoSuchField)")))
                .findings.filter { it.at == "/Policy/Probe" }.map { it.code }
        actual["a condition that does not parse"] =
            check(ModelSource(model(message = "see \$Premium\$", condition = "FieldFilled(")))
                .findings.filter { it.at == "/Policy/Probe" && it.code == invalidField }.map { it.code }

        assertEquals(true, actual.getValue("a condition naming nothing").none { it == invalidField },
            actual.toString())
        assertEquals(emptyList(), actual.getValue("a condition that does not parse"))
    }

    /** The finding names the parameter and says what would satisfy it, since the fix is in one of two places. */
    @Test
    fun theFindingNamesTheParameterAndTheMemberItMustBeReadIn() {
        val finding = check(ModelSource(model(message = "see \$Premium\$")))
            .findings.single { it.code == invalidField }
        assertEquals("/Policy/Probe", finding.at)
        assertEquals(true, finding.explain.contains("Premium"), finding.explain)
        assertEquals(true, finding.explain.contains("errorCondition"), finding.explain)
    }

    /**
     * A claims policy carrying either a rule or a computation named `Probe`.
     *
     * One builder for both because the carrier IS the comparison: the same message text is judged against a
     * condition's references in one and against a computation's own members in the other.
     */
    private fun model(message: String, operation: String? = null,
                      condition: String = "FieldFilled(Reference)"): String {
        val declaration = if (operation != null) {
            """{"type":"Computation","id":"c0","name":"Probe","Computation":{
                 "computedFieldRelPath":"../Premium",
                 "computationAlternatives":[{"operation":"$operation"}],
                 "errorMessage":[{"locale":"en_US","text":"$message"}]}}"""
        } else {
            """{"type":"Rule","id":"r0","name":"Probe","Rule":{
                 "errorCondition":"$condition","errorEntityRelPath":"../Reference",
                 "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"$message"}]}}"""
        }
        return """
            {"header":{"id":"policy","locales":[{"code":"en_US"}]},
             "content":{"modelConfig":{"fieldRefByShortNameAllowed":"True"},
              "modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Policy","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f1","name":"Premium","Field":{"fieldType":{"type":"NumberType"}}},
                {"type":"Field","id":"f2","name":"Adjuster","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f3","name":"Bonus","Field":{"fieldType":{"type":"NumberType"}}},
                {"type":"Field","id":"f6","name":"index","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f7","name":"Date","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Group","id":"g2","name":"Endorsements","Group":{"elements":[
                  {"type":"Field","id":"f8","name":"Payout","Field":{"fieldType":{"type":"StringType"}}}
                ]}},
                {"type":"Group","id":"g1","name":"Claims","Group":{"repeatability":20,"elements":[
                  {"type":"Field","id":"f4","name":"ClaimNo","Field":{"fieldType":{"type":"StringType"}}},
                  {"type":"Field","id":"f5","name":"Payout","Field":{"fieldType":{"type":"StringType"}}}
                ]}},
                $declaration
              ]}}
             ]}}}
        """.trimIndent()
    }
}

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

        val expected = mutableMapOf<String, List<String>>()
        expected["a field the condition reads — the control"] = emptyList()
        expected["no parameter at all — the control"] = emptyList()
        expected["a sibling the condition does not read"] = listOf(invalidField)
        expected["the same field with a value suffix"] = listOf(invalidField)
        // A12 answers `INVALID_ENTITY` here, a different class this family does not carry — so silence,
        // never the membership code, which would name a verdict A12 did not give.
        expected["a name that resolves to nothing"] = emptyList()
        // A GROUP draws the membership class too: the gate does not care what kind it resolved to.
        expected["a GROUP the condition does not read"] = listOf(invalidField)
        expected["a pathed field the condition does not read"] = listOf(invalidField)
        expected["an escaped dollar"] = emptyList()
        // `WRONG_NUMBER_OF_DOLLARS` is A12's, and an unterminated parameter is not a reference at all.
        expected["a lone dollar"] = emptyList()
        // The row with teeth: the name IS a real unread field, so guessing where the parameter ended would
        // report the membership class on a text A12 refuses for a different reason entirely.
        expected["an unterminated parameter around a real unread field"] = emptyList()
        // `$$` is a literal dollar, so what follows is not a parameter — reading it as two delimiters would
        // lift `Premium` out of a text A12 answers with `WRONG_NUMBER_OF_DOLLARS`.
        expected["an escaped dollar running into a name"] = emptyList()
        // The message grammar does not resolve a parameter against the CONDITION lexer's terminals, so a
        // name spelling a keyword is an ordinary reference — cataloged or not, quoted or not. Exempting such
        // names would drop real findings silently, which is the one thing silence is not free for.
        expected["a field named after a keyword"] = listOf(invalidField)
        expected["a field named after a CATALOGED keyword"] = listOf(invalidField)
        expected["the same, single-quoted"] = listOf(invalidField)
        // TWO findings, because the pair closes one parameter and opens the next — a reader treating it as
        // an escape would see one unresolvable name and report nothing on a model A12 refuses twice.
        expected["two parameters sharing a dollar pair"] = listOf(invalidField, invalidField)
        // …and the same pair mid-name leaves two names that resolve to nothing, which is A12's other class.
        expected["a dollar pair inside a name"] = emptyList()
        // A12 refuses the whole text on the dollar count and never judges the parameter it DID close, so
        // keeping the names collected before the thread was lost would report on a model A12 admits for
        // this family's reason. The row exists because a mutant returning them survived every other one.
        expected["a good parameter before an unterminated one"] = emptyList()
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
        expected["a name that resolves to nothing"] = emptyList()
        assertEquals(expected, actual, "a computation's message answers to its own members")
    }

    /**
     * The parameter forms this family deliberately does not read, every one of them SILENT.
     *
     * Each has its own gate in A12 with its own class, and none of them is the membership one — so reporting
     * here would be an over-rejection on a model A12 may well admit. The list is the family's disclosed
     * scope, not a backlog: a form is added only once its own class has been measured.
     */
    @Test
    fun theFormsThisFamilyDoesNotRead() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a category suffix"] = ruleSaying("see \$Premium->Band\$")
        actual["a semantic-index key"] = ruleSaying("see \$Claims/Payout For Claims/ClaimNo For \\\"C-1\\\"\$")
        actual["a base-year terminal"] = ruleSaying("see \$BaseYear\$")
        actual["a base-year terminal with an offset"] = ruleSaying("see \$BaseYear+3\$")
        actual["a row specifier"] = ruleSaying("see \$#Zeile\$")

        assertEquals(actual.keys.associateWith { emptyList<String>() }, actual,
            "the parameter forms this family does not read")
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
    private fun model(message: String, operation: String? = null): String {
        val declaration = if (operation != null) {
            """{"type":"Computation","id":"c0","name":"Probe","Computation":{
                 "computedFieldRelPath":"../Premium",
                 "computationAlternatives":[{"operation":"$operation"}],
                 "errorMessage":[{"locale":"en_US","text":"$message"}]}}"""
        } else {
            """{"type":"Rule","id":"r0","name":"Probe","Rule":{
                 "errorCondition":"FieldFilled(Reference)","errorEntityRelPath":"../Reference",
                 "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"$message"}]}}"""
        }
        return """
            {"header":{"id":"policy","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Policy","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f1","name":"Premium","Field":{"fieldType":{"type":"NumberType"}}},
                {"type":"Field","id":"f2","name":"Adjuster","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f3","name":"Bonus","Field":{"fieldType":{"type":"NumberType"}}},
                {"type":"Field","id":"f6","name":"index","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f7","name":"Date","Field":{"fieldType":{"type":"StringType"}}},
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

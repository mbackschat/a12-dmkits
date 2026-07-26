package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.ast.Cond
import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import io.github.mbackschat.a12.dm.interpreter.model.ConditionLanguage
import io.github.mbackschat.a12.dm.interpreter.parse.parseCondition
import io.github.mbackschat.a12.dm.interpreter.parse.parseExpression
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConditionLanguageParseTest {

    @Test
    fun germanReferenceBearingConditionsProduceTheCanonicalAst() {
        assertConditionEquivalent(
            "FieldFilled(/Benefits/Note) And " +
                "[/Benefits/Lines/Amount For /Benefits/Note] > 0",
            "FeldAngegeben(/Benefits/Note) Und " +
                "[/Benefits/Lines/Amount Fuer /Benefits/Note] > 0",
        )
        assertConditionEquivalent(
            "FieldFilled(/Benefits/Note) And " +
                "Sum(/Benefits/Lines*/Amount Having " +
                "[/Benefits/Lines/Amount] == [$/Benefits/Threshold]) > 0",
            "FeldAngegeben(/Benefits/Note) Und " +
                "Summe(/Benefits/Lines*/Amount Mit " +
                "[/Benefits/Lines/Amount] == [$/Benefits/Threshold]) > 0",
        )
        assertConditionEquivalent(
            "RepetitionNotUnique(/Benefits/Lines/Key @From /Benefits/Lines)",
            "WiederholungNichtEindeutig(/Benefits/Lines/Key @Von /Benefits/Lines)",
        )
    }

    @Test
    fun germanOperatorsConstantsAndFunctionsProduceTheCanonicalAst() {
        assertConditionEquivalent(
            "[/Benefits/Threshold] DiffersWithToleranceRange1 [/Benefits/Limit]",
            "[/Benefits/Threshold] UngleichMitToleranz1 [/Benefits/Limit]",
        )
        assertConditionEquivalent(
            "[/Benefits/Note] PatternViolated \"[A-Z]+\"",
            "[/Benefits/Note] NichtWieMuster \"[A-Z]+\"",
        )
        assertConditionEquivalent(
            "GroupFilled(RuleGroup) Or GroupNotFilled(RuleGroup)",
            "KontextAngegeben(RegelKontext) Oder KontextNichtAngegeben(RegelKontext)",
        )
        assertConditionEquivalent(
            "False == False",
            "Falsch == Falsch",
        )
        assertEquals(
            parseExpression("AddDays(Today, 2)", BASE),
            parseExpression(
                "AddiereTage(Heute, 2)",
                BASE,
                language = ConditionLanguage.DE,
            ),
        )
    }

    @Test
    fun germanBooleanKeywordCaseVariantsProduceTheCanonicalAst() {
        val canonical = parseCondition(
            "FeldAngegeben(A) Und (FeldAngegeben(B) Oder FeldAngegeben(C))",
            BASE,
            language = ConditionLanguage.DE,
        )
        assertEquals(
            canonical,
            parseCondition(
                "FeldAngegeben(A) und (FeldAngegeben(B) ODER FeldAngegeben(C))",
                BASE,
                language = ConditionLanguage.DE,
            ),
        )
        assertEquals(
            canonical,
            parseCondition(
                "FeldAngegeben(A) UND (FeldAngegeben(B) oder FeldAngegeben(C))",
                BASE,
                language = ConditionLanguage.DE,
            ),
        )
    }

    @Test
    fun keywordsFromTheOtherLanguageRemainAvailableAsFieldNames() {
        assertEquals(
            Cond.Pred("FieldFilled", listOf(Expr.Field("$BASE/Fuer/Value"))),
            parseCondition("FieldFilled(Fuer/Value)", BASE),
        )
        assertEquals(
            Cond.Pred("FieldFilled", listOf(Expr.Field("$BASE/For/Value"))),
            parseCondition(
                "FeldAngegeben(For/Value)",
                BASE,
                language = ConditionLanguage.DE,
            ),
        )
        assertEquals(
            Cond.Pred("FieldFilled", listOf(Expr.Field("$BASE/Heute"))),
            parseCondition("FieldFilled(Heute)", BASE),
        )
        assertEquals(
            Cond.Pred("FieldFilled", listOf(Expr.Field("$BASE/Today"))),
            parseCondition(
                "FeldAngegeben(Today)",
                BASE,
                language = ConditionLanguage.DE,
            ),
        )
    }

    @Test
    fun foreignFunctionKeywordsCannotAcquireCanonicalEvaluatorMeaning() {
        assertFailsWith<IllegalArgumentException> {
            parseCondition(
                "FieldFilled(Note)",
                BASE,
                language = ConditionLanguage.DE,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            parseCondition(
                "FeldAngegeben(Note)",
                BASE,
                language = ConditionLanguage.EN,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            parseExpression(
                "Sum(Note)",
                BASE,
                language = ConditionLanguage.DE,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            parseExpression(
                "Summe(Note)",
                BASE,
                language = ConditionLanguage.EN,
            )
        }
    }

    @Test
    fun atFromMustMatchTheSelectedConditionLanguage() {
        assertFailsWith<IllegalArgumentException> {
            parseCondition(
                "WiederholungNichtEindeutig(Note @From Benefits)",
                BASE,
                language = ConditionLanguage.DE,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            parseCondition(
                "RepetitionNotUnique(Note @Von Benefits)",
                BASE,
                language = ConditionLanguage.EN,
            )
        }
    }

    private fun assertConditionEquivalent(english: String, german: String) {
        assertEquals(
            parseCondition(english, BASE),
            parseCondition(
                german,
                BASE,
                language = ConditionLanguage.DE,
            ),
        )
    }

    private companion object {
        const val BASE = "/Benefits"
    }
}

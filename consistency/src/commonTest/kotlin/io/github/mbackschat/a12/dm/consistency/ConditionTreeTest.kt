package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.ConditionLanguage
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionNode
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionRecognizer
import io.github.mbackschat.a12.dm.consistency.syntax.ConnectiveKind
import io.github.mbackschat.a12.dm.consistency.syntax.SyntaxEntry
import io.github.mbackschat.a12.dm.consistency.syntax.provesPresence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The connective structure a condition is written with, which A12's iteration-guard check walks
 * (ITERATION-GUARD-PORT-PROPOSAL W1). Each row renders the tree as text so a wrong nesting shows as a wrong
 * string; the leaves name the call's operator or `cmp` for a comparison.
 */
class ConditionTreeTest {

    private fun tree(text: String, language: ConditionLanguage = ConditionLanguage.EN): String? =
        ConditionRecognizer(text, language).recognize(SyntaxEntry.CONDITION).tree?.let(::render)

    private fun render(node: ConditionNode): String = when (node) {
        is ConditionNode.Call -> node.shape.operator
        is ConditionNode.Comparison -> "cmp"
        ConditionNode.Custom -> "custom"
        is ConditionNode.Junction -> buildString {
            append("(")
            node.parts.forEachIndexed { i, part ->
                if (i > 0) append(if (node.connectives[i - 1] == ConnectiveKind.AND) " & " else " | ")
                append(render(part))
            }
            append(")")
        }
    }

    @Test
    fun bracketsNestAndASingleBracketedPartIsThePartItself() {
        assertEquals(
            linkedMapOf<String, String?>(
                "flat" to "(FieldFilled & FieldNotFilled & GroupFilled)",
                "nested" to "(FieldFilled | (FieldNotFilled & GroupFilled))",
                "nested, same connective" to "(FieldFilled & (FieldNotFilled & GroupFilled))",
                "connective case" to "(FieldFilled & FieldNotFilled & GroupFilled)",
                "bracketed leaf" to "FieldFilled",
                "comparison beside a call" to "(cmp & FieldFilled)",
                "mixed, as written" to "(FieldFilled & FieldNotFilled | GroupFilled)",
                "German connectives" to "(FieldFilled & FieldNotFilled | GroupFilled)",
            ),
            linkedMapOf(
                "flat" to tree("FieldFilled(A) And FieldNotFilled(B) And GroupFilled(G)"),
                "nested" to tree("FieldFilled(A) Or (FieldNotFilled(B) And GroupFilled(G))"),
                "nested, same connective" to tree("FieldFilled(A) And (FieldNotFilled(B) And GroupFilled(G))"),
                "connective case" to tree("FieldFilled(A) and FieldNotFilled(B) AND GroupFilled(G)"),
                "bracketed leaf" to tree("(FieldFilled(A))"),
                "comparison beside a call" to tree("Sum(Lines*/Price) > 0 And FieldFilled(A)"),
                "mixed, as written" to tree("FieldFilled(A) And FieldNotFilled(B) Or GroupFilled(G)"),
                "German connectives" to tree("FeldAngegeben(A) Und FeldNichtAngegeben(B) Oder KontextAngegeben(G)",
                    ConditionLanguage.DE)?.replace("FeldAngegeben", "FieldFilled")
                    ?.replace("FeldNichtAngegeben", "FieldNotFilled")?.replace("KontextAngegeben", "GroupFilled"),
            ),
        )
    }

    /** A filter body is the entity's, not a part of the condition, so its connective never reaches the tree. */
    @Test
    fun aFilterBodyStaysWithItsEntity() {
        assertEquals("AtLeastOneFieldFilled",
            tree("AtLeastOneFieldFilled(Lines*/Sku Having (FieldFilled(Lines/Qty) Or FieldFilled(Lines/Price)))"))
        assertEquals("(cmp & FieldFilled)",
            tree("Sum(Lines*/Price Having (FieldFilled(Lines/Qty) And FieldFilled(Lines/Sku))) > 0 And FieldFilled(A)"))
    }

    /**
     * The positive-presence proof over the tree: one `And` part suffices, every `Or` part must, and a mix folds left
     * to right as written. Here only `FieldFilled` proves, so the comparison and the custom condition are the
     * parts that must count as unproven.
     */
    @Test
    fun presenceIsProvenByOneAndPartButEveryOrPart() {
        fun proven(text: String) = provesPresence(
            checkNotNull(ConditionRecognizer(text, ConditionLanguage.EN).recognize(SyntaxEntry.CONDITION).tree),
        ) { it.operator == "FieldFilled" }
        assertEquals(
            linkedMapOf(
                "custom beside a proof, Or" to false,
                "comparison beside a proof, Or" to false,
                "custom beside a proof, And" to true,
                "mixed folds left to right" to false,
                "mixed, proven last" to true,
            ),
            linkedMapOf(
                "custom beside a proof, Or" to proven("CustomCondition Plausible Or FieldFilled(A)"),
                "comparison beside a proof, Or" to proven("Sum(Lines*/Price) > 0 Or FieldFilled(A)"),
                "custom beside a proof, And" to proven("CustomCondition Plausible And FieldFilled(A)"),
                "mixed folds left to right" to proven("FieldFilled(A) And FieldNotFilled(B) Or GroupFilled(G)"),
                "mixed, proven last" to proven("FieldNotFilled(B) Or GroupFilled(G) And FieldFilled(A)"),
            ),
        )
    }

    @Test
    fun aCustomConditionIsAnOpaqueLeafAndAFailedConditionHasNoTree() {
        assertEquals("(custom & FieldFilled)", tree("CustomCondition Plausible And FieldFilled(A)"))
        assertNull(tree("FieldFilled(A) And"))
        // Fails only after the whole condition was recognized, so the tree existed and must still be withheld.
        assertNull(tree("FieldFilled(A) And FieldFilled(B) )"))
    }
}

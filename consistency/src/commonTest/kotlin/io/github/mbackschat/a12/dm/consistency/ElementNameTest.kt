package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Whether any group holds two children of the same name.
 *
 * Which pairs collide is the Kernel's, measured by `rulekit.validate.laws.ElementNameUniquenessLawsTest`. On kernel
 * 31.1.1 a collision is a sentence of A12's converter with no `MVK_*` code, published as `RK_CHILD_NAME_DUPLICATE`
 * (KF652; 30.8.1 threw instead).
 */
class ElementNameTest {

    /**
     * A model whose root group holds [children] verbatim, so a row can place any two declarations side by
     * side — including two of different kinds, which is where the "rule names are unique" reading fails.
     */
    private fun codesFor(children: String, nested: String = "") = prepare(
        assertIs<Admission.Admitted>(
            admit(
                ModelSource(
                    """
                    {"header":{"modelVersion":"29.4.0","id":"catalog","locales":[{"code":"en_US"}]},
                     "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
                      {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[
                        $children,
                        {"type":"Group","id":"g1","name":"Archive","Group":{"elements":[
                          {"type":"Field","id":"a0","name":"Filler","Field":{"fieldType":{"type":"StringType"}}}$nested
                        ]}}
                      ]}}
                     ]}}}
                    """.trimIndent(),
                ),
            ),
        ),
    ).tracked().elementNameFindings().map { it.code }

    private fun field(id: String, name: String) = """{"type":"Field","id":"$id","name":"$name","Field":{"fieldType":{"type":"StringType"}}}"""

    private fun rule(id: String, name: String) =
        """{"type":"Rule","id":"$id","name":"$name","Rule":{"errorCode":"E1"}}"""

    @Test
    fun aNameCollidesAmongOneGroupsChildrenAndNoFurther() {
        val actual = mutableMapOf<String, List<String>>()
        actual["two distinct names — the control"] = codesFor("${field("f0", "Price")},${rule("r0", "Check")}")
        actual["two rules of one name"] = codesFor("${rule("r0", "Check")},${rule("r1", "Check")}")
        actual["a rule named after a sibling FIELD"] =
            codesFor("${field("f0", "Price")},${rule("r0", "Price")}")
        actual["a rule named after a sibling GROUP"] =
            codesFor("${field("f0", "Price")},${rule("r0", "Archive")}")
        actual["the same name in two different groups"] =
            codesFor("${field("f0", "Price")},${rule("r0", "Check")}", ",${rule("r1", "Check")}")
        actual["two names differing only in case"] = codesFor("${rule("r0", "Check")},${rule("r1", "check")}")
        actual["two FIELDS of one name"] = codesFor("${field("f0", "Price")},${field("f1", "Price")}")
        actual["a field named after a sibling GROUP"] =
            codesFor("${field("f0", "Archive")},${rule("r0", "Check")}")

        val collision = listOf(RK_CHILD_NAME_DUPLICATE)
        val expected = mapOf(
            "two distinct names — the control" to listOf(),
            "two rules of one name" to collision,
            // The demand spans KINDS — the two rows a "rule names are unique" reading passes wrongly.
            "a rule named after a sibling FIELD" to collision,
            "a rule named after a sibling GROUP" to collision,
            "the same name in two different groups" to listOf(),
            // Case-SENSITIVE, unlike the `vk_vk` prefix law on the same member — two laws on one slot with
            // opposite case rules, which is the pair a checker unifies by accident.
            "two names differing only in case" to listOf(),
            // Every other row puts a RULE in the collision, so "two children" and "a rule taking a name"
            // were one observation; these two carry none.
            "two FIELDS of one name" to collision,
            "a field named after a sibling GROUP" to collision,
        )
        assertEquals(expected, actual, "how far an element name must be unique")
    }

    /**
     * A12 states one sentence per kind of later child at a shared path: three rules of one name are one, and a
     * group and a rule taking a field's name are two (KF652; the kernel's side, with the rival collapses it rules
     * out, is `adapter.laws.CheckerConverterSentenceAgreementTest.eachDroppedDeclarationIsStatedOnce`).
     */
    @Test
    fun eachKindOfLaterChildOfASharedNameIsOneSentence() {
        fun sentences(elements: String) = prepare(
            assertIs<Admission.Admitted>(
                admit(
                    ModelSource(
                        """
                        {"header":{"modelVersion":"29.4.0","id":"catalog","locales":[{"code":"en_US"}]},
                         "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
                          {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[$elements]}}
                         ]}}}
                        """.trimIndent(),
                    ),
                ),
            ),
        ).tracked().elementNameFindings().map { it.code to it.at }
        val field = """{"type":"Field","id":"f0","name":"Check","Field":{"fieldType":{"type":"StringType"}}}"""
        val group = """{"type":"Group","id":"g1","name":"Check","Group":{"elements":[]}}"""
        val sentence = RK_CHILD_NAME_DUPLICATE to "/Catalog/Check"
        assertEquals(listOf(sentence), sentences("${rule("r0", "Check")},${rule("r1", "Check")},${rule("r2", "Check")}"))
        assertEquals(listOf(sentence, sentence), sentences("$field,$group,${rule("r0", "Check")}"))
    }
}

package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Whether any group holds two children of the same name.
 *
 * Every verdict below is the Kernel's, measured by `rulekit.validate.laws.ElementNameUniquenessLawsTest`
 * (kernel 30.8.1, 2026-09-12). The collision is not a verdict about a declaration: A12 throws while building
 * the model and reports no `MVK_*` code, so the checker reports that tier rather than inventing one.
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
                    {"header":{"id":"catalog","locales":[{"code":"en_US"}]},
                     "content":{"modelRoot":{"rootGroups":[
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
    ).elementNameFindings().map { it.code }

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

        val collision = listOf("CK_MODEL_NOT_BUILDABLE")
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

    /** Three children of one name are one collision, reported once at the path they share. */
    @Test
    fun oneCollidingNameIsReportedOnceHoweverManyChildrenCarryIt() {
        val findings = prepare(
            assertIs<Admission.Admitted>(
                admit(
                    ModelSource(
                        """
                        {"header":{"id":"catalog","locales":[{"code":"en_US"}]},
                         "content":{"modelRoot":{"rootGroups":[
                          {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[
                            ${rule("r0", "Check")},${rule("r1", "Check")},${rule("r2", "Check")}
                          ]}}
                         ]}}}
                        """.trimIndent(),
                    ),
                ),
            ),
        ).elementNameFindings()
        assertEquals(1, findings.size, "one shared name is one finding")
        assertEquals("/Catalog/Check", findings.single().at)
    }
}

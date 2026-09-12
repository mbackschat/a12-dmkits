package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The references A12 declares STRUCTURALLY rather than in DSL text: a rule's `errorEntityRelPath` and a
 * computation's `computedFieldRelPath`.
 *
 * These need no condition parser, and the Kernel checks them — a rule whose error field does not resolve is
 * `MVK_ERROR_FIELD_MISSING`/`MVK_MISSING_ERROR_FIELD`, observed on this project's own probe fixture when a
 * relative path was written one level off. They resolve from the DECLARING ELEMENT, not its group: a rule
 * at `/Order/Rule` reaches its sibling field as `../Code`, which is why the two look interchangeable on a
 * root-level rule and diverge everywhere else.
 */
class DeclarationReferenceTest {

    private fun prepared(rule: String) = prepare(
        assertIs<Admission.Admitted>(
            admit(
                ModelSource(
                    """
                    {"header":{"id":"order","locales":[{"code":"en_US"}]},"content":{"modelRoot":{"rootGroups":[
                      {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
                        {"type":"Field","id":"f0","name":"Code","Field":{}},
                        {"type":"Group","id":"g1","name":"Lines","Group":{"elements":[
                          {"type":"Field","id":"f1","name":"Qty","Field":{}}
                        ]}},
                        $rule
                      ]}}
                    ]}}}
                    """.trimIndent(),
                ),
            ),
        ),
    )

    private fun rule(relPath: String) =
        """{"type":"Rule","id":"r0","name":"Check","Rule":{"errorCode":"C",
           "errorMessage":[{"locale":"en_US","text":"x"}],
           "errorEntityRelPath":"$relPath","errorCondition":"FieldFilled(Code)"}}"""

    @Test
    fun aRulesErrorFieldResolvesRelativeToTheRuleElement() {
        assertEquals(
            listOf(Reference.Resolved("/Order/Code")),
            prepared(rule("../Code")).declaredReferences().map { it.target },
        )
    }

    @Test
    fun aDeeperRelativeErrorFieldResolves() {
        assertEquals(
            listOf(Reference.Resolved("/Order/Lines/Qty")),
            prepared(rule("../Lines/Qty")).declaredReferences().map { it.target },
        )
    }

    /**
     * The discriminating case: `Code` without the `../` would resolve if the path were read from the rule's
     * GROUP, and must not, because A12 resolves it from the rule element itself.
     */
    @Test
    fun anErrorFieldPathMissingItsParentStepDoesNotResolve() {
        assertEquals(
            listOf(Reference.Undeclared("/Order/Check/Code")),
            prepared(rule("Code")).declaredReferences().map { it.target },
        )
    }

    @Test
    fun anErrorFieldNamingNothingIsAnUndeclaredReference() {
        val reference = prepared(rule("../Missing")).declaredReferences().single()

        assertEquals(Reference.Undeclared("/Order/Missing"), reference.target)
        assertEquals("/Order/Check", reference.declaredAt)
    }

    /** A computation's computed field is the same kind of reference, read from its own member. */
    @Test
    fun aComputationsComputedFieldIsResolvedTheSameWay() {
        val prepared = prepared(
            """{"type":"Computation","id":"c0","name":"Total","Computation":{
               "computedFieldRelPath":"../Code","computationAlternatives":[{"operation":"{ 1 }"}]}}""",
        )

        assertEquals(
            listOf(Reference.Resolved("/Order/Code")),
            prepared.declaredReferences().map { it.target },
        )
    }

    /**
     * Climbing to the root and descending again is ordinary; one step FURTHER is the discriminator, because
     * a resolver that clamped at the root would land on the very same element and agree with the legal case.
     * A12 admits the first and cannot even LOAD the second — `ErrorFieldRelativePathLawsTest` records that
     * row as uncoded, since `ElementPathUtils.absPath` raises during deserialization — so the checker
     * separates them, and separates the past-root case from an ordinary missing element too.
     */
    @Test
    fun climbingToTheRootResolvesButClimbingPastItDoesNot() {
        assertEquals(
            Reference.Resolved("/Order/Code"),
            prepared(rule("../../Order/Code")).declaredReferences().single().target,
        )
        assertEquals(
            Reference.AboveRoot("../../../Order/Code"),
            prepared(rule("../../../Order/Code")).declaredReferences().single().target,
            "clamping at the root would have resolved this to the same element as the legal case",
        )
    }

    /** Every declaration that carries one is reported, so a second rule's broken target is not masked. */
    @Test
    fun everyDeclaredReferenceIsReported() {
        val prepared = prepared(rule("../Code") + "," + rule("../Missing").replace("\"r0\"", "\"r1\""))

        assertEquals(
            listOf(Reference.Resolved("/Order/Code"), Reference.Undeclared("/Order/Missing")),
            prepared.declaredReferences().map { it.target },
        )
    }
}

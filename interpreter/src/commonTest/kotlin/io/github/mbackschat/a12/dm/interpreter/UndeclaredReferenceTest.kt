package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.model.ComputationAlternative
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * **A condition or computation operation that names an entity the model does not declare is REPORTED, not
 * evaluated** (G46). The kernel's consistency gate rejects such a model as `MVK_INVALID_ENTITY`, so there is no
 * legal input here to match — but the interpreter answered anyway, in three different confident ways: a presence
 * predicate read the missing cell as empty and FIRED, a starred aggregate collapsed to an empty domain and
 * PASSED, and a value position threw. All three are the same defect, so one value-independent query answers for
 * all of them before a document is read.
 *
 * **The risky half is BREADTH, and that is what most of this file tests.** A check stricter than the kernel's
 * binding rules would make legal references unauthorable, which is worse than the leniency it replaces — so
 * every reference shape the parser supports gets a positive control here: absolute, `..`-relative, the `..Name`
 * turning point, `RuleGroup`, a bare short name under `fieldRefByShortNameAllowed`, a group operand, a starred
 * operand, a multi-star, a whitespace-separated star, and a semantic index with either key form.
 * The starred cases matter most: the wildcard markers never reach the AST, so `Items* / Count` must be checked
 * as the declared `…/Items/Count` and not as a path containing a `*`.
 */
class UndeclaredReferenceTest {

    private fun field(path: String, kind: Kind = Kind.STRING) = FieldDef(path = path, kind = kind)

    /**
     * An order model with a repeatable `Items` group — enough structure for every reference shape below: a
     * root-level field, two per-row fields, and a nested repeatable group for the multi-star case.
     */
    private fun orderModel(
        rules: List<RuleDef> = emptyList(),
        computations: List<ComputationDef> = emptyList(),
        shortNames: Boolean = false,
    ) = ModelDef(
        fields = listOf(
            field("/Order/Reference"),
            field("/Order/Items/Sku"),
            field("/Order/Items/Count", Kind.NUMBER),
            field("/Order/Items/Charges/Amount", Kind.NUMBER),
        ),
        repeatableGroups = setOf("/Order/Items", "/Order/Items/Charges"),
        rules = rules,
        computations = computations,
        fieldRefByShortNameAllowed = shortNames,
    )

    private fun rule(name: String, group: String, condition: String, errorRel: String) = RuleDef(
        path = "$group/$name",
        group = group,
        errorEntityRelPath = errorRel,
        conditionText = condition,
        errorCode = name,
    )

    private fun interpreter(model: ModelDef) = DmInterpreter(model)

    // ---------------------------------------------------------------------------------------------------
    // Does-report: the three faces of the defect
    // ---------------------------------------------------------------------------------------------------

    @Test
    fun reportsAPresencePredicateNamingAnUndeclaredField() {
        // The false-FIRED face: FieldNotFilled over a nonexistent path reads "not filled" and fires.
        val model = orderModel(rules = listOf(
            rule("MissingRefRule", "/Order/Items", "FieldNotFilled(NoSuchField)", "../Sku")))
        assertEquals(
            listOf("/Order/Items/MissingRefRule" to "/Order/Items/NoSuchField"),
            interpreter(model).undeclaredReferences().map { it.locus to it.path },
        )
    }

    @Test
    fun reportsAStarredAggregateNamingAnUndeclaredField() {
        // The false-PASSED face: the aggregate's row domain is empty, so the uniqueness predicate never fires.
        val model = orderModel(rules = listOf(
            rule("StarRefRule", "/Order", "FieldValuesNotUnique(Items*/NoSuchField)", "../Reference")))
        assertEquals(
            listOf("/Order/StarRefRule" to "/Order/Items/NoSuchField"),
            interpreter(model).undeclaredReferences().map { it.locus to it.path },
        )
    }

    @Test
    fun reportsAComputationOperationNamingAnUndeclaredField() {
        // The throw face: a value position has no empty reading, so today it dies mid-computation.
        val model = orderModel(computations = listOf(ComputationDef(
            group = "/Order/Items",
            name = "CountComp",
            targetPath = "/Order/Items/Count",
            alternatives = listOf(ComputationAlternative(precondition = null, operation = "[NoSuchNumber]")),
        )))
        assertEquals(
            listOf("/Order/Items/CountComp" to "/Order/Items/NoSuchNumber"),
            interpreter(model).undeclaredReferences().map { it.locus to it.path },
        )
    }

    @Test
    fun reportsAComputationPreconditionNamingAnUndeclaredField() {
        val model = orderModel(computations = listOf(ComputationDef(
            group = "/Order/Items",
            name = "CountComp",
            targetPath = "/Order/Items/Count",
            commonPrecondition = "FieldFilled(NoSuchGuard)",
            alternatives = listOf(ComputationAlternative(precondition = null, operation = "[Count]")),
        )))
        assertEquals(
            listOf("/Order/Items/CountComp" to "/Order/Items/NoSuchGuard"),
            interpreter(model).undeclaredReferences().map { it.locus to it.path },
        )
    }

    @Test
    fun reportsEveryUndeclaredReferenceOfOneCondition() {
        // Not just the first: a consumer fixing one typo needs to see the others in the same answer.
        val model = orderModel(rules = listOf(rule("TwoRefRule", "/Order/Items",
            "FieldNotFilled(NoSuchA) And FieldNotFilled(NoSuchB)", "../Sku")))
        assertEquals(
            listOf("/Order/Items/NoSuchA", "/Order/Items/NoSuchB"),
            interpreter(model).undeclaredReferences().map { it.path },
        )
    }

    @Test
    fun reportsACandidateConditionNamingAnUndeclaredField() {
        val model = orderModel()
        val candidate = rule("EvalCandidate", "/Order/Items", "FieldNotFilled(NoSuchField)", "../Sku")
        assertEquals(
            listOf("/Order/Items/EvalCandidate" to "/Order/Items/NoSuchField"),
            interpreter(model).undeclaredReferences(candidate).map { it.locus to it.path },
        )
    }

    @Test
    fun reportsAnUndeclaredReferenceInsideAHavingFilter() {
        // The filter is a condition in its own right; excluding it would leave a whole operand class unchecked.
        val model = orderModel(rules = listOf(rule("FilteredRule", "/Order",
            "FieldValuesNotUnique(Items*/Sku Having FieldFilled(NoSuchFlag))", "../Reference")))
        // The filter's bare ref binds against the RULE's group, not the starred group — so the reported path is
        // /Order/NoSuchFlag. Asserting the exact path rather than a substring keeps the binding rule visible.
        assertEquals(listOf("/Order/NoSuchFlag"), interpreter(model).undeclaredReferences().map { it.path },
            "the Having filter's own reference is checked, at the rule's own base")
    }

    // ---------------------------------------------------------------------------------------------------
    // Does-NOT-report: every legal reference shape (the over-rejection controls)
    // ---------------------------------------------------------------------------------------------------

    @Test
    fun acceptsEveryLegalReferenceShape() {
        val legal = listOf(
            "an absolute path" to "FieldNotFilled([/Order/Reference])",
            "a bare same-group ref" to "FieldNotFilled(Sku)",
            "a `..`-relative ref" to "FieldNotFilled(../Reference)",
            "a `..Name` turning point" to "GroupFilled(..Order)",
            "the RuleGroup self-reference" to "GroupFilled(RuleGroup)",
            "a starred operand" to "FieldValuesNotUnique(../Items*/Sku)",
            "a multi-star operand" to "FieldValuesNotUnique(../Items*/Charges*/Amount)",
            "a group operand" to "GroupFilled(../Items)",
            "a whitespace-separated star" to "FieldValuesNotUnique(../Items* / Sku)",
            "a comparison over two paths" to "[Count] > [/Order/Items/Charges/Amount]",
            "a semantic index with a literal key" to "FieldFilled([Sku For \"SKU-1\"])",
            "a semantic index keyed by a field" to "FieldFilled([Sku For Count])",
        )
        for ((label, condition) in legal) {
            val model = orderModel(rules = listOf(rule("LegalRule", "/Order/Items", condition, "../Sku")))
            assertEquals(emptyList(), interpreter(model).undeclaredReferences(),
                "$label is legal and must not be reported: $condition")
        }
    }

    @Test
    fun acceptsABareShortNameOnlyWhereTheModelAllowsIt() {
        // The one binding rule that is model-config dependent (IF119). With the flag ON, `Reference` resolves
        // model-wide from a nested group; with it OFF the same text binds to the nonexistent sibling — so this
        // pair proves the check consults the model's own rule rather than a stricter invention of its own.
        val condition = "FieldNotFilled(Reference)"
        val allowed = orderModel(
            rules = listOf(rule("ShortNameRule", "/Order/Items", condition, "../Sku")), shortNames = true)
        assertEquals(emptyList(), interpreter(allowed).undeclaredReferences(),
            "a unique model-wide short name is legal when the model allows it")

        val forbidden = orderModel(
            rules = listOf(rule("ShortNameRule", "/Order/Items", condition, "../Sku")), shortNames = false)
        assertEquals(listOf("/Order/Items/Reference"), interpreter(forbidden).undeclaredReferences().map { it.path },
            "with the flag off the same text names a sibling that does not exist")
    }

    @Test
    fun acceptsAModelWithNoRulesOrComputationsAtAll() {
        assertEquals(emptyList(), interpreter(orderModel()).undeclaredReferences())
    }
}

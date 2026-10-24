package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Test

/**
 * Differential — whether a fired count over the rule's OWN group can still be raised by filling (INTERPRETER-FINDINGS
 * IF433). An unstarred group operand that binds every level of a field's path, as `RuleGroup` or the group's spelled
 * path does for a rule declared in it, reads that field in the current row alone, so a full row leaves nothing to
 * fill and A12 types `count == capacity` a value error. The kernel twin is a12-kernel-lean's `k22rg` row `d7`.
 *
 * Every rule is declared in the repeatable `Claims`, whose declared capacity of three exceeds the rows a document
 * holds, so a reading that starred the bound level would see its undeclared rows as fillable. Own domain: an
 * insurance policy whose claims carry a note, one document reference and up to two parts.
 */
class RuleGroupCountPolarityDiffTest {

    private val policy = DocumentModelDraft.named("policy-rule-group-count").locale("en_US")
        .rootGroup("Policy") { p -> p
            .stringField("Title") { }
            .group("Claims") { c -> c.repeatable(3)
                .numberField("Amount") { n -> n.fractionDigits(0) }
                .stringField("Note") { }
                .group("Docs") { d -> d.stringField("Ref") { } }
                .group("Parts") { r -> r.repeatable(2).stringField("Part") { } } }
            .group("Holders") { h -> h.repeatable(3)
                .stringField("First") { }
                .stringField("Last") { } } }
        .build().toJson()

    private val rules = listOf(
        "full" to "FieldFilled(Amount) And NumberOfFilledFields(RuleGroup) == 5",
        "short" to "FieldFilled(Amount) And NumberOfFilledFields(RuleGroup) == 4",
        "spelled" to "FieldFilled(Amount) And NumberOfFilledFields(/Policy/Claims) == 5",
        "below" to "FieldFilled(Amount) And NumberOfFilledFields(RuleGroup) < 6",
        "all" to "FieldFilled(Amount) And AllFieldsFilled(RuleGroup)",
    )

    private val model = rules.fold(policy) { json, (code, condition) ->
        RuntimeLaws.withRule(json, "/Policy/Claims", code, "../Amount", code, condition)
    }.let { RuntimeLaws.withRule(it, "/Policy/Holders", "none", "../First", "none",
        "FieldFilled(First) And NoFieldValueIncludedInValueList(RuleGroup In \"zz\")") }

    private fun claim(parts: Int) =
        """{"Amount":1,"Note":"n","Docs":{"Ref":"r"},"Parts":[${List(parts) { """{"Part":"p$it"}""" }.joinToString(",")}]}"""

    @Test
    fun aFullRowLeavesTheCountNothingToFill() = DocumentDifferential.assertAgree(model, mapOf(
        "one full claim" to """{"Policy":{"Claims":[${claim(2)}]}}""",
        "one claim short a part" to """{"Policy":{"Claims":[${claim(1)}]}}""",
        "two full claims" to """{"Policy":{"Claims":[${claim(2)},${claim(2)}]}}""",
        "a full claim beside a short one" to """{"Policy":{"Claims":[${claim(2)},${claim(1)}]}}""",
    ))

    /** The second carrier of the same read: a value-list quantifier over the rule's own group. */
    @Test
    fun aFullRowLeavesTheQuantifierNothingToFill() = DocumentDifferential.assertAgree(model, mapOf(
        "a full holder" to """{"Policy":{"Holders":[{"First":"a","Last":"b"}]}}""",
        "a holder without a last name" to """{"Policy":{"Holders":[{"First":"a"}]}}""",
    ))
}

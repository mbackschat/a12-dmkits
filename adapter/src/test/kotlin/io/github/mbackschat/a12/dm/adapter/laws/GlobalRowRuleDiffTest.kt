package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Test

/**
 * Differential — a GLOBAL rule that iterates a keyed level runs at every declared row under a blank nonrepeatable
 * root, with no row-content gate (INTERPRETER-FINDINGS IF434, KERNEL-FINDINGS KF507's bucket). A rule is global when
 * it can fire empty, counts on its small side or names a second root; the interpreter applied that only to rules it
 * evaluates once. The kernel twin is a12-kernel-lean's `k0607`/`k0607t` runs (SPEC-2026-10-23-05).
 *
 * Every rule is declared in the root `Policy` and reports on the keyed `Claims` (capacity two), so a document holding
 * one claim or none leaves declared rows for the global rules to visit. The plain comparison is the gated control: it
 * is no global rule and must wait for its root to hold content, so it runs only beside root-blank documents. Own
 * domain: an insurance policy with keyed claims and an agency root.
 */
class GlobalRowRuleDiffTest {

    private val policy = DocumentModelDraft.named("policy-global-row-rule").locale("en_US")
        .rootGroup("Policy") { p -> p
            .stringField("Email") { }
            .group("Claims") { c -> c.repeatable(2).indexField("ClaimNo")
                .stringField("ClaimNo") { }
                .stringField("Note") { }
                .numberField("Amount") { n -> n.fractionDigits(0) }
                .group("Letter") { l -> l.stringField("Text") { } } } }
        .rootGroup("Agency") { a -> a.numberField("Rating") { n -> n.fractionDigits(0) } }
        .build().toJson()

    private val rules = listOf(
        Triple("negative", "Claims/Note", "FieldNotFilled(Claims/Note)"),
        Triple("count", "Claims/Note", "NumberOfFilledFields(Claims/Note, Claims/ClaimNo) < 1"),
        Triple("second", "Claims/Note", "[/Agency/Rating] < 5 And FieldNotFilled(Claims/Note)"),
        Triple("either", "Claims/Note", "FieldNotFilled(Claims/Note) Or FieldFilled(Claims/ClaimNo)"),
        Triple("secondOr", "Claims/Note", "[/Agency/Rating] > 5 Or FieldFilled(Claims/Note)"),
        Triple("nested", "Claims/Letter/Text", "FieldNotFilled(Claims/Letter/Text) And [/Agency/Rating] < 5"),
        Triple("valued", "Claims/Note", "[Email] == \"e\" And FieldNotFilled(Claims/Note)"),
    )

    private fun model(rules: List<Triple<String, String, String>>) = rules.fold(policy) { json, (code, target, condition) ->
        RuntimeLaws.withRule(json, "/Policy", code, "../$target", code, condition)
    }

    @Test
    fun aGlobalRuleVisitsEveryDeclaredRowUnderABlankRoot() = DocumentDifferential.assertAgree(
        model(rules + Triple("gated", "Claims/Amount", "[Claims/Amount] < 5")), mapOf(
        "a blank document" to "{}",
        "an empty policy" to """{"Policy":{}}""",
        "a low agency rating alone" to """{"Agency":{"Rating":1}}""",
        "a high agency rating alone" to """{"Agency":{"Rating":9}}""",
        "an empty policy beside a high rating" to """{"Policy":{},"Agency":{"Rating":9}}""",
        "a policy holding only its email" to """{"Policy":{"Email":"e"}}""",
    ))

    /** A global rule ignores the content of the row it runs on: the unused second claim is judged too. */
    @Test
    fun aGlobalRuleJudgesAnUnusedDeclaredRow() = DocumentDifferential.assertAgree(model(rules), mapOf(
        "one keyed claim" to """{"Policy":{"Claims":[{"ClaimNo":"a"}]}}""",
    ))
}

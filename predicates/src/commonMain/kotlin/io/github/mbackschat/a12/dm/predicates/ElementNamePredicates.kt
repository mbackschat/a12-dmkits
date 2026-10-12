package io.github.mbackschat.a12.dm.predicates

/**
 * The namespace A12 reserves for its own generated declarations.
 *
 * The Kernel generates mandatory-field and uniqueness rules named `vk_vk<field>_req` and
 * `vk_vk<field>_unique`, so a model that declares its own element in that namespace collides with them.
 */
private const val RESERVED_NAME_PREFIX = "vk_vk"

/**
 * Whether [name] draws A12's reserved-name rejection `MVK_RULE_NAME_INVALID`.
 * Formal name admission precedes this named law: dotted or overlong names have only a codeless refusal
 * (SPEC-2026-10-11-02; `RuleSlotLawsTest` and `ComputationSlotLawsTest`).
 *
 * **The match is case-INSENSITIVE**, unlike the element-name uniqueness law on the same member, which is
 * case-sensitive. The two are easy to conflate precisely because they judge the same string.
 *
 * **The code names rules and the law is not a rule's alone.** A12 rejects a COMPUTATION called `vk_vk…` with
 * exactly the same code (measured 2026-09-12 on both carriers, `validate.laws.RuleSlotLawsTest` and
 * `ComputationSlotLawsTest`), so a caller that applied this to rules only would leave computations silently
 * unchecked — which is what `Computation.Builder` did until 2026-09-12, because the code reads as a rule's
 * law. That miss is the argument for this function existing rather than for a fourth copy of the comparison.
 */
fun isReservedElementName(name: String): Boolean =
    name.regionMatches(0, RESERVED_NAME_PREFIX, 0, RESERVED_NAME_PREFIX.length, ignoreCase = true) &&
        name.length <= 100 && name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' }

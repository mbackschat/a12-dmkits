package io.github.mbackschat.a12.dm.core;

/**
 * One authored computation of an expanded model, as {@link ModelIndex#computations()} enumerates it —
 * kernel-free. The declared {@code computedFieldRelPath} is relative to the computation <em>element</em>
 * ({@code ..} pops one level, a plain segment descends; a sibling field in the same group is
 * {@code "../Field"}), and is resolved here to the absolute {@link #targetFieldPath}.
 *
 * @param path            the computation element's name-path (e.g. {@code /Claim/PayoutCalc})
 * @param groupPath       the containing group's name-path (e.g. {@code /Claim})
 * @param name            the computation's short name (last path segment)
 * @param targetFieldPath the computed field's resolved absolute name-path (e.g. {@code /Claim/Payout})
 */
public record ComputationInfo(String path, String groupPath, String name, String targetFieldPath) {
}

package io.github.mbackschat.a12.dm.adapter;

import java.util.List;
import java.util.Map;

/**
 * The complete read surface of an indexed element, extracted identically from the kernel (via
 * {@link KernelModelOracle}) and from the kernel-free {@code dm.core.ModelIndex} — the comparison unit of the
 * Stage-2 parity net ({@code ModelIndexParityTest}). Every field mirrors a {@code ModelIndex} accessor; the
 * record's value-equality is the parity assertion, so a divergence on ANY slot (kind, scale, bounds, lengths,
 * pattern, enum domain/categories, repeatability, annotations) fails the net.
 *
 * <p>Numbers are compared as {@code toPlainString} (exact scale — the CLI renders them verbatim, so the
 * behavior-neutral bar is representation, not merely numeric value).
 */
record ElementFacts(
        boolean group,
        String kind,
        Integer scale,
        String min,
        String max,
        Integer minLength,
        Integer maxLength,
        String pattern,
        List<String> enumValues,
        Map<String, List<String>> enumCategories,
        Integer repeatability,
        List<String> annotations) {
}

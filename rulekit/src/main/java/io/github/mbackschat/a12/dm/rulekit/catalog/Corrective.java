package io.github.mbackschat.a12.dm.rulekit.catalog;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A <b>code-specific rejection corrective</b> (CATALOG-SPEC §6): the fix for a kernel {@code code},
 * backed by a reject probe. A non-empty {@code whenOperands} further selects the offending comparison's
 * operand multiset; an empty list means the fix applies to the operator-specific trigger demonstrated by
 * the probe without requiring comparison-operand classification. This is the data that makes the
 * D1 class impossible ({@code MVK_INVALID_TYPE_FOR_COMPARISON} once served the string-vs-string
 * corrective on a date-vs-ISO-literal trigger): {@code lint.SpanResolver} classifies the pinned
 * operator's actual operands from the typed AST and selects the matching corrective; no match falls
 * back to the entry fix.
 *
 * <p>When present, {@code whenOperands} is an unordered multiset of operand-class tokens: a value-kind token
 * ({@code "number"}, {@code "string"}, {@code "date"}, …) matches a field or expression of that
 * kind; {@code "string-literal"} / {@code "number-literal"} match a literal node. {@code probe.dsl}
 * is a condition with exactly that operand shape which the kernel must <em>reject</em> with
 * {@code code} (executed by the facet lane's reject half), so a corrective can't document a
 * trigger that no longer exists.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Corrective(
        String code,
        List<String> whenOperands,
        String fix,
        OperatorEntry.Example probe,
        String note) {

    public Corrective {
        whenOperands = whenOperands == null ? List.of() : List.copyOf(whenOperands);
    }
}

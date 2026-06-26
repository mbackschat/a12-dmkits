package io.github.mbackschat.a12.dm.adapter.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;

/**
 * Behavioral law — <b>{@code RepetitionNotUnique}</b> (KERNEL-SEMANTICS §9): detects duplicate values
 * <em>across the rows</em> of a repeatable group, firing on each offending row. Two verified facets:
 *
 * <ul>
 *   <li>a {@code RepetitionNotUnique} rule over a <b>non-index</b> field fires on the duplicate rows
 *       (and nowhere when all values are distinct);</li>
 *   <li>the <b>index field</b> of a repeatable group already has <em>built-in</em> uniqueness (the
 *       kernel's {@code uniqueIndex} formal check), independent of any author rule — so authoring
 *       {@code RepetitionNotUnique} on the index field is redundant (and the built-in check fires
 *       instead, on the index field itself).</li>
 * </ul>
 *
 * <p>(Note: no {@code *} wildcard here — write {@code RepetitionNotUnique(Items/Count)}, not the
 * starred {@code Items}-then-{@code Count} form; the kernel rejects the asterisk with
 * {@code MVK_NO_WILDCARDS_ALLOWED}.)
 */
class RepetitionNotUniqueLawsTest {

    private static final String ORDER = RuntimeLaws.model("models/order.dm.json");

    // Conditions are authored with the rulekit typed DSL ([m]) and rendered to the canonical string fed to the
    // kernel — the test's own kernel-law assertion is the safety net. ABSOLUTE paths; the renderer relativizes.
    private static final BoundModel m = BoundModel.ofJson(ORDER);

    private static String render(Condition c) {
        return new DslRenderer().render(c);
    }

    /** Items rows with the given Count values; Sku is the (unique) index field, auto A, B, C, … */
    private static RuntimeLaws.Doc counts(String... values) {
        RuntimeLaws.Doc d = RuntimeLaws.doc().group("/Order", 1).field("/Order/CustomerName", "Acme", 1, 1);
        for (int i = 0; i < values.length; i++) {
            int r = i + 1;
            d.group("/Order/Items", 1, r).field("/Order/Items/Sku", String.valueOf((char) ('A' + i)), 1, r, 1)
                    .field("/Order/Items/Count", values[i], 1, r, 1);
        }
        return d;
    }

    @Test
    void duplicateNonIndexValuesFireOnEachOffendingRow() {
        String model = RuntimeLaws.withRule(ORDER, "/Order", "Uniq", "../Items/Count", "DUP",
                render(Dsl.repetitionNotUnique(m.field("/Order/Items/Count"))));

        // Counts 5, 5, 9 → rows 1 and 2 are duplicates
        List<RuntimeLaws.Msg> dup = RuntimeLaws.messages(model, counts("5", "5", "9")).stream()
                .filter(m -> m.code().equals("DUP")).toList();
        assertEquals(2, dup.size(), () -> "fires on each duplicate row, got: " + dup);
        assertTrue(dup.stream().anyMatch(m -> m.errorField().contains("[1, 1, 1]")), () -> "row 1: " + dup);
        assertTrue(dup.stream().anyMatch(m -> m.errorField().contains("[1, 2, 1]")), () -> "row 2: " + dup);

        // all distinct → fires nowhere
        assertEquals(false, RuntimeLaws.fires(model, "DUP", counts("5", "6", "9")),
                "distinct values are unique, so the rule fires nowhere");
    }

    @Test
    void theIndexFieldHasBuiltInUniqueness() {
        // a rule that would be redundant on the index field — duplicates of Sku (the index) are caught
        // by the kernel's own uniqueIndex check, independent of the author rule (whose code never fires)
        String model = RuntimeLaws.withRule(ORDER, "/Order", "Uniq", "../Items/Sku", "DUP_SKU",
                render(Dsl.repetitionNotUnique(m.field("/Order/Items/Sku"))));
        RuntimeLaws.Doc dupSku = RuntimeLaws.doc().group("/Order", 1).field("/Order/CustomerName", "Acme", 1, 1)
                .group("/Order/Items", 1, 1).field("/Order/Items/Sku", "A", 1, 1, 1).field("/Order/Items/Count", "1", 1, 1, 1)
                .group("/Order/Items", 1, 2).field("/Order/Items/Sku", "A", 1, 2, 1).field("/Order/Items/Count", "2", 1, 2, 1);

        java.util.Set<String> codes = RuntimeLaws.firedCodes(model, dupSku);
        assertTrue(codes.contains("uniqueIndex"),
                () -> "the kernel's built-in index uniqueness fires on a duplicate index value: " + codes);
    }
}

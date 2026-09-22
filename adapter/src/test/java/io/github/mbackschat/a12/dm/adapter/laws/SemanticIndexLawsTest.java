package io.github.mbackschat.a12.dm.adapter.laws;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;

/**
 * Behavioral law — <b>the semantic index resolves a repetition by its index-field value</b>
 * (RT-SEMANTICS-LEDGER: {@code RuntimeController} dispatch tail / {@code IndexFieldCache}; the
 * slot laws are slice 7f). Order's {@code Items} declares {@code indexFieldName: "Sku"}, so
 * {@code [Items/Count For "SKU-1"]} reads the {@code Count} of the row whose {@code Sku} is
 * {@code "SKU-1"} — without iterating.
 */
class SemanticIndexLawsTest {

    private static final String ORDER = RuntimeLaws.model("models/order.dm.json");

    // The condition is authored with the rulekit typed DSL ([m]) and rendered to the canonical string fed to the
    // kernel — the test's own kernel-law assertion is the safety net. `m.num(p).forIndex("SKU-1")` is the literal
    // semantic index `[Items/Count For "SKU-1"]`; ABSOLUTE paths render relativized.
    private static final BoundModel m = BoundModel.ofJson(ORDER);

    private static String render(Condition c) {
        return RuntimeLaws.render(c);
    }

    private static String model() {
        return RuntimeLaws.withRule(ORDER, "/Order", "Sku1Count", "../CustomerName", "SKU1_COUNT",
                render(m.str("/Order/CustomerName").filled()
                        .and(m.num("/Order/Items/Count").forIndex("SKU-1").gt(0))));
    }

    private static RuntimeLaws.Doc rows(String count1, String count2) {
        return RuntimeLaws.doc().group("/Order", 1)
                .field("/Order/CustomerName", "Acme", 1, 1)
                .group("/Order/Items", 1, 1)
                .field("/Order/Items/Sku", "SKU-1", 1, 1, 1)
                .field("/Order/Items/Count", count1, 1, 1, 1)
                .group("/Order/Items", 1, 2)
                .field("/Order/Items/Sku", "SKU-2", 1, 2, 1)
                .field("/Order/Items/Count", count2, 1, 2, 1);
    }

    @Test
    void theIndexedRowIsRead_notItsSiblings() {
        assertTrue(RuntimeLaws.fires(model(), "SKU1_COUNT", rows("5", "0")),
                "the SKU-1 row's Count (5) is read → 5 > 0 fires");
        assertFalse(RuntimeLaws.fires(model(), "SKU1_COUNT", rows("0", "5")),
                "with the counts swapped the SKU-1 row holds 0 — the sibling's 5 is not read");
    }

    @Test
    void aMissingIndexValueReadsTheKindsEmptyValue() {
        // no row carries Sku "SKU-1" → the indexed read yields the kind's EMPTY value (number → 0,
        // OMISSION) and the comparison IS evaluated — this rule's `> 0` threshold simply doesn't fire
        // on 0 (KERNEL-FINDINGS §9, corrected 2026-07-11: the earlier "not evaluated" reading couldn't
        // be distinguished by a `> 0` threshold alone).
        RuntimeLaws.Doc doc = RuntimeLaws.doc().group("/Order", 1)
                .field("/Order/CustomerName", "Acme", 1, 1)
                .group("/Order/Items", 1, 1)
                .field("/Order/Items/Sku", "SKU-9", 1, 1, 1)
                .field("/Order/Items/Count", "5", 1, 1, 1);
        assertFalse(RuntimeLaws.fires(model(), "SKU1_COUNT", doc),
                "no row carries the index value — the read is the empty 0, which this > 0 rule doesn't fire on");
    }
}

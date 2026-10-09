package io.github.mbackschat.a12.dm.rulekit.lint;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.StringRef;

/**
 * A key-selected cell and a category projection join a concatenation as {@code ""} when empty, exactly as a plain
 * text or enumeration read does (KERNEL-FINDINGS KF530), so the unguarded-concatenation lint names them too, and
 * stays quiet once the cell or the projected field is guarded. The cell is named with its key, the spelling its own
 * presence guard carries.
 *
 * <p>Own domain (an insurance policy's claims, keyed by code).
 */
class UnguardedSelectedConcatLintTest {

    private static final BoundModel M = BoundModel.ofJson(DocumentModelDraft.named("policy-keyed-lint")
            .locale("en_US")
            .rootGroup("Policy", p -> p
                    .stringField("Key", f -> { })
                    .stringField("Note", f -> { })
                    .enumField("Tier", e -> e.value("GOLD").value("SILVER").category("Band", "TOP", "MID"))
                    .group("Claims", c -> c.repeatable(5).indexField("Code")
                            .stringField("Code", f -> { })
                            .stringField("Remark", f -> { })
                            .enumField("Cover", e -> e.value("FULL").value("PART"))))
            .build().toJson());
    private static final StringRef NOTE = M.str("/Policy/Note");
    private static final FieldRef KEY = M.field("/Policy/Key");

    private static List<String> flagged(Condition condition) {
        return new Linter().lint(condition).stream()
                .filter(d -> d.code().filter("RK_UNGUARDED_STRING_CONCAT"::equals).isPresent())
                .map(d -> d.summary().substring(d.summary().indexOf("field(s) ") + 9, d.summary().indexOf(" whose")))
                .toList();
    }

    @Test
    void anUnguardedSelectedPartIsNamedWithItsKey() {
        assertEquals(List.of("/Policy/Claims/Remark For /Policy/Key"),
                flagged(NOTE.eq(M.str("/Policy/Claims/Remark").forIndex(KEY).plus("-"))));
        assertEquals(List.of("/Policy/Claims/Cover For \"C1\""),
                flagged(NOTE.eq(M.enumOf("/Policy/Claims/Cover").forIndex("C1").plus("-"))));
    }

    @Test
    void anUnguardedCategoryPartIsNamedByItsField() {
        assertEquals(List.of("/Policy/Tier"), flagged(NOTE.eq(M.enumOf("/Policy/Tier").category("Band").plus("-"))));
    }

    @Test
    void aGuardedPartIsNot() {
        var remark = M.str("/Policy/Claims/Remark");
        assertEquals(List.of(), flagged(remark.semanticIndex(KEY).filled()
                .and(NOTE.eq(remark.forIndex(KEY).plus("-")))));
        assertEquals(List.of(), flagged(M.field("/Policy/Tier").filled()
                .and(NOTE.eq(M.enumOf("/Policy/Tier").category("Band").plus("-")))));
    }
}

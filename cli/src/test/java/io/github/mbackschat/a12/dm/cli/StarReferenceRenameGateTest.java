package io.github.mbackschat.a12.dm.cli;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/**
 * {@code CG18} — the {@code *}-reference refusal {@code ReferenceRewriter} <b>advertises</b> in its own javadoc:
 * <i>"it deliberately leaves {@code *} (repetition-list) references untouched — so a rename/move a {@code *}
 * -reference would dangle must be refused."</i>
 *
 * <p>No such check exists. That is "advertise nothing you cannot point at" with the advertisement already
 * written into the source, and the rewriter's limitation is real: {@code MoveSupportDM} rewrites plain
 * references and skips starred ones, so renaming a repeatable group leaves every {@code Items*}{@code /Qty}
 * reference pointing at a path that no longer exists.
 *
 * <p><b>This file measures what happens today before proposing any gate</b>, because the outcome decides the
 * treatment: if the terminal kernel check already rejects the dangling reference, the gap is a diagnostics
 * problem (a kernel-shaped rejection where a corrective {@code RK_*} belongs); if the write succeeds, it is data
 * loss and the refusal the javadoc promises has to exist.
 */
class StarReferenceRenameGateTest {

    /** A repeatable {@code Items} whose {@code Qty} is read ONLY through a starred reference. */
    private static Path model(Path dir) throws Exception {
        String base = DocumentModelDraft.named("order").locale("en_US")
                .rootGroup("Order", o -> o
                        .field("Reference", FieldKind.STRING)
                        .group("Items", i -> i.repeatable(10).field("Qty", FieldKind.NUMBER)))
                .build().toJson();
        String withRule = EditSession.read(base)
                .addRule(m -> m.rule(m.group("/Order")).name("HasQty")
                        .errorField(m.str("/Order/Reference"))
                        .when(m.str("/Order/Reference").filled()
                                .and(Dsl.numberOfFilledFields(m.flatten("/Order/Items*/Qty")).gt(0)))
                        .code("HAS_QTY").severity(Severity.ERROR)
                        .message(en("At least one quantity is required.")).build())
                .toJson();
        Path f = dir.resolve("order.dm.json");
        Files.writeString(f, withRule);
        return f;
    }

    /**
     * <b>THE MEASUREMENT.</b> Renaming the repeatable group leaves the starred reference behind. Whatever the
     * tool does, it must not report plain success with a model whose rule now names a group that is gone.
     */
    @Test
    void renamingARepeatableGroupDoesNotSilentlyDangleItsStarredReference(@TempDir Path dir) throws Exception {
        Path model = model(dir);
        String before = Files.readString(model);

        var res = CliTestSupport.run("-m", model.toString(), "group", "rename", "/Order/Items", "--to", "Lines");

        String after = Files.readString(model);
        if (res.code() == 0) {
            // If it applied, the rewrite must have carried the starred reference across — anything else leaves
            // a rule naming `/Order/Items*` in a model whose Items no longer exists.
            assertTrue(after.contains("Lines*/Qty") && !after.contains("Items*/Qty"),
                    () -> "the rename reported success, so every reference must have moved with it — "
                            + "the starred one did not. Output: " + res.out());
        } else {
            // A refusal is the other acceptable outcome, but it must be the CORRECTIVE one the javadoc
            // promises, not a downstream kernel rejection the author cannot act on.
            assertEquals(2, res.code(),
                    () -> "a safety refusal is exit 2; a kernel-shaped rejection here is the diagnostics half of "
                            + "CG18: " + res.out());
            assertEquals(before, after, "a refusing refactor mutates nothing");
        }
    }
}

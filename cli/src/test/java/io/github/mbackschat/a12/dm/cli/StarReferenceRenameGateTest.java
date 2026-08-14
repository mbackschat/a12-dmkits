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
        assertEquals(0, res.code(), () -> "the rename applies: " + res.out());
        assertTrue(after.contains("Lines*/Qty"),
                "MEASURED: the starred reference IS carried across, so ReferenceRewriter's javadoc premise — "
                        + "that a `*`-reference is left untouched and would dangle — is false for rename");
        assertTrue(!after.contains("Items*/Qty"), "and no stale starred reference is left behind");
    }

    /**
     * <b>THE SUBSTANTIVE HALF.</b> A move does not remove the field; it changes <b>which group-operand
     * expansions contain it</b>. Moving {@code Street} out of {@code ShippingAddress} silently drops it from
     * {@code AllFieldsFilled(/Order/ShippingAddress)} — no condition text mentions the field, so there is
     * nothing for a reference rewrite to touch, and the model stays kernel-valid. `changed.rewroteReferences: []`
     * is then literally true and maximally misleading.
     *
     * <p>This row MEASURES the current behaviour rather than asserting a gate, because the right treatment is a
     * design call: a refusal is probably wrong (the move is legal and usually intended), so an advisory naming
     * which operands gain and lose the field is the likely answer.
     */
    @Test
    void movingAFieldOutOfAGroupOperandsScopeIsReportedSomehow(@TempDir Path dir) throws Exception {
        String base = DocumentModelDraft.named("order").locale("en_US")
                .rootGroup("Order", o -> o
                        .field("Reference", FieldKind.STRING)
                        .group("ShippingAddress", a -> a
                                .field("Street", FieldKind.STRING)
                                .field("City", FieldKind.STRING))
                        .group("Archive", ar -> ar.field("Note", FieldKind.STRING)))
                .build().toJson();
        Path model = dir.resolve("order.dm.json");
        Files.writeString(model, EditSession.read(base)
                .addRule(m -> m.rule(m.group("/Order")).name("AddressComplete")
                        .errorField(m.str("/Order/Reference"))
                        .when(m.str("/Order/Reference").filled()
                                .and(Dsl.notAllFieldsFilled(m.flattenGroup("/Order/ShippingAddress"))))
                        .code("ADDR").severity(Severity.ERROR)
                        .message(en("The address is incomplete.")).build())
                .toJson());

        var res = CliTestSupport.run("-m", model.toString(), "field", "move",
                "/Order/ShippingAddress/Street", "--to", "/Order/Archive");

        assertEquals(0, res.code(), () -> "the move is legal and kernel-valid, so it applies: " + res.out());
        assertTrue(res.out().contains("AddressComplete"),
                () -> "MEASURED before the fix: `applied` + `KERNEL_CONFIRMED` + `rewroteReferences: []` + no "
                        + "diagnostics, while AddressComplete's group operand silently lost a field. The move "
                        + "must NAME the rules whose expansion changed — a refusal would be wrong, since the "
                        + "move is legal and usually intended: " + res.out());
    }
}

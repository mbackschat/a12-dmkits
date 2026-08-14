package io.github.mbackschat.a12.dm.cli;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/**
 * The §6 safe-delete gate over a field reached through a <b>GROUP</b> operand — {@code CG13}, and the row that
 * turns {@code CG16}'s delete-a-live-field story from a prediction into a measurement.
 *
 * <p><b>Measured first, and it corrected the framing.</b> The obvious test — remove a field a rule references
 * DIRECTLY, through {@code apply}, which runs no §6 gate for {@code FIELD_REMOVE} — does not lose data: both ops
 * stage, and then the terminal kernel check rejects the sequence because the rule now dangles. The bypass is real
 * (exit 1 with a kernel-shaped rejection instead of exit 2 with a corrective {@code RK_REFERENCED}), but the
 * model survives.
 *
 * <p><b>The GROUP-reached case is the one that actually loses the field</b>, for exactly the reason it is hard to
 * see: {@code AllFieldsFilled(/Order/ShippingAddress)} still names a group that still exists, so removing
 * {@code Street} leaves a <b>kernel-valid</b> model. The terminal check has nothing to object to, the exact-match
 * gate finds no referrer, and the rule silently changes meaning — it now checks one fewer field. Nothing in the
 * pipeline says no. That was stated as a mechanism in {@code CG16}'s row and in this file's first draft; it is
 * measured here.
 */
class ApplyFieldRemoveGateTest {

    private static final ObjectMapper M = new ObjectMapper();

    /**
     * An order whose only rule names the GROUP {@code /Order/ShippingAddress}; {@code Spare} is reached by
     * nothing and is the control that the gate refuses on the reference, not on the operation.
     */
    private static Path model(Path dir) throws Exception {
        String base = DocumentModelDraft.named("order").locale("en_US")
                .rootGroup("Order", o -> o
                        .field("Reference", FieldKind.STRING)
                        .field("Spare", FieldKind.STRING)
                        .group("ShippingAddress", a -> a
                                .field("Street", FieldKind.STRING)
                                .field("City", FieldKind.STRING)))
                .build().toJson();
        String withRule = EditSession.read(base)
                .addRule(m -> m.rule(m.group("/Order")).name("AddressComplete")
                        .errorField(m.str("/Order/Reference"))
                        .when(m.str("/Order/Reference").filled()
                                .and(Dsl.notAllFieldsFilled(m.flattenGroup("/Order/ShippingAddress"))))
                        .code("ADDR_INCOMPLETE").severity(Severity.ERROR)
                        .message(en("The shipping address is incomplete.")).build())
                .toJson();
        Path f = dir.resolve("order.dm.json");
        Files.writeString(f, withRule);
        return f;
    }

    /**
     * <b>THE DISCRIMINATOR — the standalone verb.</b> {@code Street} is read only through the group operand, so
     * the exact-match gate saw nothing and the delete succeeded against a model that stayed kernel-valid.
     */
    @Test
    void fieldRemoveRefusesAFieldReachedThroughAGroupOperand(@TempDir Path dir) throws Exception {
        Path model = model(dir);
        String before = Files.readString(model);

        var res = CliTestSupport.run("-m", model.toString(), "field", "remove", "/Order/ShippingAddress/Street");

        assertEquals(2, res.code(),
                () -> "AddressComplete reads Street through AllFieldsFilled(/Order/ShippingAddress); removing it "
                        + "leaves a VALID model whose rule silently checks one fewer field, so nothing "
                        + "downstream catches this: " + res.out());
        JsonNode j = M.readTree(res.out());
        assertEquals("refused", j.path("outcome").asText(), res::out);
        assertEquals("RK_REFERENCED", j.path("diagnostics").get(0).path("code").asText(), res::out);
        assertTrue(j.path("diagnostics").get(0).toString().contains("ShippingAddress"),
                () -> "the refusal must name the GROUP that makes it reachable, or the author cannot tell why a "
                        + "field no rule mentions is refused: " + res.out());
        assertEquals(before, Files.readString(model), "a refusing refactor mutates nothing");
    }

    /** <b>THE SAME THROUGH {@code apply}</b>, the route that ran no §6 gate for this op at all. */
    @Test
    void applyFieldRemoveRefusesTheSameGroupReachedField(@TempDir Path dir) throws Exception {
        Path model = model(dir);
        String before = Files.readString(model);
        Path ops = dir.resolve("ops.json");
        Files.writeString(ops, """
                [ {"target":"field","op":"remove","fieldPathInModel":"/Order/ShippingAddress/Street"} ]""");

        var res = CliTestSupport.run("-m", model.toString(), "apply", ops.toString());

        assertEquals(2, res.code(), () -> "apply must run the same §6 gate as the verb: " + res.out());
        JsonNode j = M.readTree(res.out());
        assertEquals("refused", j.path("outcome").asText(), res::out);
        assertEquals(before, Files.readString(model), "a refused op rolls the sequence back");
    }

    /**
     * <b>THE CONTROL.</b> A field nothing reaches must still remove cleanly through both routes — without it,
     * "refuse every field remove" satisfies both rows above.
     */
    @Test
    void anUnreachedFieldStillRemovesThroughBothRoutes(@TempDir Path dir) throws Exception {
        Path viaVerb = model(dir);
        var res = CliTestSupport.run("-m", viaVerb.toString(), "field", "remove", "/Order/Spare");
        assertEquals(0, res.code(), res::out);
        assertFalse(Files.readString(viaVerb).contains("Spare"), "the unreached field removes cleanly");

        Path viaApply = dir.resolve("second");
        Files.createDirectories(viaApply);
        Path model2 = model(viaApply);
        Path ops = viaApply.resolve("ops.json");
        Files.writeString(ops, """
                [ {"target":"field","op":"remove","fieldPathInModel":"/Order/Spare"} ]""");
        var applied = CliTestSupport.run("-m", model2.toString(), "apply", ops.toString());
        assertEquals(0, applied.code(), applied::out);
        assertFalse(Files.readString(model2).contains("Spare"), "…through apply too");
    }

    /**
     * <b>The GROUP half of the same blindness.</b> {@code GroupRemoval.plan} asked {@code whereUsed(group)},
     * which finds a rule naming THAT group — but a rule naming an ANCESTOR reaches into it just as a rule naming
     * the group reaches into its fields. Removing {@code Region} here drops two fields out of
     * {@code AddressComplete}'s expansion while leaving the model valid, the same silent narrowing as the field
     * case one level up.
     */
    @Test
    void groupRemoveRefusesAGroupReachedThroughAnAncestorOperand(@TempDir Path dir) throws Exception {
        String base = DocumentModelDraft.named("order").locale("en_US")
                .rootGroup("Order", o -> o
                        .field("Reference", FieldKind.STRING)
                        .group("ShippingAddress", a -> a
                                .field("Street", FieldKind.STRING)
                                .group("Region", r -> r.field("Code", FieldKind.STRING))))
                .build().toJson();
        Path model = dir.resolve("nested.dm.json");
        Files.writeString(model, EditSession.read(base)
                .addRule(m -> m.rule(m.group("/Order")).name("AddressComplete")
                        .errorField(m.str("/Order/Reference"))
                        .when(m.str("/Order/Reference").filled()
                                .and(Dsl.notAllFieldsFilled(m.flattenGroup("/Order/ShippingAddress"))))
                        .code("ADDR_INCOMPLETE").severity(Severity.ERROR)
                        .message(en("The shipping address is incomplete.")).build())
                .toJson());
        String before = Files.readString(model);

        var res = CliTestSupport.run("-m", model.toString(), "group", "remove",
                "/Order/ShippingAddress/Region");

        assertEquals(2, res.code(),
                () -> "AddressComplete reaches into Region through its ancestor operand; removing the group "
                        + "narrows the rule silently and leaves a valid model: " + res.out());
        assertEquals("RK_REFERENCED", M.readTree(res.out()).path("diagnostics").get(0).path("code").asText(),
                res::out);
        assertEquals(before, Files.readString(model), "a refusing refactor mutates nothing");
    }

    /**
     * <b>THE ESCAPE HATCH stays available.</b> {@code --cascade} removes the reaching rule with the field, the
     * same contract a directly-referenced field already has. Without this the gate would be a dead end for an
     * author who genuinely wants the field gone.
     */
    @Test
    void cascadeStillRemovesTheReachingRuleWithTheField(@TempDir Path dir) throws Exception {
        Path model = model(dir);
        var res = CliTestSupport.run("-m", model.toString(), "field", "remove",
                "/Order/ShippingAddress/Street", "--cascade");
        assertEquals(0, res.code(), res::out);
        String written = Files.readString(model);
        assertFalse(written.contains("AddressComplete"), "the reaching rule is cascaded away");
        assertFalse(written.contains("\"Street\""), "and the field is gone");
    }
}

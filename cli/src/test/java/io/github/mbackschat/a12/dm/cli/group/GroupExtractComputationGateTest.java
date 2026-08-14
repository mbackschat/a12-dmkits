package io.github.mbackschat.a12.dm.cli.group;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * {@code group extract}'s outward-reference gate, in the two directions {@code GroupExtractVerbTest} does not
 * reach — {@code CG17}. CLI-SPEC §6 requires every refactor to check safety BEFORE it changes anything and to
 * refuse with corrective diagnostics when it cannot establish it; §6 additionally requires that the refusal
 * itself be tested, not only the happy path.
 *
 * <p><b>The gate is wrong in BOTH directions at once</b>, which is why they belong in one file: it misses a real
 * escape and invents one that is not there.
 *
 * <ol>
 *   <li><b>It never checks COMPUTATIONS.</b> {@code SafetyChecks.subtreeSelfContained} loops
 *       {@code adapter.ruleFullNames()}, which explicitly excludes computations — every other reference site in
 *       the repo pairs {@code whereUsed} with {@code whereUsedInComputations}. So a computation inside the
 *       subtree whose operation reads a field outside it passes the gate and moves into a sub-model that cannot
 *       see what it reads.</li>
 *   <li><b>It flags the subtree ROOT GROUP as escaping.</b> The membership test is
 *       {@code field.startsWith(groupPath + "/")}, and {@code "/Order/Shipping".startsWith("/Order/Shipping/")}
 *       is <b>false</b> — so a rule referencing the extracted group ITSELF is reported as reaching outside the
 *       subtree it is the root of. That is an over-refusal with a diagnostic that names the wrong problem, and
 *       CLAUDE.md is explicit that a refusal we invent is the more dangerous half, because the shape it blocks
 *       is legal and the user has no way to satisfy the message.</li>
 * </ol>
 *
 * <p>Both are the same segment-boundary family as the reachability walk in {@code KernelAdapter}: a path
 * relation expressed as a string operation, correct for the common case and wrong at the boundary.
 */
class GroupExtractComputationGateTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static Path write(Path dir, String name, String json) throws Exception {
        Path f = dir.resolve(name);
        Files.writeString(f, json);
        return f;
    }

    /**
     * An order whose {@code Shipping} subtree is what gets extracted; {@code Surcharge} lives inside it.
     *
     * <p>Both numbers declare the SAME fraction digits so the computation below is kernel-consistent. Without
     * that, the model draws {@code MVK_INVALID_COMPARE_DEC_PLACES} and the extract fails for a reason that has
     * nothing to do with the gate — a fixture defect that reads exactly like the gate firing.
     */
    private static String orderWithShipping() {
        return DocumentModelDraft.named("order").locale("en_US")
                .rootGroup("Order", o -> o
                        .numberField("Subtotal", f -> f.fractionDigits(0, 2))
                        .field("Reference", FieldKind.STRING)
                        .group("Shipping", sh -> sh
                                .field("Zip", FieldKind.STRING)
                                .numberField("Surcharge", f -> f.fractionDigits(0, 2))))
                .build()
                .toJson();
    }

    /**
     * <b>THE MISSED ESCAPE.</b> {@code SurchargeComp} lives at {@code /Order/Shipping} and its operation reads
     * {@code /Order/Subtotal}, which stays behind. Extracting must refuse: the computation would land in a
     * sub-model that cannot see its own operand.
     */
    @Test
    void refusesASubtreeWhoseCOMPUTATIONReadsAFieldOutsideIt(@TempDir Path dir) throws Exception {
        String withComputation = EditSession.read(orderWithShipping())
                .addComputation(m -> m.computation(m.num("/Order/Shipping/Surcharge")).name("SurchargeComp")
                        .calc(m.num("/Order/Subtotal").value())
                        .message(en("The surcharge follows the subtotal.")).build())
                .toJson();
        Path model = write(dir, "order.dm.json", withComputation);

        var res = CliTestSupport.run("-m", model.toString(), "group", "extract", "/Order/Shipping",
                "--reference", "shipping");
        assertEquals(2, res.code(),
                () -> "a computation inside the subtree reads /Order/Subtotal, which stays behind — extracting "
                        + "moves it into a sub-model that cannot see its operand, so the gate must refuse: "
                        + res.out());
        JsonNode j = M.readTree(res.out());
        assertEquals("refused", j.get("outcome").asText());
        assertTrue(j.path("diagnostics").toString().contains("RK_OUTWARD_REFERENCE"),
                () -> "the refusal must carry the outward-reference diagnostic: " + res.out());
        assertTrue(j.path("data").toString().contains("SurchargeComp"),
                () -> "the refusal must NAME the offending computation, or the author cannot act on it: "
                        + res.out());
        assertTrue(!Files.exists(dir.resolve("shipping.dm.json")),
                "a refusing refactor must mutate nothing");
    }

    /**
     * <b>THE OVER-REFUSAL.</b> {@code ShippingPresent} sits inside the subtree and references the subtree's own
     * root group. That reference travels perfectly — the group is the sub-model's root — so extraction must be
     * ALLOWED. The gate's {@code startsWith(groupPath + "/")} test says otherwise.
     */
    @Test
    void aRuleReferencingTheSubtreeRootGroupItselfDoesNotBlockExtraction(@TempDir Path dir) throws Exception {
        String withRule = EditSession.read(orderWithShipping())
                .addRule(m -> m.rule(m.group("/Order/Shipping")).name("ShippingPresent")
                        .errorField(m.str("/Order/Shipping/Zip"))
                        .when(m.group("/Order/Shipping").filled().and(m.str("/Order/Shipping/Zip").notFilled()))
                        .code("SHIP_ZIP").severity(Severity.ERROR)
                        .message(en("A shipping block needs a zip.")).build())
                .toJson();
        Path model = write(dir, "order.dm.json", withRule);

        var res = CliTestSupport.run("-m", model.toString(), "group", "extract", "/Order/Shipping",
                "--reference", "shipping");
        assertEquals(0, res.code(),
                () -> "the rule references the subtree's OWN root group, which becomes the sub-model's root and "
                        + "travels with it — refusing here blocks a legal extraction and names a problem the "
                        + "author cannot fix: " + res.out());
        assertEquals("applied", M.readTree(res.out()).get("outcome").asText());
        assertTrue(Files.exists(dir.resolve("shipping.dm.json")), "the sub-model was written");
    }

    /**
     * The POSITIVE CONTROL for the row above: a rule inside the subtree reaching a genuinely outside field must
     * still refuse. Without it, "admit the root self-reference" would also be satisfied by disabling the gate.
     */
    @Test
    void aGenuineOutwardReferenceStillRefuses(@TempDir Path dir) throws Exception {
        String withRule = EditSession.read(orderWithShipping())
                .addRule(m -> m.rule(m.group("/Order/Shipping")).name("NeedsReference")
                        .errorField(m.str("/Order/Shipping/Zip"))
                        .when(m.str("/Order/Reference").notFilled().and(m.str("/Order/Shipping/Zip").filled()))
                        .code("NEEDS_REF").severity(Severity.ERROR)
                        .message(en("An order reference is required.")).build())
                .toJson();
        Path model = write(dir, "order.dm.json", withRule);

        var res = CliTestSupport.run("-m", model.toString(), "group", "extract", "/Order/Shipping",
                "--reference", "shipping");
        assertEquals(2, res.code(), () -> "a real outward reference must still refuse: " + res.out());
        assertTrue(M.readTree(res.out()).path("data").path("escapingRules").toString()
                .contains("/Order/Shipping/NeedsReference"), () -> "and must name the rule: " + res.out());
    }
}

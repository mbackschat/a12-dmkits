package io.github.mbackschat.a12.dm.cli.model;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/**
 * {@code model usage} over a rule whose operand is a <b>GROUP</b> — {@code CG16}.
 *
 * <p><b>The mechanism, and why the primitive underneath is NOT the bug.</b> {@code KernelAdapter.whereUsed} and
 * {@code referrersByEntity} are exact by design and document it: <i>"'Direct' is exact: a rule that references an
 * enclosing group is not listed for a field inside it."</i> That contract is right and stays. The defect is that
 * a consumer asking a <b>reachability</b> question — "does anything read this field?" — called the
 * <b>exactness</b> primitive. Since a group operand expands recursively to every field below the group, a field
 * read only through {@code AllFieldsFilled(/Order/ShippingAddress)} had no direct referrer and was reported
 * {@code unreferenced}.
 *
 * <p><b>Why that is worse than a cosmetic report bug.</b> The published schema promises this map covers every
 * field path, and an agent reads {@code unreferenced} as "safe to delete". Paired with the ungated delete paths
 * ({@code CG13}), the sequence is: the report says unreferenced, the gate says zero referrers, the delete
 * succeeds — and the model stays <b>kernel-valid</b>, because the rule still names the group and merely counts
 * one fewer field, so no terminal check fires either. Nothing in the pipeline says no.
 *
 * <p><b>The fixture is built around the two controls, not around the happy path.</b> {@code Warehouse/Note} is
 * reached by nothing and must stay unreferenced — without it, "report every field as referenced" passes every
 * other row here. And {@code Billing} versus {@code BillingContact} is the segment-boundary trap: a
 * {@code startsWith} ancestor test makes {@code /Order/Billing} an ancestor of {@code /Order/BillingContact/Email},
 * which is the same defect as {@code CG17}'s {@code "/Order/Address".startsWith("/Order/Address/")} one file over.
 */
class ModelUsageGroupOperandTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * An e-commerce order whose rules reference two GROUPS and no field inside them.
     *
     * <p>{@code ShippingAddress} carries a nested {@code Region} subgroup because the kernel's expansion is
     * RECURSIVE: a rule naming the outer group reaches {@code Region/Code} too, so an implementation that
     * checked only the immediate parent would pass every other row and fail that one.
     */
    private static Path model(Path dir) throws IOException {
        String base = DocumentModelDraft.named("order-group-usage").locale("en_US")
                .rootGroup("Order", o -> o
                        .field("Reference", FieldKind.STRING)
                        .group("ShippingAddress", a -> a
                                .field("Street", FieldKind.STRING)
                                .field("City", FieldKind.STRING)
                                .group("Region", r -> r.field("Code", FieldKind.STRING)))
                        .group("Billing", b -> b.field("Note", FieldKind.STRING))
                        .group("BillingContact", c -> c.field("Email", FieldKind.STRING))
                        .group("Warehouse", w -> w.field("Note", FieldKind.STRING)))
                .build()
                .toJson();
        String withRules = EditSession.read(base)
                .addRule(m -> m.rule(m.group("/Order")).name("AddressComplete")
                        .errorField(m.str("/Order/Reference"))
                        .when(m.str("/Order/Reference").filled()
                                .and(Dsl.notAllFieldsFilled(m.flattenGroup("/Order/ShippingAddress"))))
                        .code("ADDR_INCOMPLETE").severity(Severity.ERROR)
                        .message(en("The shipping address is incomplete.")).build())
                .addRule(m -> m.rule(m.group("/Order")).name("BillingComplete")
                        .errorField(m.str("/Order/Reference"))
                        .when(m.str("/Order/Reference").filled()
                                .and(Dsl.notAllFieldsFilled(m.flattenGroup("/Order/Billing"))))
                        .code("BILL_INCOMPLETE").severity(Severity.ERROR)
                        .message(en("The billing block is incomplete.")).build())
                .toJson();
        Path f = dir.resolve("order.dm.json");
        Files.writeString(f, withRules);
        return f;
    }

    private record Usage(JsonNode byField, List<String> unreferenced) {
        List<String> referrersOf(String field) {
            List<String> out = new ArrayList<>();
            byField.path(field).forEach(r -> out.add(r.asText()));
            return out;
        }
    }

    private static Usage usage(Path dir) throws Exception {
        Path model = model(dir);
        var res = CliTestSupport.run("-m", model.toString(), "model", "usage");
        assertEquals(0, res.code(), res::err);
        JsonNode data = MAPPER.readTree(res.out()).path("data");
        List<String> unreferenced = new ArrayList<>();
        data.path("unreferenced").forEach(u -> unreferenced.add(u.asText()));
        return new Usage(data.path("usage"), unreferenced);
    }

    /**
     * <b>THE DISCRIMINATOR.</b> Both fields directly under the referenced group are reported referenced, naming
     * the rule that reaches them — and neither appears in {@code unreferenced}, which is the half an agent acts on.
     */
    @Test
    void aFieldReachedOnlyThroughAGroupOperandIsReportedReferenced(@TempDir Path dir) throws Exception {
        Usage u = usage(dir);
        for (String field : List.of("/Order/ShippingAddress/Street", "/Order/ShippingAddress/City")) {
            assertTrue(u.referrersOf(field).contains("/Order/AddressComplete"),
                    () -> field + " is read only through AllFieldsFilled(/Order/ShippingAddress) and must name "
                            + "that rule, got " + u.referrersOf(field));
            assertTrue(!u.unreferenced().contains(field),
                    () -> field + " is reachable but was listed unreferenced, which an agent reads as "
                            + "safe-to-delete: " + u.unreferenced());
        }
    }

    /**
     * The expansion is RECURSIVE, so a field in a nested subgroup of the referenced group is reached too. An
     * implementation that consulted only the immediate parent passes the row above and fails this one.
     */
    @Test
    void theReachIsRecursiveIntoANestedSubgroup(@TempDir Path dir) throws Exception {
        Usage u = usage(dir);
        assertTrue(u.referrersOf("/Order/ShippingAddress/Region/Code").contains("/Order/AddressComplete"),
                () -> "a nested subgroup's field is inside the referenced group's expansion, got "
                        + u.referrersOf("/Order/ShippingAddress/Region/Code"));
    }

    /**
     * A field reached BOTH directly and through its group is reported ONCE, with both referrers unioned. The
     * ancestor walk adds to the direct answer rather than replacing it, and a set rather than a list means the
     * same rule reaching a field by two routes is not double-counted.
     */
    @Test
    void directAndGroupReachedReferrersAreUnionedWithoutDuplication(@TempDir Path dir) throws Exception {
        Usage u = usage(dir);
        List<String> referrers = u.referrersOf("/Order/Reference");
        assertTrue(referrers.contains("/Order/AddressComplete") && referrers.contains("/Order/BillingComplete"),
                () -> "/Order/Reference is named directly by both rules: " + referrers);
        assertEquals(referrers.stream().distinct().toList(), referrers,
                () -> "a rule reaching a field by two routes must appear once: " + referrers);
    }

    /**
     * A COMPUTATION reaching the group counts too — {@code referrersByEntity} carries both element kinds, so the
     * reachability walk must not quietly become rules-only. This is the shape {@code CG17} gets wrong one file
     * over, where a gate loops {@code ruleFullNames()} and silently excludes computations.
     */
    @Test
    void aComputationReachingTheGroupCountsAsAReader(@TempDir Path dir) throws Exception {
        Path model = dir.resolve("computed.dm.json");
        String base = DocumentModelDraft.named("order-computed-usage").locale("en_US")
                .rootGroup("Order", o -> o
                        .field("Reference", FieldKind.STRING)
                        .field("Tally", FieldKind.NUMBER)
                        .group("Lines", l -> l.repeatable(10).field("Amount", FieldKind.NUMBER)))
                .build().toJson();
        Files.writeString(model, EditSession.read(base)
                .addComputation(m -> m.computation(m.num("/Order/Tally")).name("TallyComp")
                        .calc(Dsl.sum(m.flattenGroup("/Order/Lines*")).expression())
                        .message(en("The tally sums every line.")).build())
                .toJson());

        var res = CliTestSupport.run("-m", model.toString(), "model", "usage");
        assertEquals(0, res.code(), res::err);
        JsonNode usage = MAPPER.readTree(res.out()).path("data").path("usage");
        List<String> referrers = new ArrayList<>();
        usage.path("/Order/Lines/Amount").forEach(r -> referrers.add(r.asText()));
        assertTrue(referrers.contains("/Order/TallyComp"),
                () -> "Sum(/Order/Lines*) reads Amount through the group, and a computation is a reader: "
                        + referrers);
    }

    /**
     * <b>THE NEGATIVE CONTROL.</b> Without it, an implementation that reported every field as referenced would
     * satisfy every other row in this file.
     */
    @Test
    void aFieldUnderNoReferencedGroupStaysUnreferenced(@TempDir Path dir) throws Exception {
        Usage u = usage(dir);
        assertEquals(List.of(), u.referrersOf("/Order/Warehouse/Note"),
                "nothing references /Order/Warehouse or the field itself");
        assertTrue(u.unreferenced().contains("/Order/Warehouse/Note"),
                () -> "a genuinely unread field must still be reported unreferenced: " + u.unreferenced());
    }

    /**
     * <b>THE SEGMENT-BOUNDARY CONTROL.</b> {@code "/Order/BillingContact/Email".startsWith("/Order/Billing")} is
     * TRUE, so a naive prefix test reports this field as reached by {@code /Order/BillingComplete}. The ancestor
     * relation holds only at a {@code /} boundary.
     */
    @Test
    void aGroupWhoseNameIsAStringPrefixOfAnotherDoesNotReachIntoIt(@TempDir Path dir) throws Exception {
        Usage u = usage(dir);
        assertEquals(List.of(), u.referrersOf("/Order/BillingContact/Email"),
                "/Order/Billing is a string prefix of /Order/BillingContact but not an ancestor of it");
        assertTrue(u.referrersOf("/Order/Billing/Note").contains("/Order/BillingComplete"),
                () -> "the genuine child of the referenced group is still reached: "
                        + u.referrersOf("/Order/Billing/Note"));
    }
}

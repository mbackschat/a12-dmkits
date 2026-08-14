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
 * <p><b>Why that is worse than a cosmetic report bug — stated as the HYPOTHESIS it is.</b> The published schema
 * promises this map covers every field path, and an agent reads {@code unreferenced} as "safe to delete". The
 * predicted sequence, paired with the ungated delete paths ({@code CG13}): the report says unreferenced, the
 * gate says zero referrers, the delete succeeds, and the model stays kernel-valid because the rule still names
 * the group and merely counts one fewer field. <b>That end-to-end sequence is NOT measured</b> — neither here nor
 * in {@code CG13}, whose own ledger section says none of its rows is measured yet. It is the reason the reader
 * half was fixed first, not a result this file establishes; what this file measures is the reporting half alone.
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
                // A SECOND rule naming the INNER group, so /Order/ShippingAddress/Region/Code has referrers at
                // TWO levels of its ancestor chain. Without it, an implementation that stops at the first
                // non-empty ancestor passes every other row in this file — the walk must UNION, not select.
                .addRule(m -> m.rule(m.group("/Order")).name("RegionComplete")
                        .errorField(m.str("/Order/Reference"))
                        .when(m.str("/Order/Reference").filled()
                                .and(Dsl.notAllFieldsFilled(m.flattenGroup("/Order/ShippingAddress/Region"))))
                        .code("REGION_INCOMPLETE").severity(Severity.ERROR)
                        .message(en("The region block is incomplete.")).build())
                .toJson();
        Path f = dir.resolve("order.dm.json");
        Files.writeString(f, withRules);
        return f;
    }

    private record Usage(JsonNode byField, List<String> unreferenced) {
        /**
         * The referrers reported for {@code field} — asserting the KEY EXISTS first.
         *
         * <p>Without that assertion a bare {@code path(field)} yields a {@code MissingNode} that iterates zero
         * times, so "reported with no referrers" and "silently dropped from the map" are indistinguishable — and
         * the published schema promises the map covers EVERY field path. Both negative controls below would pass
         * against an implementation that omitted the field entirely.
         */
        List<String> referrersOf(String field) {
            assertTrue(byField.has(field),
                    () -> "the usage map must carry EVERY field path, and " + field + " is missing: " + byField);
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
     * <b>THE UNION ROW, and the one that pins the walk's shape.</b> {@code Region/Code} has referrers at TWO
     * levels of its ancestor chain — {@code RegionComplete} names its immediate parent, {@code AddressComplete}
     * names the grandparent — so it must report BOTH.
     *
     * <p>This is the row a cold review showed the file was missing. Every other assertion here is satisfied by an
     * implementation that stops at the first non-empty ancestor:
     * {@code if (a != null) { reaching.addAll(a); break; }}. Only a field whose chain carries referrers at more
     * than one level can tell "union" from "select the nearest".
     */
    @Test
    void referrersFromEVERYAncestorLevelAreUnionedNotJustTheNearest(@TempDir Path dir) throws Exception {
        Usage u = usage(dir);
        List<String> referrers = u.referrersOf("/Order/ShippingAddress/Region/Code");
        assertTrue(referrers.contains("/Order/RegionComplete"),
                () -> "the rule naming the immediate parent group reaches it: " + referrers);
        assertTrue(referrers.contains("/Order/AddressComplete"),
                () -> "and so does the rule naming the GRANDPARENT — a walk that stopped at the nearest "
                        + "non-empty ancestor would report only the parent: " + referrers);
    }

    /** A field named DIRECTLY keeps its direct referrers — the ancestor walk adds, it never replaces. */
    @Test
    void aDirectlyNamedFieldKeepsItsOwnReferrers(@TempDir Path dir) throws Exception {
        List<String> referrers = usage(dir).referrersOf("/Order/Reference");
        assertTrue(referrers.contains("/Order/AddressComplete") && referrers.contains("/Order/BillingComplete")
                        && referrers.contains("/Order/RegionComplete"),
                () -> "/Order/Reference is named directly by all three rules: " + referrers);
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
     * <b>The enforcement locus for the claim the schema and CLI-SPEC §6 now ADVERTISE</b> — that {@code where-used}
     * and {@code model usage} "differ on exactly the group-reached fields, by design". Both surfaces state it;
     * nothing pinned it, which is the "advertise nothing you cannot point at" failure in its usual form: the
     * sentence is true today and nothing fails when it stops being.
     *
     * <p>Both halves are asserted on ONE model, because either alone is satisfiable by a degenerate answer — the
     * first by a `where-used` that reports nothing at all, the second by a `model usage` that mirrors it.
     */
    @Test
    void whereUsedStaysExactWhereModelUsageIsReachable(@TempDir Path dir) throws Exception {
        Path model = model(dir);

        var exact = CliTestSupport.run("-m", model.toString(), "where-used",
                "/Order/ShippingAddress/Street");
        assertEquals(0, exact.code(), exact::err);
        JsonNode exactData = MAPPER.readTree(exact.out()).path("data");
        assertEquals(0, exactData.path("rules").size() + exactData.path("computations").size(),
                () -> "where-used answers 'which rules NAME this entity?' — no rule names Street: " + exact.out());

        assertTrue(usage(dir).referrersOf("/Order/ShippingAddress/Street").contains("/Order/AddressComplete"),
                "…while model usage answers 'what READS it?' and reaches through the group");

        var named = CliTestSupport.run("-m", model.toString(), "where-used", "/Order/Reference");
        assertEquals(0, named.code(), named::err);
        assertTrue(MAPPER.readTree(named.out()).path("data").path("rules").size() > 0,
                () -> "the control: on a DIRECTLY named field the two agree, so the divergence above is about "
                        + "group reach and not about where-used being empty: " + named.out());
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

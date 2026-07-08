package io.github.mbackschat.a12.dm.adapter.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import io.github.mbackschat.a12.dm.rulekit.catalog.FacetProbe;
import io.github.mbackschat.a12.dm.rulekit.catalog.OperatorCatalog;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * The catalog facet lane (CATALOG-SPEC §5) — the runtime peer of N7: every {@code emptyOperandDefaults}
 * and {@code semantics.emptyOperand} probe in {@code operators.json} is executed against the <em>real
 * kernel runtime</em>, so a facet value can never drift from the engine (the structural half — every
 * claim must CARRY a probe — is {@code OperatorCatalogTest}; this is the execution half).
 *
 * <p>Each probe runs twice, by contract:
 * <ol>
 *   <li><b>accepted</b> — the probe rule is injected on {@code /Order} and must pass {@link RuleValidator}.
 *       This kills the false-green trap: a {@code fires: false} probe whose DSL is silently invalid
 *       would otherwise "pass" for the wrong reason.</li>
 *   <li><b>fires ⟺ expected</b> — the described document instance (rows / given / empty, with 1-based
 *       {@code [n]} repetition indexing) is evaluated via {@code validateFull}; the probe's rule must
 *       fire exactly when the claim says. The lane always adds the content-bearing filler
 *       {@code /Order/CustomerName = "Acme"} (unless the probe itself places CustomerName), so every
 *       probe runs on a filled row — the KERNEL-FINDINGS §2 precondition for empty-value evaluation.</li>
 * </ol>
 */
class CatalogFacetProbesTest {

    private static final OperatorCatalog CATALOG = OperatorCatalog.load();
    private static final String ORDER = RuntimeLaws.model("models/order.dm.json");
    private static final RuleValidator VALIDATOR = new RuleValidator();
    private static final String PROBE_CODE = "FACET_PROBE";
    private static final String FILLER_FIELD = "/Order/CustomerName";

    @TestFactory
    Stream<DynamicTest> everyEmptyOperandDefaultProbeHoldsOnTheKernel() {
        return CATALOG.emptyOperandDefaults().entrySet().stream()
                .flatMap(e -> e.getValue().probes().stream()
                        .map(p -> probeTest("emptyOperandDefaults[" + e.getKey() + "]", p)));
    }

    @TestFactory
    Stream<DynamicTest> everyEmptyOperandClaimProbeHoldsOnTheKernel() {
        return CATALOG.entries().stream()
                .flatMap(e -> e.emptyOperandClaims().stream()
                        .flatMap(c -> c.probes().stream()
                                .map(p -> probeTest(e.id() + " emptyOperand[" + c.operand() + "]", p))));
    }

    @TestFactory
    Stream<DynamicTest> everyBoundaryClaimProbeHoldsOnTheKernel() {
        return CATALOG.entries().stream()
                .filter(e -> e.boundaryClaim() != null)
                .flatMap(e -> e.boundaryClaim().probes().stream()
                        .map(p -> probeTest(e.id() + " boundary", p)));
    }

    @TestFactory
    Stream<DynamicTest> everyIterationRangeClaimProbeHoldsOnTheKernel() {
        return CATALOG.entries().stream()
                .filter(e -> e.iterationRangeClaim() != null)
                .flatMap(e -> e.iterationRangeClaim().probes().stream()
                        .map(p -> probeTest(e.id() + " iterationRange", p)));
    }

    @TestFactory
    Stream<DynamicTest> everyAggregateIdentityClaimProbeHoldsOnTheKernel() {
        return CATALOG.entries().stream()
                .filter(e -> e.aggregateIdentityClaim() != null)
                .flatMap(e -> e.aggregateIdentityClaim().probes().stream()
                        .map(p -> probeTest(e.id() + " aggregateIdentity", p)));
    }

    private static DynamicTest probeTest(String claim, FacetProbe probe) {
        return dynamicTest(claim + " · " + probe.dsl(), () -> {
            DraftRule draft = new DraftRule("/Order", "FacetProbeRule", "../" + probe.errorField(),
                    probe.dsl(), PROBE_CODE, "ERROR");
            ValidationResult accepted = VALIDATOR.check(ORDER, draft);
            assertTrue(accepted.ok(),
                    () -> claim + ": probe DSL must be kernel-valid, got " + accepted.diagnostics());

            String model = RuntimeLaws.withRule(ORDER, "/Order", "FacetProbeRule",
                    "../" + probe.errorField(), PROBE_CODE, probe.dsl());
            boolean fired = RuntimeLaws.firedCodes(model, instance(probe)).contains(PROBE_CODE);
            assertEquals(probe.fires(), fired,
                    () -> claim + ": expected fires=" + probe.fires() + " for '" + probe.dsl()
                            + "' on given=" + probe.given() + " empty=" + probe.empty()
                            + (probe.note() == null ? "" : " — " + probe.note()));
        });
    }

    /** The probe's document instance: rows + given + empty with {@code [n]} indexing, ancestor groups
     *  auto-added, and the content-bearing CustomerName filler unless the probe places it itself. */
    private static RuntimeLaws.Doc instance(FacetProbe probe) {
        Map<String, String> given = new LinkedHashMap<>(probe.given());
        if (!given.containsKey(FILLER_FIELD) && !probe.empty().contains(FILLER_FIELD)) {
            given.put(FILLER_FIELD, "Acme");
        }

        Map<String, Placed> groups = new LinkedHashMap<>();   // "path[reps]" → placement, dedup by key
        List<String> rows = new ArrayList<>(probe.rows());
        rows.add("/Order");
        for (String row : rows) {
            collectAncestors(parse(row), groups, true);
        }
        given.keySet().forEach(f -> collectAncestors(parse(f), groups, false));
        probe.empty().forEach(f -> collectAncestors(parse(f), groups, false));

        RuntimeLaws.Doc doc = RuntimeLaws.doc();
        groups.values().forEach(g -> doc.group(g.path(), g.reps()));
        probe.empty().forEach(f -> {
            Placed p = parse(f);
            doc.emptyField(p.path(), p.reps());
        });
        given.forEach((f, v) -> {
            Placed p = parse(f);
            doc.field(p.path(), v, p.reps());
        });
        return doc;
    }

    /** Add group placements for {@code p} itself (when {@code selfIsGroup}) and all its ancestors. */
    private static void collectAncestors(Placed p, Map<String, Placed> groups, boolean selfIsGroup) {
        String[] parts = p.path().substring(1).split("/");
        int upTo = selfIsGroup ? parts.length : parts.length - 1;
        StringBuilder path = new StringBuilder();
        for (int i = 0; i < upTo; i++) {
            path.append('/').append(parts[i]);
            int[] reps = java.util.Arrays.copyOfRange(p.reps(), 0, i + 1);
            Placed placed = new Placed(path.toString(), reps);
            groups.putIfAbsent(placed.path() + java.util.Arrays.toString(reps), placed);
        }
    }

    private record Placed(String path, int[] reps) {
    }

    /** Parse {@code /Order/Items[2]/Count} into the bare path plus one 1-based index per part. */
    private static Placed parse(String indexed) {
        String[] parts = indexed.substring(1).split("/");
        StringBuilder path = new StringBuilder();
        int[] reps = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            int bracket = part.indexOf('[');
            if (bracket >= 0) {
                reps[i] = Integer.parseInt(part.substring(bracket + 1, part.length() - 1));
                part = part.substring(0, bracket);
            } else {
                reps[i] = 1;
            }
            path.append('/').append(part);
        }
        return new Placed(path.toString(), reps);
    }
}

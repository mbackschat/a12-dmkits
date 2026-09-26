package io.github.mbackschat.a12.dm.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.adapter.laws.RuntimeLaws;
import io.github.mbackschat.a12.dm.bridge.InterpreterConditionReadBack;
import io.github.mbackschat.a12.dm.core.ModelIndex;
import io.github.mbackschat.a12.dm.core.ReferenceResolvers;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.catalog.OperatorCatalog;
import io.github.mbackschat.a12.dm.rulekit.catalog.OperatorEntry;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;
import io.github.mbackschat.a12.dm.rulekit.spi.ConditionReadBack;

/**
 * Read-back parity across the WHOLE operator catalog — the moving-denominator half of
 * {@code ConditionReadBackParityTest}, and the answer DEV-API-GAPS {@code DG44} left open.
 *
 * <p><b>Why the hand-authored corpus was not enough.</b> That corpus states each case in typed DSL, which
 * makes it readable and gives it a third witness (as-authored), but its denominator is a literal list: it
 * covered 19 constructs against a bridge mapping ~84 operator names, and seven names appeared in no test at
 * all. Any list maintained by hand drifts from the surface it is supposed to cover. This class takes its cases
 * from {@link OperatorCatalog} instead, so a new operator entry joins the parity net the moment its
 * {@code validExample} lands — the denominator moves with the catalog rather than with someone remembering.
 *
 * <p><b>What it asserts, and why an Opaque is data rather than a failure.</b> The bridge maps a SUBSET of the
 * grammar and answers {@link ReadResult.Opaque} outside it, deliberately. So the net is two-sided: wherever the
 * bridge converts, its typed AST must render identically to the kernel provider's — the kernel is the oracle,
 * which is the property the module's own unit tests cannot have, since they compare the bridge against
 * hand-authored rulekit types and a self-consistently wrong mapping agrees with them. And the set of ids the
 * bridge canNOT convert is PINNED, so coverage cannot regress silently and a newly converting operator has to
 * be acknowledged.
 *
 * <p>The rule shape reuses N7's ({@code CatalogExamplesAreKernelValidTest}): each example is spliced into the
 * shared fixture as one rule whose error field is the first fixture field the example references, because the
 * kernel requires the error field to be referenced. The field list is derived from the model through
 * {@link ModelIndex} rather than restated, so the fixture and the heuristic cannot drift apart.
 */
class CatalogReadBackParityTest {

    private static final OperatorCatalog CATALOG = OperatorCatalog.load();
    private static final DslRenderer RENDER = new DslRenderer(ConditionLanguage.EN);

    /** The N7 fixtures: the shared order model, and the two-star warehouse the multi-star examples need. */
    private static final String ORDER_JSON = TestResources.text("models/order.dm.json");
    private static final String WAREHOUSE_JSON = DocumentModelDraft.named("warehouse").locale("en_US")
            .rootGroup("Warehouse", warehouse -> warehouse
                    .group("Aisles", aisles -> aisles.repeatable(20)
                            .stringField("Code", field -> { })
                            .group("Bins", bins -> bins.repeatable(20)
                                    .numberField("Quantity", field -> { }))))
            .build().toJson();

    /** Declared before the placement fields below, which read it during static initialization. */
    private static final ModelIndex ORDER_INDEX = ModelIndex.ofSelfContained(ORDER_JSON);

    /** One catalog example, placed: its rule group, its error field relative to that group, its condition. */
    private record Example(String id, String ruleName, String group, String errorField, String dsl) { }

    /**
     * Catalog examples this placement could not reach, because they name no field of either fixture. Pinned
     * rather than silently skipped: an example that drops out of the denominator is coverage lost with nothing
     * to show for it, which is the failure mode a floor assertion cannot see.
     */
    private static final Set<String> UNPLACED = new TreeSet<>();

    // Built ONCE: :adapter:test carries timing-sensitive growth floors, so this class costs four model
    // parses in total (two fixtures × two providers) rather than one per catalog entry.
    private static final List<Example> ORDER_EXAMPLES = placeExamples(false);
    private static final List<Example> WAREHOUSE_EXAMPLES = placeExamples(true);
    private static final Map<String, ReadResult> KERNEL_READS = readAll(new KernelConditionReadBack());
    private static final Map<String, ReadResult> BRIDGE_READS = readAll(new InterpreterConditionReadBack());

    /**
     * <b>The kernel is the oracle.</b> Every example the bridge converts must produce the same typed AST the
     * kernel's parse produces, compared as an identical rendering. This is the assertion the module's own
     * tests structurally cannot make.
     */
    @Test
    void everyExampleTheBridgeConvertsRendersIdenticallyToTheKernelsRead() {
        Map<String, String> mismatches = new LinkedHashMap<>();
        for (Example e : allExamples()) {
            if (!(BRIDGE_READS.get(e.ruleName()) instanceof ReadResult.Converted bridge)) {
                continue;
            }
            ReadResult kernelRead = KERNEL_READS.get(e.ruleName());
            String kernel = kernelRead instanceof ReadResult.Converted converted
                    ? RENDER.render(converted.condition())
                    : "NOT-CONVERTED: " + kernelRead;
            String read = RENDER.render(bridge.condition());
            if (!kernel.equals(read)) {
                mismatches.put(e.id(), "authored=" + e.dsl() + "  kernel=" + kernel + "  bridge=" + read);
            }
        }
        assertEquals(Map.of(), mismatches,
                "the bridge's typed AST must equal the kernel's wherever it claims to convert");
    }

    /**
     * The pinned coverage frontier. An id leaving this set means the bridge learned an operator — welcome, and
     * it must be removed here deliberately. An id ENTERING it is a coverage regression, which is the direction
     * that would otherwise pass as "the bridge is still tolerant".
     */
    @Test
    void theSetOfCatalogExamplesTheBridgeCannotConvertIsPinned() {
        assertEquals(OPAQUE_TO_THE_BRIDGE, opaqueIds(BRIDGE_READS),
                "the bridge's read-back coverage of the catalog moved — pin the new frontier deliberately");
    }

    /**
     * The control that keeps the two assertions above from passing vacuously. If the kernel provider stopped
     * converting, or the fixture stopped carrying the rules, both would go quiet: the parity loop skips what
     * the bridge does not convert, and the pinned set would simply grow to everything.
     */
    @Test
    void theKernelProviderConvertsTheWholeCatalogAndBothProvidersSeeEveryRule() {
        assertEquals(Set.copyOf(allExamples().stream().map(Example::ruleName).toList()),
                Set.copyOf(KERNEL_READS.keySet()),
                "the kernel provider must enumerate exactly the spliced catalog rules");
        assertEquals(KERNEL_READS.keySet(), BRIDGE_READS.keySet(),
                "both providers must enumerate the same rules from the same model");
        assertEquals(Set.of(), opaqueIds(KERNEL_READS),
                "the kernel provider is the oracle here and must convert every catalog example");
        // The EXACT denominator, not a floor: a floor cannot see examples silently dropping out of the net,
        // which is precisely how a catalog-driven corpus would rot back into a hand-maintained one.
        assertEquals(CATALOG.entries().size(), allExamples().size() + UNPLACED.size(),
                "every catalog entry must be either placed or explicitly unplaced");
        assertEquals(EXPECTED_UNPLACED, UNPLACED,
                "an example stopped naming a fixture field — give the fixture that field rather than lose it");
    }

    // ---- placement ---------------------------------------------------------------------------------

    private static List<Example> allExamples() {
        List<Example> all = new ArrayList<>(ORDER_EXAMPLES);
        all.addAll(WAREHOUSE_EXAMPLES);
        return all;
    }

    /** Every catalog entry with a valid example, placed in whichever fixture its paths name. */
    private static List<Example> placeExamples(boolean warehouse) {
        List<Example> placed = new ArrayList<>();
        for (OperatorEntry entry : CATALOG.entries()) {
            if (entry.validExample() == null) {
                continue;
            }
            String dsl = entry.validExample().dsl();
            if (dsl.contains("/Warehouse/") != warehouse) {
                continue;
            }
            String errorField = warehouse
                    ? (dsl.contains("Aisles/Code") ? "Aisles/Code" : "Aisles/Bins/Quantity")
                    : firstReferencedField(dsl);
            if (errorField == null) {
                UNPLACED.add(entry.id());
                continue;
            }
            placed.add(new Example(entry.id(), ruleName(entry.id()),
                    warehouse ? "/Warehouse" : "/Order", errorField, dsl));
        }
        return List.copyOf(placed);
    }

    /**
     * The first fixture field the example references, DERIVED from the model rather than restated — a
     * hand-kept field list is the kind of second copy that drifts from the fixture it describes. Longest path
     * first, so {@code Items/Sku} wins over a bare {@code Sku} that happens to be a suffix of it.
     *
     * <p>An example that names only a GROUP falls back to a field inside it, which is legal because the kernel
     * counts an enclosing group reference as referencing the error field. That fallback is not decoration:
     * thirteen examples reference a group alone, and four of them are operators DEV-API-GAPS {@code DG44}
     * named as covered by no test at all — so skipping them would have lost exactly the coverage this class
     * exists to add.
     */
    private static String firstReferencedField(String dsl) {
        return relativePaths(ORDER_INDEX.fieldPaths())
                .filter(rel -> references(dsl, rel))
                .findFirst()
                .or(() -> relativePaths(ORDER_INDEX.groupPaths())
                        .filter(rel -> references(dsl, rel))
                        .flatMap(rel -> relativePaths(ORDER_INDEX.fieldPaths("/Order/" + rel)))
                        .findFirst())
                .orElse(null);
    }

    /** Model paths as fixture-relative names, longest first so a longer path wins over its own suffix. */
    private static Stream<String> relativePaths(Stream<String> paths) {
        return paths.filter(path -> path.startsWith("/Order/"))
                .map(path -> path.substring("/Order/".length()))
                .sorted((a, b) -> b.length() - a.length());
    }

    private static boolean references(String dsl, String relativePath) {
        return Pattern.compile("\\b" + Pattern.quote(relativePath) + "\\b").matcher(dsl).find();
    }

    private static String ruleName(String catalogId) {
        return "Cat" + catalogId.replaceAll("[^A-Za-z0-9]", "");
    }

    // ---- reading -----------------------------------------------------------------------------------

    private static Map<String, ReadResult> readAll(ConditionReadBack provider) {
        Map<String, ReadResult> byRuleName = new LinkedHashMap<>();
        collect(provider, spliced(ORDER_JSON, ORDER_EXAMPLES), byRuleName);
        collect(provider, spliced(WAREHOUSE_JSON, WAREHOUSE_EXAMPLES), byRuleName);
        return byRuleName;
    }

    private static void collect(ConditionReadBack provider, String json, Map<String, ReadResult> into) {
        provider.readAll(json, ReferenceResolvers.selfContained())
                .forEach((path, result) -> into.put(path.substring(path.lastIndexOf('/') + 1), result));
    }

    private static String spliced(String baseJson, List<Example> examples) {
        String json = baseJson;
        for (Example e : examples) {
            json = RuntimeLaws.withRule(json, e.group(), e.ruleName(), "../" + e.errorField(), "CAT", e.dsl());
        }
        return json;
    }

    /** The catalog ids whose spliced rule did not convert, as a stable sorted set. */
    private static Set<String> opaqueIds(Map<String, ReadResult> reads) {
        Set<String> opaque = new TreeSet<>();
        for (Example e : allExamples()) {
            if (!(reads.get(e.ruleName()) instanceof ReadResult.Converted)) {
                opaque.add(e.id());
            }
        }
        return opaque;
    }

    /** Measured, then pinned — see {@link #theSetOfCatalogExamplesTheBridgeCannotConvertIsPinned}. */
    private static final Set<String> OPAQUE_TO_THE_BRIDGE = new TreeSet<>(List.of());

    /** Measured, then pinned — see {@link #UNPLACED}. */
    private static final Set<String> EXPECTED_UNPLACED = new TreeSet<>(List.of());
}

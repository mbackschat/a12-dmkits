package io.github.mbackschat.a12.dm.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.ModelIndex;
import io.github.mbackschat.a12.dm.core.ModelResolver;
import io.github.mbackschat.a12.dm.core.ReferenceResolvers;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.modelkit.MountOptions;

/**
 * The Stage-2 <b>parity net</b> — the differential-vs-the-kernel proof that the kernel-free {@code dm.core}
 * reader's index equals the kernel's over the SAME model. For every model, it builds the kernel-backed
 * {@link KernelModelOracle} and the kernel-free {@link ModelIndex}, then asserts they carry the <b>same
 * name-path set</b> and the <b>same {@link ElementFacts}</b> at every path (kind, scale, bounds, lengths,
 * pattern, enum domain/categories, repeatability, annotations). This is where "differential vs the kernel's
 * expand" is met — include mounting, imported-typedef effective types and id≠path all live in the real corpora.
 *
 * <p>Coverage: authored own-domain models (all kinds + bounds + a typedef + a SINGLE_RG and a MODEL_ROOT
 * include), the committed self-contained fixtures, and — opt-in — the {@code a12-kernel-mm} metamodels
 * (sibling-clone) and the workspaces corpus ({@code -Da12.corpus1.dir}). A model the kernel itself rejects is
 * skipped (not a parity concern); a non-empty floor guards against a silently-empty corpus reading green.
 */
class ModelIndexParityTest {

    // ── authored own-domain models (always run) ───────────────────────────────────────────────────────────

    @Test
    void authoredModelsMatchTheKernelAcrossKindsBoundsTypedefsAndIncludes() {
        assertParity(allKinds(), ReferenceResolvers.selfContained());
        assertParity(withBoundsAndEnum(), ReferenceResolvers.selfContained());
        assertParity(withTypedef(), ReferenceResolvers.selfContained());

        // SINGLE_RG include — the included root-group name vanishes, children mount under the alias group
        assertParity(policyIncludingBenefits(), benefitsResolver());
        // MODEL_ROOT include — the included root group is preserved as a subgroup under the mount
        assertParity(policyModelRootMount(), riderResolver());
    }

    // ── the committed self-contained fixtures (always run) ────────────────────────────────────────────────

    @Test
    void committedFixturesMatchTheKernel() {
        for (String fixture : List.of("models/order.dm.json", "models/subscription.dm.json",
                "models/person.dm.json", "models/project.dm.json", "models/appointment.dm.json",
                "models/membership.dm.json", "models/staffing.dm.json")) {
            assertParity(classpath(fixture), ReferenceResolvers.selfContained());
        }
    }

    // ── the opt-in corpora (the differential vs the kernel's expand lives here) ────────────────────────────

    @Test
    void kernelMmCorpusMatchesTheKernel() {
        Path root = locateKernelMm();
        assumeTrue(root != null, "a12-kernel-mm not present — skipping the kernel-mm parity sweep");
        int compared = sweepCorpusParity(root);
        assertTrue(compared >= 10, () -> "expected the canonical metamodel set, compared only " + compared);
    }

    @Test
    void workspacesCorpusMatchesTheKernel() {
        String dir = System.getProperty("a12.corpus1.dir");
        assumeTrue(dir != null && Files.isDirectory(Path.of(dir)),
                "a12.corpus1.dir not set / absent — skipping the workspaces parity sweep");
        int compared = sweepCorpusParity(Path.of(dir));
        assertTrue(compared > 0, () -> "workspaces parity compared 0 models — the sweep is broken, not merely empty");
    }

    // ── the comparator ────────────────────────────────────────────────────────────────────────────────────

    /** Assert the kernel-free index and the kernel oracle carry identical name-paths and facts for one model. */
    private static void assertParity(String dmJson, ModelResolver resolver) {
        KernelModelOracle kernel = KernelModelOracle.of(dmJson, resolver);
        ModelIndex ours = ModelIndex.of(dmJson, resolver);

        assertEquals(new TreeSet<>(kernel.paths()), new TreeSet<>(ourPaths(ours)),
                "the kernel-free index and the kernel must key the same name-paths");
        for (String path : kernel.paths()) {
            assertEquals(kernel.factsAt(path), ourFactsAt(ours, path),
                    () -> "facts diverge from the kernel at " + path);
        }
    }

    /** Sweep every document model under a corpus root; compare the ones the kernel accepts; return the count. */
    private static int sweepCorpusParity(Path root) {
        ModelResolver resolver = ReferenceResolvers.directory(root);
        int compared = 0;
        List<String> divergences = new ArrayList<>();
        for (Path file : documentModels(root)) {
            String json;
            try {
                json = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                continue;
            }
            KernelModelOracle kernel;
            try {
                kernel = KernelModelOracle.of(json, resolver);   // kernel load + expand + checkConsistency
            } catch (RuntimeException kernelRejected) {
                continue;   // a model the kernel itself won't load is not a parity concern
            }
            ModelIndex ours = ModelIndex.of(json, resolver);
            try {
                assertEquals(new TreeSet<>(kernel.paths()), new TreeSet<>(ourPaths(ours)));
                for (String path : kernel.paths()) {
                    assertEquals(kernel.factsAt(path), ourFactsAt(ours, path), () -> "at " + path);
                }
                compared++;
            } catch (AssertionError diverged) {
                divergences.add(file.getFileName() + ": " + diverged.getMessage());
            }
        }
        assertTrue(divergences.isEmpty(), () -> "index parity diverged from the kernel on:\n"
                + String.join("\n", divergences.subList(0, Math.min(20, divergences.size()))));
        return compared;
    }

    private static List<String> ourPaths(ModelIndex index) {
        List<String> paths = new ArrayList<>();
        index.fieldPaths().forEach(paths::add);
        index.groupPaths().forEach(paths::add);
        return paths;
    }

    /** The kernel-free read surface at {@code path} — the mirror of {@link KernelModelOracle#factsAt}. */
    private static ElementFacts ourFactsAt(ModelIndex index, String path) {
        if (index.isGroup(path)) {
            return new ElementFacts(true, null, null, null, null, null, null, null, null, null,
                    index.maxRepetitions(path), annotations(index.annotationsOfGroup(path)));
        }
        var kind = index.kindOf(path);
        Integer scale = kind == io.github.mbackschat.a12.dm.core.ValueKind.NUMBER ? index.numberScale(path) : null;
        String min = index.numberMin(path).map(BigDecimal::toPlainString).orElse(null);
        String max = index.numberMax(path).map(BigDecimal::toPlainString).orElse(null);
        Integer minLength = index.stringMinLength(path).isPresent()
                ? index.stringMinLength(path).getAsInt() : null;
        Integer maxLength = index.stringMaxLength(path).isPresent()
                ? index.stringMaxLength(path).getAsInt() : null;
        String pattern = index.stringPattern(path).orElse(null);
        List<String> enumValues = null;
        Map<String, List<String>> enumCategories = null;
        if (kind == io.github.mbackschat.a12.dm.core.ValueKind.ENUMERATION) {
            enumValues = index.enumValues(path);
            enumCategories = index.enumCategories(path);
        }
        return new ElementFacts(false, kind.name(), scale, min, max, minLength, maxLength, pattern,
                enumValues, enumCategories, null, annotations(index.annotationsOf(path)));
    }

    private static List<String> annotations(List<io.github.mbackschat.a12.dm.core.Annotation> annotations) {
        return annotations.stream().map(a -> a.name() + "=" + a.value()).toList();
    }

    // ── authored fixtures + corpus location ───────────────────────────────────────────────────────────────

    private static String allKinds() {
        return DocumentModelDraft.named("catalog").locale("en_US")
                .rootGroup("Catalog", c -> c
                        .field("Sku", FieldKind.STRING)
                        .field("Listed", FieldKind.DATE)
                        .field("Curated", FieldKind.CONFIRM)
                        .field("Active", FieldKind.BOOLEAN)
                        .numberField("ListPrice", n -> n.fractionDigits(2))
                        .group("Variants", v -> v.repeatable(20)
                                .field("Color", FieldKind.STRING)
                                .numberField("Weight", n -> n.fractionDigits(3))))
                .build().toJson();
    }

    private static String withBoundsAndEnum() {
        return DocumentModelDraft.named("policy").locale("en_US")
                .rootGroup("Policy", p -> p
                        .numberField("Premium", n -> n.fractionDigits(2).min("0.00").max("100000.00"))
                        .stringField("Ref", s -> s.minLength(3).maxLength(12).pattern("[A-Z]{3}.*", "three letters first"))
                        .enumField("Tier", e -> e.value("BRONZE").value("SILVER").value("GOLD")))
                .build().toJson();
    }

    private static String withTypedef() {
        return DocumentModelDraft.named("shop").locale("en_US")
                .typeDef("Sku", t -> t.string(s -> s.maxLength(12)))
                .typeDef("Money", t -> t.number(n -> n.fractionDigits(2)))
                .rootGroup("Shop", s -> s
                        .typedefField("ProductSku", "Sku")
                        .typedefField("Price", "Money")
                        .field("Name", FieldKind.STRING))
                .build().toJson();
    }

    private static String policyIncludingBenefits() {
        return DocumentModelDraft.named("policy").locale("en_US")
                .include("benefits", "benefits")
                .rootGroup("Policy", p -> p
                        .field("PolicyNo", FieldKind.STRING)
                        .mount("Benefits", "benefits"))
                .build().toJson();
    }

    private static String benefitsModel() {
        return DocumentModelDraft.named("benefits").locale("en_US")
                .rootGroup("Benefits", b -> b
                        .numberField("Coverage", n -> n.fractionDigits(2))
                        .field("Kind", FieldKind.STRING))
                .build().toJson();
    }

    private static ModelResolver benefitsResolver() {
        String benefits = benefitsModel();
        return ref -> "benefits".equals(ref) ? benefits : null;
    }

    private static String policyModelRootMount() {
        return DocumentModelDraft.named("policy").locale("en_US")
                .include("rider", "rider")
                .rootGroup("Policy", p -> p
                        .field("PolicyNo", FieldKind.STRING)
                        .mount("Extras", "rider", MountOptions.single().asModelRoot()))
                .build().toJson();
    }

    private static ModelResolver riderResolver() {
        String rider = DocumentModelDraft.named("rider").locale("en_US")
                .rootGroup("Rider", r -> r.numberField("Amount", n -> n.fractionDigits(2)))
                .build().toJson();
        return ref -> "rider".equals(ref) ? rider : null;
    }

    private static String classpath(String resource) {
        try (InputStream in = ModelIndexParityTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, "fixture not found on classpath: " + resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The 3-candidate sibling-clone locate for a12-kernel-mm (mirrors CorpusShrinkSupport). */
    private static Path locateKernelMm() {
        List<String> candidates = new ArrayList<>();
        String prop = System.getProperty("a12.kernel.mm.dir");
        if (prop != null) {
            candidates.add(prop);
        }
        candidates.add("../a12-kernel-mm/documentModels");
        candidates.add("../../a12-kernel-mm/documentModels");
        for (String c : candidates) {
            Path p = Path.of(c);
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        return null;
    }

    /** Every {@code header.modelType == "document"} JSON file under {@code root}. */
    private static List<Path> documentModels(Path root) {
        List<Path> models = new ArrayList<>();
        try (var stream = Files.walk(root)) {
            stream.filter(p -> p.toString().endsWith(".json")).forEach(p -> {
                try {
                    String head = Files.readString(p, StandardCharsets.UTF_8);
                    if (head.contains("\"modelType\"") && head.contains("\"document\"")
                            && head.contains("\"modelRoot\"")) {
                        models.add(p);
                    }
                } catch (IOException | RuntimeException ignored) {
                    // not a readable model — skip
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return models;
    }
}

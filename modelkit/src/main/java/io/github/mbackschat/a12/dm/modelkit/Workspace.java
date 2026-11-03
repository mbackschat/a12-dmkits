package io.github.mbackschat.a12.dm.modelkit;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A kernel-free read over a <b>directory of document models</b> — the engine behind {@code dmtool workspace
 * list}. It walks the tree, reads each {@code .json} model's header textually (no kernel, no reference
 * resolution — a half-wired workspace still lists), and builds a per-model index: identity + the
 * super/subtype convention ({@code abstract}/{@code superTypes}/{@code subTypes} header annotations,
 * KERNEL-INTEGRATION-NOTES) + every model reference ({@code include} / type-def import)
 * <b>cross-resolved to its file within the scanned set</b>. That cross-reference is the point: an agent
 * dropped into a folder learns what each model provides and which file an include or imported type-def
 * lives in, instead of guessing an {@code -w/--workspace}.
 *
 * <p>The scan is the read peer of {@link DocumentModelDraft} (author) / {@link ModelDocument} (single-model
 * read), one level up — the multi-model surface. It depends only on {@link ModelDocument} (hence Jackson),
 * never the kernel; the optional {@code --validate} pass that confirms which models are kernel-valid lives
 * in the CLI, not here.
 */
public final class Workspace {

    private Workspace() {
    }

    /**
     * A model this one references — an {@code include} or a type-def import, by the referenced model's id — and
     * where it resolves <em>within the scanned set</em>:
     * {@code resolvedPath} is the referenced model's relative path, or {@code null} when no scanned model
     * has that id (the reference dangles, or its target is out of the scan's scope).
     */
    public record Reference(String ref, String resolvedPath) {
    }

    /**
     * A subtype→supertype edge of the model-inheritance graph ({@code workspace graph}). {@code resolved} is
     * true iff <em>both</em> ids name a scanned model — a false edge points at (or from) a model outside the
     * scan (dangling, or out of scope; widen with {@code --recursive}).
     */
    public record Edge(String subtype, String supertype, boolean resolved) {
    }

    /** One model's index entry: identity, the super/subtype convention, references (cross-resolved), and counts. */
    public record Model(
            String id,
            String path,
            String modelType,
            String label,
            boolean isAbstract,
            List<String> superTypes,
            List<String> subTypes,
            // the access-control gating roles — the (comma-split) `roles` header annotation R3's lint resolves
            // against the workspace roles.yaml; empty when the model carries no `roles` annotation.
            List<String> roles,
            List<String> locales,
            List<Reference> includes,
            List<Reference> typedefImports,
            List<String> providesTypedefs,
            int groups,
            int fields) {
    }

    /**
     * Scan {@code root} for document models and index them, deterministically ordered by relative path.
     * {@code recursive} descends sub-directories (off = the immediate directory only, like {@code ls}); a
     * recursive scan widens the resolution scope, so a reference into a sub-folder resolves there.
     *
     * @throws UncheckedIOException if {@code root} can't be opened (a missing/!directory root is the caller's
     *         gate to reject first); individual unreadable files/sub-dirs are skipped, not fatal.
     */
    public static List<Model> scan(Path root, boolean recursive) {
        List<Path> files = new ArrayList<>(modelFiles(root, recursive));
        files.sort(Comparator.comparing(p -> relativePath(root, p)));

        List<ModelDocument> docs = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        Map<String, String> idToPath = new LinkedHashMap<>();
        for (Path p : files) {
            ModelDocument doc;
            try {
                doc = ModelDocument.read(Files.readString(p));
            } catch (IOException | RuntimeException notAModel) {
                continue; // unreadable, or not DM-JSON — simply not an indexable model
            }
            if (doc.id().isEmpty() || doc.modelType().isEmpty()) {
                continue; // a JSON object without a model header (e.g. a document instance) is not a model
            }
            String rel = relativePath(root, p);
            docs.add(doc);
            paths.add(rel);
            idToPath.putIfAbsent(doc.id(), rel); // first wins on a duplicate id (paths are pre-sorted)
        }

        List<Model> models = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) {
            models.add(toModel(docs.get(i), paths.get(i), idToPath));
        }
        return List.copyOf(models);
    }

    /**
     * Index a single already-read model against a resolution map (id → relative path) — the per-model core
     * of {@link #scan}, exposed so a single-model caller (the CLI {@code model info}) gets the same
     * references (the same {@link Reference} shape, cross-resolved) and super/subtype facts without
     * re-implementing the convention. {@code path} appears as {@link Model#path()} (cosmetic for a
     * single-model caller); {@code idToPath} is typically built from {@link #scan} over the include dir.
     */
    public static Model index(ModelDocument doc, String path, Map<String, String> idToPath) {
        return toModel(doc, path, idToPath);
    }

    /**
     * The model-inheritance edge set ({@code workspace graph}): every subtype→supertype relation declared by
     * the super/subtype convention, <b>unioned across both directions</b> (a model's {@code superTypes} and a
     * base's {@code subTypes}) and deduped, deterministically ordered by (subtype, supertype). Each edge is
     * {@code resolved} iff both endpoints are among {@code models}. Pure derivation over a scanned list — the
     * nodes are the {@code models} themselves.
     */
    public static List<Edge> inheritanceEdges(List<Model> models) {
        Set<String> ids = new HashSet<>();
        models.forEach(m -> ids.add(m.id()));
        // a (subtype, supertype) pair, deduped across both declaration directions (superTypes + subTypes)
        Set<List<String>> pairs = new LinkedHashSet<>();
        for (Model m : models) {
            for (String supertype : m.superTypes()) {
                pairs.add(List.of(m.id(), supertype));
            }
            for (String subtype : m.subTypes()) {
                pairs.add(List.of(subtype, m.id()));
            }
        }
        List<Edge> edges = new ArrayList<>();
        for (List<String> pair : pairs) {
            edges.add(new Edge(pair.get(0), pair.get(1), ids.contains(pair.get(0)) && ids.contains(pair.get(1))));
        }
        edges.sort(Comparator.comparing(Edge::subtype).thenComparing(Edge::supertype));
        return List.copyOf(edges);
    }

    private static Model toModel(ModelDocument doc, String path, Map<String, String> idToPath) {
        Map<String, String> annotations = doc.modelAnnotations();
        Map<String, String> labels = doc.modelLabels();

        List<Reference> includes = new ArrayList<>();
        for (ModelDocument.Include inc : doc.includes()) {
            includes.add(new Reference(inc.reference(), idToPath.get(inc.reference())));
        }
        List<Reference> imports = new ArrayList<>();
        for (String ref : doc.typeDefImports()) {
            imports.add(new Reference(ref, idToPath.get(ref)));
        }

        return new Model(
                doc.id(),
                path,
                doc.modelType(),
                labels.isEmpty() ? null : labels.values().iterator().next(),
                "true".equalsIgnoreCase(annotations.get("abstract")),
                splitCsv(annotations.get("superTypes")),
                splitCsv(annotations.get("subTypes")),
                splitCsv(annotations.get("roles")),
                doc.locales(),
                List.copyOf(includes),
                List.copyOf(imports),
                doc.typeDefinitionIds(),
                doc.groupPaths().size(),
                doc.fieldPaths().size());
    }

    /** Split a comma-separated annotation value (the super/subtype convention's list shape) into trimmed ids. */
    private static List<String> splitCsv(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return List.copyOf(out);
    }

    /** The {@code *.json} files under {@code root} (recursive or the immediate directory only), tolerating IO faults. */
    private static List<Path> modelFiles(Path root, boolean recursive) {
        List<Path> files = new ArrayList<>();
        try {
            if (recursive) {
                Files.walkFileTree(root, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path p, BasicFileAttributes attrs) {
                        if (isJson(p)) {
                            files.add(p);
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path p, IOException exc) {
                        return FileVisitResult.CONTINUE; // skip an unreadable file/dir, don't abort the scan
                    }
                });
            } else {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(root, "*.json")) {
                    for (Path p : stream) {
                        if (Files.isRegularFile(p)) {
                            files.add(p);
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to scan workspace " + root, e);
        }
        return files;
    }

    private static boolean isJson(Path p) {
        return p.getFileName().toString().endsWith(".json");
    }

    /** {@code root}-relative path, normalized to forward slashes so the index reads the same on every OS. */
    private static String relativePath(Path root, Path file) {
        return root.relativize(file).toString().replace(File.separatorChar, '/');
    }
}

package io.github.mbackschat.a12.dm.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * Factory methods for {@link ModelResolver} ({@code id → DM-JSON}, our kernel-free resolver type). Every
 * method here is kernel-free (Jackson only); the kernel-side bridge that adapts a {@code ModelResolver} to
 * the kernel's own resolver moved into the backend at kernel-decoupling stage 3, so this class — and all of
 * {@code dm.core} — imports no {@code com.mgmtp.a12.kernel.*} type.
 */
public final class ReferenceResolvers {

    private ReferenceResolvers() {
    }

    /**
     * A resolver for <em>self-contained</em> models — those with no {@code include}d models or
     * imported type-definitions. If expansion ever asks it to resolve a reference, it fails fast
     * with an actionable message rather than silently indexing a half-resolved model.
     */
    public static ModelResolver selfContained() {
        return reference -> {
            throw new IllegalStateException(
                    "model references a separate model (\"" + reference + "\") but was treated as "
                            + "self-contained; supply a resolver that can resolve it");
        };
    }

    /**
     * A resolver that loads referenced models from one or more directory trees (F-RES) — what a
     * multi-file application needs: its rules {@code include} other document models and import
     * type-definitions ({@code _TDM}) by reference. The kernel's {@code expand} and
     * {@code extractImportedTypeDefinitions} both resolve the target's <em>model id</em> (through the
     * backend's resolver bridge), so this indexes every <b>document model</b> in the trees by
     * {@code header.id} and serves its DM-JSON on demand.
     *
     * <p><b>Document-model detection is by {@code header.modelType == "document"}</b>, not a filename
     * convention — the trees also hold {@code form}/{@code overview}/{@code tree}/… models that are not
     * resolvable through this (document-only) interface. A {@code _TDM} type-definition model is itself
     * {@code modelType: "document"}, so the one id-keyed index covers both {@code include} and
     * {@code typeDefinitions} references. Handing back the authored text (not a shared parsed model) keeps
     * each expansion's deserialize independent (expansion mutates in place, so a shared instance would
     * corrupt repeated includes). An unknown id fails fast with an actionable message.
     *
     * @throws UncheckedIOException if a tree can't be walked
     */
    public static ModelResolver directory(Path... roots) {
        Map<String, String> modelsById = indexDocumentModels(roots);
        return reference -> {
            String json = modelsById.get(reference);
            if (json == null) {
                throw new UnresolvedReferenceException(reference, "no document model with id \"" + reference + "\" found in "
                        + modelsById.size() + " indexed models under " + java.util.Arrays.toString(roots));
            }
            return json;
        };
    }

    /**
     * The <b>raw DM-JSON</b> of the document model with {@code header.id == id} under {@code roots}, indexed
     * the same way {@link #directory} resolves references. Unlike {@code directory} (which deserializes to an
     * {@code IDocumentModel}), this hands back the authored text — what {@code include inline} needs to fold a
     * sub-model's content back into the base. Empty if no document model with that id is found.
     */
    public static Optional<String> documentModelJson(String id, Path... roots) {
        return Optional.ofNullable(indexDocumentModels(roots).get(id));
    }

    /**
     * Walk the trees, parse each {@code *.json} header, and index {@code modelType=="document"} files by id.
     * Uses {@code walkFileTree} (not {@code Files.walk}) so an <b>unreadable subdirectory</b> is skipped, not
     * fatal: when the resolver is pointed at a broad tree (e.g. the include-dir defaults to the model file's
     * parent, which can be a shared {@code /tmp} on a CI runner), traversal must tolerate sibling dirs it
     * can't enter — {@code /tmp/systemd-private-*} is root-only and would otherwise throw
     * {@code AccessDeniedException} and abort the whole index.
     */
    private static Map<String, String> indexDocumentModels(Path... roots) {
        // A candidate file is a DM-JSON model, so it is parsed the way A12 parses one: a commented model must be
        // INDEXED, not skipped. It was skipped before, and because indexIfDocumentModel swallows the parse error the
        // symptom was a later RK_UNRESOLVED_REFERENCE naming an id whose file is sitting on disk (IF228).
        ObjectMapper mapper = JsonMapper.builder()
                .enable(JsonParser.Feature.ALLOW_COMMENTS)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
                .build();
        Map<String, String> byId = new HashMap<>();
        for (Path root : roots) {
            try {
                Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path p, java.nio.file.attribute.BasicFileAttributes attrs) {
                        if (p.toString().endsWith(".json")) {
                            indexIfDocumentModel(mapper, byId, p);
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path p, IOException exc) {
                        return FileVisitResult.CONTINUE;   // skip an unreadable file/dir, don't abort
                    }
                });
            } catch (IOException e) {
                throw new UncheckedIOException("failed to walk reference directory " + root, e);
            }
        }
        return byId;
    }

    private static void indexIfDocumentModel(ObjectMapper mapper, Map<String, String> byId, Path p) {
        try {
            String json = Files.readString(p, StandardCharsets.UTF_8);
            JsonNode header = mapper.readTree(json).path("header");
            if ("document".equals(header.path("modelType").asText())) {
                String id = header.path("id").asText();
                if (!id.isEmpty()) {
                    byId.putIfAbsent(id, json);   // first wins on a duplicate id
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // a file that isn't valid DM-JSON is simply not an indexable model
        }
    }
}

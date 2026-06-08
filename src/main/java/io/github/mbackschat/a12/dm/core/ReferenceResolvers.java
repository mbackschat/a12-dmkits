package io.github.mbackschat.a12.dm.core;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mgmtp.a12.kernel.md.facade.DocumentModelServiceFactory;
import com.mgmtp.a12.kernel.md.model.api.IDocumentModel;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelReferenceResolver;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelSerializer;

/** Factory methods for the kernel's {@link IDocumentModelReferenceResolver}. */
public final class ReferenceResolvers {

    private ReferenceResolvers() {
    }

    /**
     * A resolver for <em>self-contained</em> models — those with no {@code include}d models or
     * imported type-definitions. If expansion ever asks it to resolve a reference, it fails fast
     * with an actionable message rather than silently indexing a half-resolved model.
     */
    public static IDocumentModelReferenceResolver selfContained() {
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
     * {@code extractImportedTypeDefinitions} both call {@link IDocumentModelReferenceResolver#getDocumentModel}
     * with the target's <em>model id</em>, so this indexes every <b>document model</b> in the trees by
     * {@code header.id} and serves it on demand.
     *
     * <p><b>Document-model detection is by {@code header.modelType == "document"}</b>, not a filename
     * convention — the trees also hold {@code form}/{@code overview}/{@code tree}/… models that are not
     * resolvable through this (document-only) interface. A {@code _TDM} type-definition model is itself
     * {@code modelType: "document"}, so the one id-keyed index covers both {@code include} and
     * {@code typeDefinitions} references. Each {@code getDocumentModel} deserializes a <em>fresh</em>
     * model instance (expansion mutates in place, so a shared instance would corrupt repeated includes).
     *
     * @throws UncheckedIOException if a tree can't be walked
     */
    public static IDocumentModelReferenceResolver directory(Path... roots) {
        Map<String, String> modelsById = indexDocumentModels(roots);
        IDocumentModelSerializer serializer = new DocumentModelServiceFactory().createDocumentModelSerializer();
        return reference -> {
            String json = modelsById.get(reference);
            if (json == null) {
                throw new IllegalStateException("no document model with id \"" + reference + "\" found in "
                        + modelsById.size() + " indexed models under " + java.util.Arrays.toString(roots));
            }
            try {
                return serializer.deserialize(new StringReader(json));
            } catch (IOException e) {
                throw new UncheckedIOException("failed to deserialize referenced model \"" + reference + "\"", e);
            }
        };
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
        ObjectMapper mapper = new ObjectMapper();
        Map<String, String> byId = new HashMap<>();
        for (Path root : roots) {
            try {
                Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<Path>() {
                    @Override
                    public java.nio.file.FileVisitResult visitFile(Path p, java.nio.file.attribute.BasicFileAttributes attrs) {
                        if (p.toString().endsWith(".json")) {
                            indexIfDocumentModel(mapper, byId, p);
                        }
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }

                    @Override
                    public java.nio.file.FileVisitResult visitFileFailed(Path p, IOException exc) {
                        return java.nio.file.FileVisitResult.CONTINUE;   // skip an unreadable file/dir, don't abort
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

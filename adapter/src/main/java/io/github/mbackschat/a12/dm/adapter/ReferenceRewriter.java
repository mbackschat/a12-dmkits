package io.github.mbackschat.a12.dm.adapter;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.util.Map;

import com.mgmtp.a12.kernel.md.facade.DocumentModelServiceFactory;
import com.mgmtp.a12.kernel.md.model.a12internal.DocumentModel;
import com.mgmtp.a12.kernel.md.model.a12internal.services.MoveSupportDM;
import com.mgmtp.a12.kernel.md.model.api.IDocumentModel;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelSerializer;
import com.mgmtp.a12.kernel.md.serializer.model.internal.service.ExternalDocumentModelSerializerService;

/**
 * The reference-rewriting primitive for a reference-preserving rename/move (FEATURE-GAPS-PROPOSAL §3.1) —
 * an N8-boundary wrapper over the kernel's internal {@code MoveSupportDM}. Given a model as DM-JSON and a
 * map of {@code oldFullPath -> newFullPath}, it rewrites every <b>reference</b> to those paths — rule
 * conditions, error texts, computations (preconditions/operations/common precondition), and the error /
 * computed-field references — and returns the rewritten DM-JSON.
 *
 * <p>It lives in {@code adapter} (not core) because {@code MoveSupportDM} is {@code @OnlyForUsage}
 * kernel-internal API — exactly the internal tier this module isolates behind the N8/D15 boundary. Two
 * kernel-stated limitations the caller must honor: it does <b>not</b> move the element itself (the caller
 * renames/moves the declaration afterward), and it deliberately leaves <b>{@code *} (repetition-list)
 * references untouched</b> — so a rename/move a {@code *}-reference would dangle must be refused.
 */
public final class ReferenceRewriter {

    private static final DocumentModelServiceFactory FACTORY = new DocumentModelServiceFactory();

    private ReferenceRewriter() {
    }

    /** Rewrite all references per {@code entityMoves} (old → new absolute path) and return the new DM-JSON. */
    public static String rewriteReferences(String modelJson, Map<String, String> entityMoves) {
        try {
            IDocumentModelSerializer serializer = FACTORY.createDocumentModelSerializer();
            IDocumentModel model = serializer.deserialize(new StringReader(modelJson));
            // the public model wraps the internal one; MoveSupportDM mutates the internal in place, and
            // serializing the wrapper reflects it (getInternDocumentModel unwraps — shared state).
            DocumentModel intern = ExternalDocumentModelSerializerService.getInternDocumentModel(model);
            MoveSupportDM.changePathReferencesForMove(intern, entityMoves);
            StringWriter writer = new StringWriter();
            serializer.serialize(model, writer, notification -> { });
            return writer.toString();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to rewrite references", e);
        }
    }
}

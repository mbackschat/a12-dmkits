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
 * The reference-rewriting primitive for a reference-preserving rename/move (FEATURE-GAPS) —
 * an N8-boundary wrapper over the kernel's internal {@code MoveSupportDM}. Given a model as DM-JSON and a
 * map of {@code oldFullPath -> newFullPath}, it rewrites every <b>reference</b> to those paths — rule
 * conditions, error texts, computations (preconditions/operations/common precondition), and the error /
 * computed-field references — and returns the rewritten DM-JSON.
 *
 * <p>It lives in {@code adapter} (not core) because {@code MoveSupportDM} is {@code @OnlyForUsage}
 * kernel-internal API — exactly the internal tier this module isolates behind the N8/D15 boundary. One
 * kernel-stated limitation the caller must honor: it does <b>not</b> move the element itself (the caller
 * renames/moves the declaration afterward).
 *
 * <p><b>The {@code *}-reference caveat this javadoc used to carry was MEASURED FALSE (2026-08-14).</b> It said
 * starred repetition-list references are left untouched, "so a rename/move a {@code *}-reference would dangle
 * must be refused" — an advertised refusal that never existed. Renaming a repeatable group under a rule reading
 * {@code Items*}{@code /Qty} in fact carries the starred reference across (`Lines*`{@code /Qty} present, no
 * stale one left), so there is nothing to refuse. Deleted rather than implemented, which is the right
 * resolution of an "advertise nothing you cannot point at" violation when the advertisement is the wrong half.
 * ({@code cli.StarReferenceRenameGateTest}; {@code CG18}.)
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

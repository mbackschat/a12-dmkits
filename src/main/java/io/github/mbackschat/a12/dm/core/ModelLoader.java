package io.github.mbackschat.a12.dm.core;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

import com.mgmtp.a12.kernel.md.facade.DocumentModelServiceFactory;
import com.mgmtp.a12.kernel.md.model.api.IDocumentModel;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelReferenceResolver;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelSerializer;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelService;
import com.mgmtp.a12.model.notification.RankedNotification;
import com.mgmtp.a12.model.notification.Severity;

/**
 * Loads a DocumentModel from DM-JSON into the fully-expanded, consistency-checked form rulekit
 * binds to (proposal §5a / F0). The pipeline is: {@code deserialize → expand(resolver) →
 * checkConsistency}, failing fast on any {@code ERROR}-severity notification.
 *
 * <p>Uses only the public kernel surface ({@code kernel-md-facade}); no internal-tier dependency.
 */
public final class ModelLoader {

    private static final DocumentModelServiceFactory FACTORY = new DocumentModelServiceFactory();

    private ModelLoader() {
    }

    /**
     * Load a <em>self-contained</em> model — one with no {@code include}d models or imported
     * type-definitions. If the model does reference a separate artifact, this fails fast with an
     * actionable message pointing at {@link #load(Reader, IDocumentModelReferenceResolver)}.
     */
    public static IDocumentModel loadSelfContained(Reader reader) throws IOException {
        return load(reader, ReferenceResolvers.selfContained());
    }

    /**
     * Deserialize the model, expand it against {@code resolver} (resolving includes / type-def
     * imports), and check the expanded model's consistency. The returned model is expanded in place.
     *
     * @throws ModelConsistencyException if the expanded model has {@code ERROR}-severity problems
     */
    public static IDocumentModel load(Reader reader, IDocumentModelReferenceResolver resolver) throws IOException {
        IDocumentModelSerializer serializer = FACTORY.createDocumentModelSerializer();
        IDocumentModelService service = FACTORY.createDocumentModelService();

        IDocumentModel model = serializer.deserialize(reader);
        service.expand(model, resolver);

        List<RankedNotification> errors = new ArrayList<>();
        service.checkConsistency(model, notification -> {
            if (notification.getSeverity() == Severity.ERROR) {
                errors.add(notification);
            }
        });
        if (!errors.isEmpty()) {
            throw new ModelConsistencyException(errors);
        }
        return model;
    }

    /**
     * Deserialize + expand <b>without</b> the consistency gate — for <em>read-only</em> inspection of a model
     * that may be inconsistent (e.g. a candidate rule whose error field is not yet valid, G4). Unlike
     * {@link #load}, it never throws on {@code ERROR}-severity problems, so the caller can read what the model
     * <em>says</em> (its parsed conditions, references, structure) before it would pass validation. It makes
     * <b>no validity promise</b> — never use it for the write path (which round-trips through {@link #load}).
     */
    public static IDocumentModel expandOnly(Reader reader, IDocumentModelReferenceResolver resolver)
            throws IOException {
        IDocumentModelSerializer serializer = FACTORY.createDocumentModelSerializer();
        IDocumentModelService service = FACTORY.createDocumentModelService();
        IDocumentModel model = serializer.deserialize(reader);
        service.expand(model, resolver);
        return model;
    }
}

package io.github.mbackschat.a12.dm.adapter;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

import com.mgmtp.a12.kernel.md.facade.DocumentModelServiceFactory;
import com.mgmtp.a12.kernel.md.model.api.IDocumentModel;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelSerializer;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelService;

import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.core.ModelResolver;
import io.github.mbackschat.a12.dm.core.Notification;
import io.github.mbackschat.a12.dm.core.Severity;

/** One adapter-internal kernel deserialize/expand result, shared by checking and service bootstrap. */
final class KernelModelPreparation {

    private static final DocumentModelServiceFactory FACTORY = new DocumentModelServiceFactory();

    private KernelModelPreparation() {
    }

    static ExpandedModel expand(String modelJson, ModelResolver resolver) {
        IDocumentModelSerializer serializer = FACTORY.createDocumentModelSerializer();
        IDocumentModelService service = FACTORY.createDocumentModelService();
        try {
            IDocumentModel model = serializer.deserialize(new StringReader(modelJson));
            service.expand(model, KernelResolvers.of(resolver));
            return new ExpandedModel(model, service);
        } catch (IOException exception) {
            throw new UncheckedIOException("failed to deserialize the model DM-JSON", exception);
        }
    }

    record ExpandedModel(IDocumentModel model, IDocumentModelService service) {

        List<Notification> notifications() {
            List<Notification> notifications = new ArrayList<>();
            service.checkConsistency(model, problem -> notifications.add(KernelNotifications.toCore(problem)));
            return List.copyOf(notifications);
        }

        IDocumentModel requireConsistent() {
            List<Notification> errors = notifications().stream()
                    .filter(notification -> notification.severity() == Severity.ERROR)
                    .toList();
            if (!errors.isEmpty()) {
                throw new ModelConsistencyException(errors);
            }
            return model;
        }

        String variant() {
            return service.extractUsedVariants(model).stream().findFirst().orElse("");
        }
    }
}

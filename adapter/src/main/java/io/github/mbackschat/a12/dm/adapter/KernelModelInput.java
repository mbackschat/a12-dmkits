package io.github.mbackschat.a12.dm.adapter;

import java.io.IOException;
import java.io.StringReader;

import com.mgmtp.a12.kernel.md.model.a12internal.Computation;
import com.mgmtp.a12.kernel.md.model.a12internal.DocumentModel;
import com.mgmtp.a12.kernel.md.model.a12internal.Element;
import com.mgmtp.a12.kernel.md.model.a12internal.Group;
import com.mgmtp.a12.kernel.md.model.a12internal.IDocumentModelObjectReference;
import com.mgmtp.a12.kernel.md.model.a12internal.Rule;
import com.mgmtp.a12.kernel.md.model.a12internal.visitor.DocumentModelVisitor;
import com.mgmtp.a12.kernel.md.model.a12internal.visitor.DocumentModelWalker;
import com.mgmtp.a12.kernel.md.model.api.IDocumentModel;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelSerializer;
import com.mgmtp.a12.kernel.md.model.api.visitor.DocumentModelWalker.VisitProcess;
import com.mgmtp.a12.kernel.md.serializer.model.internal.service.DocumentModelSerializerImpl;

import io.github.mbackschat.a12.dm.core.ModelInputException;

/** Classifies malformed input at the kernel deserializer boundary without relabelling unrelated failures. */
final class KernelModelInput {

    private static final String PATH_ARITHMETIC = "com.mgmtp.a12.kernel.core.base.utils.internal.ElementPathUtils";

    private KernelModelInput() {
    }

    static IDocumentModel deserialize(IDocumentModelSerializer serializer, String json) {
        try {
            return serializer.deserialize(new StringReader(json));
        } catch (IOException failure) {
            throw new ModelInputException("invalid model DM-JSON: " + failure.getMessage(), failure);
        } catch (NullPointerException failure) {
            classifyMissingReference(json, failure);
            throw failure;
        } catch (IndexOutOfBoundsException | IllegalArgumentException failure) {
            if (thrownByPathArithmetic(failure)) {
                throw new ModelInputException("a reference path in the model cannot be resolved: "
                        + failure.getMessage(), failure);
            }
            throw failure;
        }
    }

    /**
     * Whether the kernel's path arithmetic raised this failure while resolving references: a relative path that
     * climbs past the root fails in {@code ElementPathUtils.absPath}, and a slash-led one in
     * {@code splitRelPath} (KERNEL-FINDINGS KF643). The first frame outside the JDK names the thrower, so the
     * same exception type from anywhere else keeps its identity.
     */
    private static boolean thrownByPathArithmetic(RuntimeException failure) {
        for (StackTraceElement frame : failure.getStackTrace()) {
            String owner = frame.getClassName();
            if (owner.startsWith("java.") || owner.startsWith("jdk.")) {
                continue;
            }
            return owner.equals(PATH_ARITHMETIC);
        }
        return false;
    }

    static void classifyMissingReference(String json, NullPointerException failure) {
        // DocumentModelSerializer resolves references before returning. Its own mapper lets us inspect
        // the owner objects before that step; a valid object graph leaves the original defect untouched.
        DocumentModel internal;
        try {
            internal = DocumentModelSerializerImpl.getMapper().readValue(json, DocumentModel.class);
        } catch (IOException invalid) {
            throw new ModelInputException("invalid model DM-JSON: " + invalid.getMessage(), failure);
        }
        if (internal == null || internal.getContent() == null || internal.getContent().getModelRoot() == null) {
            throw new ModelInputException("model DM-JSON must contain a non-null content.modelRoot", failure);
        }
        new DocumentModelWalker().acceptDocumentModel(internal, new DocumentModelVisitor() {
            @Override
            public VisitProcess visitRule(Rule rule) {
                requireReference(rule.getErrorEntity(), rule, "errorEntityRelPath", failure);
                return VisitProcess.CONTINUE_TRAVERSAL;
            }

            @Override
            public VisitProcess visitComputation(Computation computation) {
                requireReference(computation.getComputedField(), computation, "computedFieldRelPath", failure);
                return VisitProcess.CONTINUE_TRAVERSAL;
            }

            @Override
            public VisitProcess visitGroup(Group group) {
                for (var sortField : group.getSortFields()) {
                    requireReference(sortField.getField(), group, "sortFields[].name", failure);
                }
                return VisitProcess.CONTINUE_TRAVERSAL;
            }
        });
    }

    private static void requireReference(IDocumentModelObjectReference<?> reference, Element element,
            String property, NullPointerException failure) {
        if (reference == null) {
            throw new ModelInputException("missing required " + property + " on element '" + element.getId() + "'", failure);
        }
    }
}

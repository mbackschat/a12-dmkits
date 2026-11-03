package io.github.mbackschat.a12.dm.adapter;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.mgmtp.a12.kernel.md.model.a12internal.Computation;
import com.mgmtp.a12.kernel.md.model.a12internal.DocumentModel;
import com.mgmtp.a12.kernel.md.model.a12internal.Element;
import com.mgmtp.a12.kernel.md.model.a12internal.Group;
import com.mgmtp.a12.kernel.md.model.a12internal.Rule;
import com.mgmtp.a12.kernel.md.model.a12internal.visitor.DocumentModelVisitor;
import com.mgmtp.a12.kernel.md.model.a12internal.visitor.DocumentModelWalker;
import com.mgmtp.a12.kernel.md.model.api.IDocumentModel;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelSerializer;
import com.mgmtp.a12.kernel.md.model.api.visitor.DocumentModelWalker.VisitProcess;
import com.mgmtp.a12.kernel.md.serializer.model.internal.service.DocumentModelSerializerImpl;

import tools.jackson.core.JacksonException;

import io.github.mbackschat.a12.dm.core.ConsistencyCheckFailedException;
import io.github.mbackschat.a12.dm.core.ModelInputException;

/** Classifies malformed input at the kernel deserializer boundary without relabelling unrelated failures. */
final class KernelModelInput {

    private static final String PATH_ARITHMETIC = "com.mgmtp.a12.kernel.core.base.utils.internal.ElementPathUtils";

    private KernelModelInput() {
    }

    static IDocumentModel deserialize(IDocumentModelSerializer serializer, String json) {
        try {
            return serializer.deserialize(new StringReader(json));
        } catch (IOException | JacksonException failure) {
            // Kernel 31 reads through Jackson 3, whose exceptions are unchecked rather than IOExceptions, so a
            // malformed or unbindable model now arrives as a JacksonException (ModelInputClassificationTest).
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
        absentRequiredMember(json, failure).ifPresent(member -> {
            throw new ModelInputException("missing required " + member, failure);
        });
    }

    /** The kernel's own throw while building a model, as the typed failure its callers fold into a verdict (KF259). */
    static ConsistencyCheckFailedException couldNotBuild(RuntimeException thrown) {
        String why = thrown.getMessage() != null ? thrown.getMessage() : thrown.getClass().getSimpleName();
        return new ConsistencyCheckFailedException("the kernel could not build this model: " + why, thrown);
    }

    /**
     * Names the build failure by the stored member the model lacks, when it lacks one.
     *
     * <p>Kernel 31.1.1 reads a model missing a required member and then dies building it (KERNEL-FINDINGS
     * KF654). Its words name at most a null Java variable, and only through the JVM's helpful-NPE detail, which
     * the native image need not carry; two of the members draw no detail at all ("could not be serialized",
     * a bare {@code NullPointerException}). So the member is read from the model itself and appended in our
     * own words, which the corrective matches.
     */
    static ConsistencyCheckFailedException namingAbsentMember(String json, ConsistencyCheckFailedException failure) {
        return absentRequiredMember(json, failure)
                .map(member -> new ConsistencyCheckFailedException(
                        failure.getMessage() + " (missing required " + member + ")", failure.getCause()))
                .orElse(failure);
    }

    /**
     * The first required member the read model lacks, as "{@code <member> on <owner>}".
     *
     * <p>DocumentModelSerializer resolves references before returning, so its own mapper is used to inspect the
     * owner objects before that step. A model that reads to nothing, or lacks none of them, yields empty.
     */
    private static Optional<String> absentRequiredMember(String json, RuntimeException failure) {
        DocumentModel internal;
        try {
            internal = DocumentModelSerializerImpl.getMapper().readValue(json, DocumentModel.class);
        } catch (JacksonException invalid) {
            throw new ModelInputException("invalid model DM-JSON: " + invalid.getMessage(), failure);
        }
        if (internal == null || internal.getContent() == null || internal.getContent().getModelRoot() == null) {
            throw new ModelInputException("model DM-JSON must contain a non-null content.modelRoot", failure);
        }
        if (internal.getHeader() == null || internal.getHeader().getId() == null) {
            return Optional.of("id on the header");
        }
        List<String> absent = new ArrayList<>();
        new DocumentModelWalker().acceptDocumentModel(internal, new DocumentModelVisitor() {
            @Override
            public VisitProcess visitRule(Rule rule) {
                require(rule.getErrorEntity(), rule, "errorEntityRelPath");
                require(rule.getErrorCode(), rule, "errorCode");
                return VisitProcess.CONTINUE_TRAVERSAL;
            }

            @Override
            public VisitProcess visitComputation(Computation computation) {
                require(computation.getComputedField(), computation, "computedFieldRelPath");
                return VisitProcess.CONTINUE_TRAVERSAL;
            }

            @Override
            public VisitProcess visitGroup(Group group) {
                for (var sortField : group.getSortFields()) {
                    require(sortField.getField(), group, "sortFields[].name");
                }
                return VisitProcess.CONTINUE_TRAVERSAL;
            }

            private void require(Object member, Element owner, String property) {
                if (member == null) {
                    absent.add(property + " on element '" + owner.getId() + "'");
                }
            }
        });
        return absent.stream().findFirst();
    }
}

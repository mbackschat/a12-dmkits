package io.github.mbackschat.a12.dm.adapter;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import com.mgmtp.a12.kernel.md.document.apiV2.PartiallyKnownDocumentMultiPointer;
import com.mgmtp.a12.kernel.md.rt.api.IDocumentValidationResult;
import com.mgmtp.a12.kernel.md.rt.api.IMessage;

/** The boundary conversion (N8) of a kernel validation result into the JDK-only {@link EvalResult}. */
final class KernelMessages {

    private KernelMessages() {
    }

    static EvalResult toEvalResult(IDocumentValidationResult result) {
        return new EvalResult(result.getMessages().stream().map(KernelMessages::evalMessage).toList());
    }

    private static EvalMessage evalMessage(IMessage m) {
        return EvalMessage.builder()
                .errorCode(m.getErrorCode())
                .rulePath(m.getRulePath().orElse(null))
                .field(fieldPointer(m.getErrorFieldPointer()))
                .severity(m.getSeverity().name())
                .messageType(m.getMessageType().name())
                .message(m.getErrorText())
                .referenced(fieldPointers(m.getReferencedFieldsPointers()))
                .fillToFix(fieldPointers(m.getRefOmissionErrorResponsiblePointers()))
                .possiblyCausing(m.getGroupsOfPossiblyCausingEntities().stream()
                        .map(group -> Set.copyOf(fieldPointers(group))).toList())
                .build();
    }

    private static List<FieldPointer> fieldPointers(Collection<PartiallyKnownDocumentMultiPointer> pointers) {
        return pointers.stream().map(KernelMessages::fieldPointer).toList();
    }

    private static FieldPointer fieldPointer(PartiallyKnownDocumentMultiPointer p) {
        return new FieldPointer(p.fullName(), p.repetitionIndexes());
    }
}

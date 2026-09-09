package io.github.mbackschat.a12.dm.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.core.ModelResolver;
import io.github.mbackschat.a12.dm.core.Verification;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;

class ValidationFailureBoundaryTest {

    enum Boundary { DOCUMENT, SESSION }

    private static String host(boolean bilingual) {
        var builder = DocumentModelDraft.named("host").locale("en_US");
        if (bilingual) builder.locale("de_DE");
        return builder.include("library", "library")
                .rootGroup("Host", root -> root.mount("Included", "library")).build().toJson();
    }

    private static String library() {
        return DocumentModelDraft.named("library").locale("en_US")
                .rootGroup("Library", root -> root.field("Value", FieldKind.STRING)).build().toJson();
    }

    private static Verification validate(Boundary boundary, String json, ModelResolver resolver) {
        return switch (boundary) {
            case DOCUMENT -> ModelDocument.read(json).validate(resolver).consistencyGate().orElseThrow();
            case SESSION -> EditSession.read(json, resolver).validate().consistencyGate().orElseThrow();
        };
    }

    @ParameterizedTest
    @EnumSource(Boundary.class)
    void resolverDefectsKeepTheirIdentity(Boundary boundary) {
        for (RuntimeException defect : new RuntimeException[] {
                new NullPointerException("resolver defect"), new IllegalArgumentException("resolver defect")}) {
            ModelResolver resolver = reference -> { throw defect; };
            assertSame(defect, assertThrows(RuntimeException.class, () -> validate(boundary, host(false), resolver)));
        }
    }

    @ParameterizedTest
    @EnumSource(Boundary.class)
    void compatibleIncludesStillEarnTheKernelReceipt(Boundary boundary) {
        assertEquals(Verification.KERNEL_CONFIRMED, validate(boundary, host(false), reference -> library()));
    }

    @ParameterizedTest
    @EnumSource(Boundary.class)
    void kernelExpansionRefusalsRemainModelRejections(Boundary boundary) {
        var refusal = assertThrows(ModelConsistencyException.class,
                () -> validate(boundary, host(true), reference -> library()));
        assertEquals("DocumentModelExpansionException", refusal.getCause().getClass().getSimpleName());
        assertTrue(refusal.getMessage().contains("library"), refusal::getMessage);
    }
}

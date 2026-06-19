package io.github.mbackschat.a12.dm.modelkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.mgmtp.a12.kernel.md.facade.DocumentModelServiceFactory;
import com.mgmtp.a12.kernel.md.model.api.IDocumentModel;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelSerializer;
import com.mgmtp.a12.model.notification.RankedNotification;
import com.mgmtp.a12.model.notification.Severity;

import io.github.mbackschat.a12.dm.core.ModelIndex;
import io.github.mbackschat.a12.dm.core.ModelLoader;
import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * modelkit M1 — a from-scratch structure draft must emit DM-JSON the real kernel accepts. The
 * structure package is pure-emit (Jackson only, no kernel/dsl coupling); this test is the oracle:
 * the emitted JSON passes both kernel gates — {@code checkConsistency} (via {@link ModelLoader}) and
 * {@code BasicConsistencyCheck} (the serialize/write gate, F6/Q5) — and the loaded model indexes by
 * name-path with the right kinds. Own domain (a boutique), not adapted from any fixture.
 */
class DocumentModelDraftTest {

    private static DocumentModelDraft boutique() {
        return DocumentModelDraft.named("boutique").locale("en_US")
                .rootGroup("Boutique", b -> b
                        .field("BoutiqueName", FieldKind.STRING)
                        .field("OpenedOn", FieldKind.DATE)
                        .field("Curated", FieldKind.CONFIRM)
                        .group("Garment", g -> g
                                .repeatable(50)
                                .field("Sku", FieldKind.STRING)
                                .field("Price", FieldKind.NUMBER)
                                .field("InStock", FieldKind.BOOLEAN)))
                .build();
    }

    private static IDocumentModel load(String json) {
        try {
            return ModelLoader.loadSelfContained(new StringReader(json));
        } catch (Exception e) {
            throw new RuntimeException("emitted DM-JSON failed checkConsistency:\n" + json, e);
        }
    }

    @Test
    void emitsAModelTheKernelAccepts_checkConsistency() {
        // loadSelfContained runs deserialize → expand → checkConsistency, throwing on any ERROR
        IDocumentModel model = load(boutique().toJson());
        ModelIndex index = ModelIndex.of(model);

        // fields resolve by name-path, with the kinds we declared
        assertEquals(ValueKind.STRING, index.kindOf("/Boutique/BoutiqueName"));
        assertEquals(ValueKind.DATE, index.kindOf("/Boutique/OpenedOn"));
        assertEquals(ValueKind.CONFIRM, index.kindOf("/Boutique/Curated"));
        assertEquals(ValueKind.STRING, index.kindOf("/Boutique/Garment/Sku"));
        assertEquals(ValueKind.NUMBER, index.kindOf("/Boutique/Garment/Price"));
        assertEquals(ValueKind.BOOLEAN, index.kindOf("/Boutique/Garment/InStock"));
        // the nested group repeats; the parent doesn't
        assertTrue(index.isRepeating("/Boutique/Garment"));
        assertFalse(index.isRepeating("/Boutique"));
    }

    @Test
    void emitsAModelThatPassesTheWriteGate_basicConsistencyCheck() {
        IDocumentModel model = load(boutique().toJson());
        IDocumentModelSerializer serializer = new DocumentModelServiceFactory().createDocumentModelSerializer();

        List<RankedNotification> errors = new ArrayList<>();
        StringWriter out = new StringWriter();
        try {
            serializer.serialize(model, out, n -> {
                if (n.getSeverity() == Severity.ERROR) {
                    errors.add(n);
                }
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        assertTrue(errors.isEmpty(), () -> "BasicConsistencyCheck reported ERRORs: " + errors);
        assertFalse(out.toString().isBlank(), "serialize produced output");
    }

    @Test
    void everyEasyFieldKindIsKernelValid() {
        // smoke the full easy-kind set in one model
        DocumentModelDraft m = DocumentModelDraft.named("kinds").locale("en_US")
                .rootGroup("Root", r -> r
                        .field("S", FieldKind.STRING)
                        .field("N", FieldKind.NUMBER)
                        .field("D", FieldKind.DATE)
                        .field("B", FieldKind.BOOLEAN)
                        .field("C", FieldKind.CONFIRM))
                .build();
        ModelIndex index = ModelIndex.of(load(m.toJson()));
        assertEquals(ValueKind.STRING, index.kindOf("/Root/S"));
        assertEquals(ValueKind.NUMBER, index.kindOf("/Root/N"));
        assertEquals(ValueKind.DATE, index.kindOf("/Root/D"));
        assertEquals(ValueKind.BOOLEAN, index.kindOf("/Root/B"));
        assertEquals(ValueKind.CONFIRM, index.kindOf("/Root/C"));
    }

    @Test
    void conditionLanguageDefaultsToEnglishRegardlessOfDisplayLocale() {
        // A German DISPLAY locale must NOT force German condition KEYWORDS. Condition language is independent
        // of display locale: the kernel's own models (a12-kernel-mm) declare a de_DE display locale yet set
        // conditionLanguage=en_US and author English-keyword rules (FieldFilled/And/…). English keywords are
        // the default everywhere; the display locale only governs labels/messages.
        String json = DocumentModelDraft.named("amt").locale("de_DE")
                .rootGroup("Person", p -> p.field("Age", FieldKind.NUMBER))
                .build().toJson();
        assertEquals("en_US", ModelDocument.read(json).config().conditionLanguage(),
                "a de_DE display locale must keep en_US condition keywords (locale ≠ keyword language)");
        // the kernel accepts en_US condition language on a de_DE-ONLY model — conditionLanguage need not be a
        // declared display locale (load() runs checkConsistency, throwing on any ERROR)
        load(json);
    }

    @Test
    void aModelNeedsALocaleAndARootGroup() {
        try {
            DocumentModelDraft.named("x").build();
            throw new AssertionError("expected a missing-locale/root-group rejection");
        } catch (IllegalStateException expected) {
            // illegal states are unrepresentable at build()
        }
    }
}

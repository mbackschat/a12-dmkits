package io.github.mbackschat.a12.dm.modelkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;

/**
 * A display label owes the locales the KERNEL asks of it, and no more.
 *
 * <p>The draft used to demand that every label — a field's, a group's, an enumeration value's — answer every
 * declared locale, the last of those correctly and the first two by analogy with it. The kernel imposes
 * nothing on a field or group label and arms the enum-value law only on an entry for a DECLARED locale
 * ({@code rulekit.validate.laws.ElementLabelLocaleLawsTest}, [KERNEL-FINDINGS KF321]) — so the analogy made
 * three shapes it accepts unauthorable, and unreadable with them, since this draft is also the read model.
 */
class ElementLabelLocaleTest {

    private static final String UNDECLARED = "fr_FR";

    @Test
    void aFieldLabelledOnlyForAnUndeclaredLocaleIsWrittenAndTakenByTheKernel() {
        String json = DocumentModelDraft.named("labels").locale("en_US")
                .rootGroup("Order", g -> g.stringField("Customer", f -> f.label(UNDECLARED, "Client")))
                .build().toJson();

        assertTrue(dense(json).contains("{\"locale\":\"fr_FR\",\"text\":\"Client\"}"),
                () -> "the label must survive the write: " + json);
        BoundModel.ofJson(json);
    }

    @Test
    void aGroupLabelledOnlyForAnUndeclaredLocaleIsWrittenAndTakenByTheKernel() {
        String json = DocumentModelDraft.named("labels").locale("en_US")
                .rootGroup("Order", g -> g.label(UNDECLARED, "Commande").stringField("Customer", f -> { }))
                .build().toJson();

        assertTrue(dense(json).contains("{\"locale\":\"fr_FR\",\"text\":\"Commande\"}"),
                () -> "the label must survive the write: " + json);
        BoundModel.ofJson(json);
    }

    @Test
    void anElementLabelMayAnswerOnlyOneOfTwoDeclaredLocales() {
        String json = DocumentModelDraft.named("labels").locale("en_US").locale("de_DE")
                .rootGroup("Order", g -> g.stringField("Customer", f -> f.label("en_US", "Customer")))
                .build().toJson();

        assertTrue(dense(json).contains("\"label\":[{\"locale\":\"en_US\",\"text\":\"Customer\"}]"),
                () -> "the answered locale must be written, and the unanswered one left out: " + json);
        BoundModel.ofJson(json);
    }

    @Test
    void anEnumLabelledOnlyForAnUndeclaredLocaleIsWrittenAndTakenByTheKernel() {
        String json = DocumentModelDraft.named("labels").locale("en_US")
                .rootGroup("Order", g -> g.enumField("Tier", e -> e
                        .value("BASIC", l -> l.label(UNDECLARED, "Basique"))
                        .value("PLUS", l -> l.label(UNDECLARED, "Plus"))))
                .build().toJson();

        assertTrue(dense(json).contains("{\"locale\":\"fr_FR\",\"text\":\"Basique\"}"),
                () -> "the label must survive the write: " + json);
        BoundModel.ofJson(json);
    }

    /**
     * The read model is the same draft, so a label it cannot write is one it cannot carry back either.
     *
     * <p>This is the half a write-only test misses: an over-strict writer makes an EXISTING model unreadable,
     * which is why the project's refusal rule asks for a read test beside the authoring one.
     */
    /** The writer pretty-prints, so every shape assertion above reads the same bytes without the spacing. */
    private static String dense(String json) {
        return json.replace(" ", "").replace("\n", "");
    }

    @Test
    void anUndeclaredLocaleLabelSurvivesAReadAndRewrite() {
        String written = DocumentModelDraft.named("labels").locale("en_US")
                .rootGroup("Order", g -> g
                        .stringField("Customer", f -> f.label(UNDECLARED, "Client"))
                        .enumField("Tier", e -> e
                                .value("BASIC", l -> l.label(UNDECLARED, "Basique"))
                                .value("PLUS", l -> l.label(UNDECLARED, "Plus"))))
                .build().toJson();

        assertEquals(written, EditSession.read(written).toJson(), "a read and rewrite must be the identity");
    }
}

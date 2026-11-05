package io.github.mbackschat.a12.dm.edit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.core.ModelInputException;
import io.github.mbackschat.a12.dm.core.ReferenceResolvers;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;

/**
 * The edit gates' shared root, {@code ModelLoader.checkEdit}: a reader refusal that only the edit drew is the gate
 * rejecting the edit, a {@link ModelConsistencyException}, on every carrier that gates an edit, while one the source
 * draws too stays the input's {@link ModelInputException}. A12's reader refuses the locale {@code en_us}
 * (KERNEL-FINDINGS KF290), which is how each edit below makes its model unreadable. Own domain (insurance policies).
 */
class EditGateReaderRefusalTest {

    private static final String POLICY = DocumentModelDraft.named("policy").locale("en_US")
            .rootGroup("Policy", p -> p.stringField("PolicyNo", f -> { }).stringField("Holder", f -> { }))
            .build().toJson();

    @Test
    void anEditSessionEditTheReaderRefusesIsTheGatesRejection() {
        var rejected = assertThrows(ModelConsistencyException.class,
                () -> EditSession.read(POLICY).setModelLabel("en_us", "Policy").validate());
        assertTrue(rejected.getMessage().contains("en_us"), rejected::getMessage);
    }

    @Test
    void aModelDocumentEditTheReaderRefusesIsTheGatesRejection() {
        assertThrows(ModelConsistencyException.class,
                () -> ModelDocument.read(POLICY).setModelLabel("en_us", "Policy").validate());
    }

    /**
     * The re-bind the CLI's rule and computation verbs gate an edit with. The typed {@code Rule} builder already
     * refuses an undeclared message locale, so the edit here is a label, written beside the source it came from.
     */
    @Test
    void aBoundModelReboundAfterAnEditIsTheGatesRejection() {
        String edited = EditSession.read(POLICY).setModelLabel("en_us", "Policy").toJson();
        assertThrows(ModelConsistencyException.class,
                () -> BoundModel.ofEdit(edited, POLICY, ReferenceResolvers.selfContained()));
        assertThrows(ModelInputException.class, () -> BoundModel.ofJson(edited));
    }

    /** The control: the source already carries the refused locale, so a harmless edit leaves the blame with it. */
    @Test
    void aSourceTheReaderRefusesStaysTheInputsRefusal() {
        String unreadable = EditSession.read(POLICY).setModelLabel("en_US", "Policy").toJson()
                .replaceAll("(\"locale\"\\s*:\\s*)\"en_US\"", "$1\"en_us\"");
        assertTrue(unreadable.contains("\"en_us\""), "the fixture stores en_us");
        assertThrows(ModelInputException.class,
                () -> EditSession.read(unreadable).setModelLabel("de_DE", "Police").validate());
        assertThrows(ModelInputException.class,
                () -> ModelDocument.read(unreadable).setModelLabel("de_DE", "Police").validate());
    }
}

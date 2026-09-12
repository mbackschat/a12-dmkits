package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mbackschat.a12.dm.core.ModelInputException;
import io.github.mbackschat.a12.dm.core.ModelLoader;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * How far A12's reader descends before a DM-JSON stops being a DocumentModel — the basis for the portable
 * checker's one admission refusal (PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN, B2).
 *
 * <p><b>The rows exist to bound a client-side refusal, so their value is in where the boundary is NOT.</b>
 * Every shape above {@code content} is read without complaint, including an empty root — which is not a
 * degenerate case but the normal shape of a type-def-only library ({@code TypeDefModelImportTest}). So a
 * checker refusing "a model with no groups" would reject a legal A12 artifact, and only the missing
 * {@code content} row licenses a refusal at all.
 *
 * <p>The shapes are produced by removing one member from a model the typed builder authored, so each row
 * differs from the accepted control by exactly that member.
 */
class ModelRootReadabilityLawsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String ORDINARY = DocumentModelDraft.named("catalog").locale("en_US")
            .rootGroup("Catalog", catalog -> catalog.field("Name", FieldKind.STRING))
            .build().toJson();

    /** The builder's own output with one edit applied — never a hand-authored envelope. */
    private static String edited(Consumer<ObjectNode> edit) {
        try {
            ObjectNode tree = (ObjectNode) MAPPER.readTree(ORDINARY);
            edit.accept(tree);
            return MAPPER.writeValueAsString(tree);
        } catch (Exception unreadable) {
            throw new IllegalStateException("the fixture itself must be readable", unreadable);
        }
    }

    private static ObjectNode content(ObjectNode tree) {
        return (ObjectNode) tree.get("content");
    }

    @Test
    void anOrdinaryModelIsRead() {
        assertDoesNotThrow(() -> ModelLoader.loadSelfContained(ORDINARY));
    }

    /**
     * The row that makes the refusal narrow. An empty root is what {@code DocumentModelDraft.typeDefModel}
     * produces, so treating "declares no elements" as unreadable would refuse a whole legitimate artifact
     * kind — the over-rejection the client-side-refusal rule exists to prevent.
     */
    @Test
    void anEmptyRootIsRead() {
        assertDoesNotThrow(() -> ModelLoader.loadSelfContained(
                edited(tree -> ((ObjectNode) content(tree).get("modelRoot")).putArray("rootGroups"))));
    }

    @Test
    void anAbsentRootGroupsMemberIsRead() {
        assertDoesNotThrow(() -> ModelLoader.loadSelfContained(
                edited(tree -> ((ObjectNode) content(tree).get("modelRoot")).remove("rootGroups"))));
    }

    @Test
    void anAbsentModelRootIsRead() {
        assertDoesNotThrow(() -> ModelLoader.loadSelfContained(
                edited(tree -> content(tree).remove("modelRoot"))));
    }

    /**
     * The only shape the reader cannot take, and therefore the only one a checker may refuse for — measured
     * both ways a source can fail to carry a model container, because a refusal written for "absent" and
     * applied to "not an object" would be broader than its basis.
     */
    @Test
    void aContentThatIsNotAModelContainerIsNotReadable() {
        assertThrows(ModelInputException.class, () -> ModelLoader.loadSelfContained(
                edited(tree -> tree.remove("content"))));
        assertThrows(RuntimeException.class, () -> ModelLoader.loadSelfContained(
                edited(tree -> tree.put("content", "not a model"))));
        assertThrows(RuntimeException.class, () -> ModelLoader.loadSelfContained(
                edited(tree -> tree.putArray("content"))));
    }

    /**
     * {@code header.modelType} names which A12 service owns an artifact — {@code document},
     * {@code additive-document}, {@code combination}, {@code selection} — but the DocumentModel reader does
     * not consult it, so a foreign type reads as a document with whatever content it happens to carry. A
     * checker keying on it would be refusing on its own authority, which is why it does not.
     */
    @Test
    void aForeignModelTypeIsReadAnyway() {
        assertDoesNotThrow(() -> ModelLoader.loadSelfContained(
                edited(tree -> ((ObjectNode) tree.get("header")).put("modelType", "selection"))));
        assertDoesNotThrow(() -> ModelLoader.loadSelfContained(
                edited(tree -> ((ObjectNode) tree.get("header")).remove("modelType"))));
    }
}

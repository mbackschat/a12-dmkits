package io.github.mbackschat.a12.dm.modelkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.core.ModelLoader;
import io.github.mbackschat.a12.dm.edit.EditSession;

/**
 * The typed document uniqueness criterion (DEV-API-GAPS DG176): the draft and the edit path write A12's stored
 * shape, the read path returns what is stored, and admission stays with A12 (KERNEL-FINDINGS KF671), whose
 * consistency check is the oracle for every verdict here.
 */
class UniquenessCriterionAuthoringTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final UniquenessCriterion POLICY_KEY = new UniquenessCriterion("PolicyKey",
            List.of("/Policy/PolicyNo", "/Policy/Product"),
            orderedText("en_US", "Policy $/Policy/PolicyNo$ exists", "de_DE", "Police $/Policy/PolicyNo$ existiert"));

    private static DocumentModelDraft.Builder policy() {
        return DocumentModelDraft.named("policy").locale("en_US").locale("de_DE")
                .typeDef("Code", t -> t.string(s -> s.maxLength(8)))
                .rootGroup("Policy", p -> p
                        .stringField("PolicyNo", f -> f.required())
                        .stringField("Product", f -> f.required())
                        .stringField("Holder", f -> { }));
    }

    @Test
    void theDraftWritesAdmittedCriteriaInA12sShapeAndMemberOrder() throws Exception {
        String json = policy().uniquenessCriterion(POLICY_KEY).build().toJson();

        JsonNode content = JSON.readTree(json).path("content");
        assertEquals(List.of("modelInfo", "modelConfig", "documentUniquenessCriteria", "typeDefinitions", "modelRoot"),
                memberNames(content));
        assertEquals(JSON.readTree("""
                [{"name":"PolicyKey","fields":[{"fullName":"/Policy/PolicyNo"},{"fullName":"/Policy/Product"}],
                  "errorMessage":[{"locale":"en_US","text":"Policy $/Policy/PolicyNo$ exists"},
                                  {"locale":"de_DE","text":"Police $/Policy/PolicyNo$ existiert"}]}]"""),
                content.path("documentUniquenessCriteria"));
        ModelLoader.loadSelfContained(json);
        assertEquals(List.of(POLICY_KEY), ModelDocument.read(json).uniquenessCriteria());
    }

    /** The API-GUIDE snippet: author on the session, let A12 admit it, read it back. */
    @Test
    void theGuidesSessionFlowIsAdmittedAndReadsBack() {
        String policyJson = DocumentModelDraft.named("policy").locale("en_US")
                .rootGroup("Policy", p -> p.stringField("PolicyNo", f -> f.required()).stringField("Product", f -> f.required()))
                .build().toJson();
        // tag::uniqueness-criterion[]
        // No two stored policies may share a number and a product. A12 admits the criterion only over required,
        // persisted, non-repeatable fields named by full path, with an error text for the model's locale (KF671).
        UniquenessCriterion policyKey = new UniquenessCriterion("PolicyKey",
                List.of("/Policy/PolicyNo", "/Policy/Product"),
                Map.of("en_US", "Policy $/Policy/PolicyNo$ already exists"));
        String json = EditSession.read(policyJson).addUniquenessCriterion(policyKey).validate().toJson();
        List<UniquenessCriterion> criteria = EditSession.read(json).uniquenessCriteria();   // [policyKey]
        // end::uniqueness-criterion[]
        assertEquals(List.of(policyKey), criteria);
    }

    /** A model with no criterion member gets it where A12's model class declares it, not appended after the root. */
    @Test
    void theEditPathAddsTheMemberWhereA12DeclaresItAndTheKernelAdmitsIt() throws Exception {
        String json = EditSession.read(policy().build().toJson()).addUniquenessCriterion(POLICY_KEY).validate().toJson();

        assertEquals(List.of("modelInfo", "modelConfig", "documentUniquenessCriteria", "typeDefinitions", "modelRoot"),
                memberNames(JSON.readTree(json).path("content")));
        assertEquals(List.of(POLICY_KEY), EditSession.read(json).uniquenessCriteria());
    }

    /** Admission is A12's: the surface writes a criterion over an optional field, and A12 refuses it in its sentence. */
    @Test
    void anInadmissibleCriterionIsWrittenAndA12RefusesItInItsOwnSentence() {
        UniquenessCriterion overOptional = new UniquenessCriterion("HolderKey", List.of("/Policy/Holder"),
                orderedText("en_US", "Taken", "de_DE", "Vergeben"));
        EditSession session = EditSession.read(policy().build().toJson()).addUniquenessCriterion(overOptional);

        var refused = assertThrows(ModelConsistencyException.class, session::validate);
        String sentences = refused.notifications().stream().map(n -> n.message().trim()).collect(Collectors.joining(" | "));
        assertTrue(sentences.contains("The field '/Policy/Holder' is not set as required."), sentences);
    }

    /** The read path never refuses: a stored criterion A12 would refuse (no name, no field, no text) still reads. */
    @Test
    void aStoredCriterionA12RefusesStillReads() {
        String json = ModelDocument.read(policy().build().toJson())
                .addUniquenessCriterion(new UniquenessCriterion("", List.of(), Map.of())).toJson();

        assertEquals(List.of(new UniquenessCriterion("", List.of(), Map.of())), ModelDocument.read(json).uniquenessCriteria());
        assertEquals(List.of(), ModelDocument.read(policy().build().toJson()).uniquenessCriteria());
    }

    /** A12 refuses two criteria of one name (KF671), and a remove by name needs the name to pick out one. */
    @Test
    void aDuplicateNameIsRefusedOnAddAndAnUnknownNameOnRemove() {
        ModelDocument doc = ModelDocument.read(policy().uniquenessCriterion(POLICY_KEY).build().toJson());

        var duplicate = assertThrows(IllegalArgumentException.class, () -> doc.addUniquenessCriterion(
                new UniquenessCriterion("PolicyKey", List.of("/Policy/Product"), Map.of("en_US", "Taken"))));
        assertTrue(duplicate.getMessage().contains("'PolicyKey'"), duplicate::getMessage);
        var unknown = assertThrows(IllegalArgumentException.class, () -> doc.removeUniquenessCriterion("ProductKey"));
        assertTrue(unknown.getMessage().contains("'ProductKey'"), unknown::getMessage);
        assertEquals(List.of(POLICY_KEY), doc.uniquenessCriteria());
    }

    @Test
    void removingACriterionKeepsTheOthersAndTheLastRemovalDropsTheMember() throws Exception {
        UniquenessCriterion productKey = new UniquenessCriterion("ProductKey", List.of("/Policy/Product"),
                orderedText("en_US", "Taken", "de_DE", "Vergeben"));
        ModelDocument doc = ModelDocument.read(policy().uniquenessCriterion(POLICY_KEY).uniquenessCriterion(productKey)
                .build().toJson());

        assertEquals(List.of(productKey), doc.removeUniquenessCriterion("PolicyKey").uniquenessCriteria());
        String emptied = doc.removeUniquenessCriterion("ProductKey").toJson();
        assertEquals(List.of("modelInfo", "modelConfig", "typeDefinitions", "modelRoot"),
                memberNames(JSON.readTree(emptied).path("content")));
        ModelLoader.loadSelfContained(emptied);
    }

    private static Map<String, String> orderedText(String locale, String text, String otherLocale, String otherText) {
        Map<String, String> byLocale = new LinkedHashMap<>();
        byLocale.put(locale, text);
        byLocale.put(otherLocale, otherText);
        return byLocale;
    }

    private static List<String> memberNames(JsonNode object) {
        List<String> names = new ArrayList<>();
        object.fieldNames().forEachRemaining(names::add);
        return names;
    }
}

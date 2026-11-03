package io.github.mbackschat.a12.dm.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * What A12's own move support does to a document uniqueness criterion when a field it names is renamed or moved,
 * through the {@link ReferenceRewriter} round trip every rename and move verb runs first: whether the criterion
 * survives the typed round trip, and whether its field list and the {@code $path$} parameters of its text follow.
 * Own domain (insurance policies).
 */
class UniquenessCriterionRewriteTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String CRITERIA = "\"documentUniquenessCriteria\":[{\"name\":\"PolicyKey\","
            + "\"fields\":[{\"fullName\":\"/Policy/PolicyNo\"},{\"fullName\":\"/Policy/Product\"}],"
            + "\"errorMessage\":[{\"locale\":\"en_US\",\"text\":\"Policy $/Policy/PolicyNo.value$ of $/Policy/Product$ exists\"}]}],";

    private static String policy() {
        String json = DocumentModelDraft.named("policy-uniqueness").locale("en_US")
                .rootGroup("Policy", p -> p
                        .stringField("PolicyNo", f -> f.required())
                        .stringField("Product", f -> f.required())
                        .group("Office", o -> o.stringField("Branch", f -> f.required())))
                .build().toJson();
        int at = json.indexOf("\"modelRoot\"");
        return json.substring(0, at) + CRITERIA + json.substring(at);
    }

    /** The criteria of [json] as `name|field,field|text`, one entry per criterion. */
    private static String criteria(String json) throws Exception {
        JsonNode list = JSON.readTree(json).path("content").path("documentUniquenessCriteria");
        StringBuilder out = new StringBuilder();
        for (JsonNode criterion : list) {
            StringBuilder fields = new StringBuilder();
            criterion.path("fields").forEach(f -> fields.append(fields.isEmpty() ? "" : ",").append(f.path("fullName").asText()));
            out.append(criterion.path("name").asText()).append('|').append(fields).append('|')
                    .append(criterion.path("errorMessage").path(0).path("text").asText());
        }
        return list.isMissingNode() ? "(member absent)" : out.toString();
    }

    @Test
    void aRenameOrMoveOfACriterionFieldRewritesItsFieldsAndItsText() throws Exception {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("unchanged", criteria(ReferenceRewriter.rewriteReferences(policy(), Map.of())));
        actual.put("a field renamed", criteria(ReferenceRewriter.rewriteReferences(policy(),
                Map.of("/Policy/PolicyNo", "/Policy/PolicyNumber"))));
        actual.put("a field moved into a group", criteria(ReferenceRewriter.rewriteReferences(policy(),
                Map.of("/Policy/Product", "/Policy/Office/Product"))));
        actual.put("the root renamed", criteria(ReferenceRewriter.rewriteReferences(policy(),
                Map.of("/Policy", "/Contract"))));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("unchanged", "PolicyKey|/Policy/PolicyNo,/Policy/Product|Policy $/Policy/PolicyNo.value$ of $/Policy/Product$ exists");
        expected.put("a field renamed", "PolicyKey|/Policy/PolicyNumber,/Policy/Product|Policy $/Policy/PolicyNumber.value$ of $/Policy/Product$ exists");
        expected.put("a field moved into a group", "PolicyKey|/Policy/PolicyNo,/Policy/Office/Product|Policy $/Policy/PolicyNo.value$ of $/Policy/Office/Product$ exists");
        expected.put("the root renamed", "PolicyKey|/Contract/PolicyNo,/Contract/Product|Policy $/Contract/PolicyNo.value$ of $/Contract/Product$ exists");
        assertEquals(expected, actual);
    }
}

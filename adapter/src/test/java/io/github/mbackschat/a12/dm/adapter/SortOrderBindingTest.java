package io.github.mbackschat.a12.dm.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.modelkit.SortOrder;

/**
 * Which constant A12 binds each admitted order spelling to, read back from A12's own serializer, against the constant
 * our reader binds ({@code predicates.SortOrderBindingPredicatesTest}). A verdict cannot tell {@code ASC} from
 * {@code DESC}, so the ordinal rows of {@code validate.laws.GroupIndexAndSortReferenceLawsTest} say only that A12 reads
 * them; this says what it reads (KERNEL-FINDINGS KF680). Own domain (a claims register).
 */
class SortOrderBindingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void anAdmittedSpellingNamesTheConstantA12WritesBack() throws Exception {
        List<String> spellings = List.of("\"ASC\"", "\"DESC\"", "\" ASC\"", "\"\\tDESC\"", "0", "-0", "1", "\"0\"",
                "\" 1\"");
        Map<String, String> a12 = new LinkedHashMap<>();
        Map<String, String> ours = new LinkedHashMap<>();
        for (String spelling : spellings) {
            String model = model(spelling);
            JsonNode written = MAPPER.readTree(ReferenceRewriter.rewriteReferences(model, Map.of()));
            a12.put(spelling, written.at(ENTRY + "/order").asText());
            ours.put(spelling, ModelDocument.read(model).sortFields("/Register/Claims").getFirst().order().name());
        }
        assertEquals(Map.of("\"ASC\"", "ASC", "\"DESC\"", "DESC", "\" ASC\"", "ASC", "\"\\tDESC\"", "DESC", "0", "ASC",
                "-0", "ASC", "1", "DESC", "\"0\"", "ASC", "\" 1\"", "DESC"), a12);
        assertEquals(a12, ours);
    }

    private static final String ENTRY = "/content/modelRoot/rootGroups/0/Group/elements/0/Group/sortFields/0";

    private static String model(String order) throws Exception {
        ObjectNode root = (ObjectNode) MAPPER.readTree(DocumentModelDraft.named("claims").locale("en_US")
                .rootGroup("Register", r -> r.group("Claims", c -> c.repeatable(5)
                        .numberField("Amount", f -> f.fractionDigits(0)).sortField("Amount", SortOrder.ASC)))
                .build().toJson());
        ((ObjectNode) root.at(ENTRY)).set("order", MAPPER.readTree(order));
        return root.toString();
    }
}

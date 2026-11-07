package io.github.mbackschat.a12.dm.modelkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Both readers of a stored {@code sortFields} entry bind its order as A12's reader does, and refuse what A12 refuses
 * ({@code predicates.SortOrderBindingPredicatesTest}; the A12 rows are {@code validate.laws.GroupIndexAndSortReferenceLawsTest}
 * and {@code adapter.SortOrderBindingTest}). They used to read an absent order as {@code ASC} and throw an unnamed
 * {@code valueOf} error on the ordinal {@code 1}, a spelling A12 reads as {@code DESC}. Own domain (a claims register).
 */
class SortFieldBindingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CLAIMS = "/Register/Claims";

    @Test
    void anOrderReadsAsTheConstantA12BindsItTo() {
        Map<String, SortOrder> expected = new LinkedHashMap<>();
        expected.put("\"DESC\"", SortOrder.DESC);
        expected.put("\" ASC\"", SortOrder.ASC);
        expected.put("1", SortOrder.DESC);
        expected.put("-0", SortOrder.ASC);
        expected.put("\"0\"", SortOrder.ASC);
        Map<String, List<SortOrder>> observed = new LinkedHashMap<>();
        expected.forEach((raw, order) -> {
            ModelDocument document = ModelDocument.read(model(raw));
            observed.put(raw, List.of(document.sortFields(CLAIMS).getFirst().order(),
                    document.authoredState().groups().get(CLAIMS).structure().sortFields().getFirst().order()));
        });
        Map<String, List<SortOrder>> both = new LinkedHashMap<>();
        expected.forEach((raw, order) -> both.put(raw, List.of(order, order)));
        assertEquals(both, observed);
    }

    /** A12 refuses each of these at read, so neither reader invents an order or a name for it. */
    @Test
    void anEntryA12CannotReadIsRefusedByBothReaders() {
        for (String raw : List.of("ABSENT", "null", "\"asc\"", "2", "\"01\"", "true")) {
            ModelDocument document = ModelDocument.read(model(raw));
            var direct = assertThrows(IllegalArgumentException.class, () -> document.sortFields(CLAIMS), raw);
            assertEquals(true, direct.getMessage().contains("sortFields[0].order"), direct.getMessage());
            assertThrows(IllegalArgumentException.class, document::authoredState, raw);
        }
        ObjectNode root = (ObjectNode) read(model("\"ASC\""));
        sortEntry(root).remove("name");
        ModelDocument nameless = ModelDocument.read(root.toString());
        var refusal = assertThrows(IllegalArgumentException.class, () -> nameless.sortFields(CLAIMS));
        assertEquals(true, refusal.getMessage().contains("sortFields[0].name"), refusal.getMessage());
    }

    /** The writer's side of the same requirement: a sort field without an order is refused where it is made. */
    @Test
    void aSortFieldIsNeverMadeWithoutBothMembers() {
        var orderless = assertThrows(NullPointerException.class, () -> new ElementDraft.SortField("Amount", null));
        assertEquals(true, orderless.getMessage().contains("order"), orderless.getMessage());
        var nameless = assertThrows(NullPointerException.class, () -> new ElementDraft.SortField(null, SortOrder.ASC));
        assertEquals(true, nameless.getMessage().contains("name"), nameless.getMessage());
    }

    /** The claims register with one sort entry on {@code Amount}, its order replaced by [raw] or removed for ABSENT. */
    private static String model(String raw) {
        ObjectNode root = (ObjectNode) read(DocumentModelDraft.named("claims").locale("en_US")
                .rootGroup("Register", r -> r.group("Claims", c -> c.repeatable(5)
                        .numberField("Amount", f -> f.fractionDigits(0)).sortField("Amount", SortOrder.ASC)))
                .build().toJson());
        ObjectNode entry = sortEntry(root);
        if (raw.equals("ABSENT")) {
            entry.remove("order");
        } else {
            entry.set("order", read(raw));
        }
        return root.toString();
    }

    private static ObjectNode sortEntry(ObjectNode root) {
        return (ObjectNode) root.at("/content/modelRoot/rootGroups/0/Group/elements/0/Group/sortFields/0");
    }

    private static JsonNode read(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}

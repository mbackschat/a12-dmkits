package io.github.mbackschat.a12.dm.modelkit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How the authored-state reader reads a category token that is not a plain string, against what kernel 31.1.1 makes of
 * it (KERNEL-FINDINGS KF660, measured by {@code adapter.laws.CheckerCategoryTokenAgreementTest}): a number or a boolean
 * binds as its text, {@code null} is no token, as a blank one is, and an object or an array leaves the model unbuilt.
 *
 * The reader used to take each token's {@code asText()}, which wrote {@code null} back as the token {@code "null"} and
 * an object entry as {@code ""}, so a round trip turned a model A12 refuses into one it judges differently.
 */
class EnumCategoryTokenReadTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String model(String tokens) {
        String json = DocumentModelDraft.named("cover").locale("en_US")
                .rootGroup("Cover", cover -> cover.enumField("Tier", tier -> tier.value("GOLD").value("SILVER")
                        .category("Band", "HIGH", "LOW")))
                .build().toJson();
        try {
            JsonNode tree = MAPPER.readTree(json);
            JsonNode category = tree.findValue("categories").get(0);
            ((ObjectNode) category).set("values", MAPPER.readTree(tokens));
            return MAPPER.writeValueAsString(tree);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> tokens(String json) {
        FieldTypeSpec.EnumSpec enumeration = assertInstanceOf(FieldTypeSpec.EnumSpec.class,
                ModelDocument.read(json).authoredState().fields().get("/Cover/Tier").type());
        return enumeration.categories().getFirst().values();
    }

    @Test
    void aScalarTokenReadsAsItsText() {
        assertTrue(model("[1,true]").contains("\"values\":[1,true]"), "the fixture edit applied");
        assertEquals(List.of("1", "true"), tokens(model("[1,true]")));
    }

    @Test
    void aNullTokenReadsAsNoToken() {
        assertEquals(List.of("", "LOW"), tokens(model("[null,\"LOW\"]")));
    }

    @Test
    void anObjectOrArrayTokenIsRefusedAsA12RefusesIt() {
        for (String tokens : List.of("[{\"value\":\"HIGH\"},\"LOW\"]", "[[\"HIGH\"],\"LOW\"]")) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> tokens(model(tokens)));
            assertTrue(refused.getMessage().contains("Band"), refused.getMessage());
        }
    }
}

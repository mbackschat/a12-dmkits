package io.github.mbackschat.a12.dm.modelkit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.core.ModelInputException;

/**
 * A model document reads and edits a Group element's body as A12's reader binds it on kernel 31.1.1 ([KERNEL-FINDINGS
 * KF3]; {@code core.GroupBodyReadingTest} for the expanding reader): a missing or {@code null} body is an empty group
 * and any other non-object body is refused. Each operation is compared with the same document written with a
 * {@code {}} body. Reading leaves the document's text untouched; an edit writes the body out. Own domain (a permit
 * whose address group carries no body, beside a mounted component).
 */
class ModelDocumentGroupBodyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final List<String> SPELLINGS = List.of("missing", "null", "true", "7", "\"x\"", "[]");

    @Test
    void everyOperationTreatsANoValueBodyAsAnEmptyGroupAndRefusesAnyOtherNonObject() {
        Map<String, Function<String, String>> operations = new LinkedHashMap<>();
        operations.put("authored state", json -> ModelDocument.read(json).authoredState().groups()
                .get("/Permit/Address").structure().toString());
        operations.put("reading leaves the text alone", json -> {
            ModelDocument document = ModelDocument.read(json);
            document.authoredState();
            return String.valueOf(read(document.toJson()).equals(read(json)));
        });
        operations.put("an edit of the group", json -> read(ModelDocument.read(json)
                .setRepeatable("/Permit/Address", 3).toJson()).at("/content/modelRoot/rootGroups/0/Group/elements/1")
                .toString());
        operations.put("a repoint walks past it", json -> read(ModelDocument.read(json)
                .setIncludeReference("component", "component-v2").toJson())
                .at("/content/modelRoot/rootGroups/0/Group/elements/2/Group/includeConfig/reference").asText());
        Map<String, String> expected = new LinkedHashMap<>();
        Map<String, String> observed = new LinkedHashMap<>();
        operations.forEach((operation, run) -> {
            String control = run.apply(withAddressBody("{}"));
            for (String spelling : SPELLINGS) {
                boolean emptyGroup = spelling.equals("missing") || spelling.equals("null");
                expected.put(operation + " " + spelling, emptyGroup ? control : "refused");
                observed.put(operation + " " + spelling, outcome(run, withAddressBody(spelling)));
            }
        });
        assertEquals(expected, observed);
    }

    private static String outcome(Function<String, String> run, String json) {
        try {
            return run.apply(json);
        } catch (ModelInputException refused) {
            return "refused";
        }
    }

    /** A permit with a note, an address group whose body is {@code body}, and a mounted component. */
    private static String withAddressBody(String body) {
        ObjectNode root = (ObjectNode) read(DocumentModelDraft.named("permit").locale("en_US")
                .rootGroup("Permit", p -> p.field("Note", FieldKind.STRING)
                        .group("Address", a -> { })
                        .mount("Details", "component"))
                .build().toJson());
        ObjectNode address = (ObjectNode) root.at("/content/modelRoot/rootGroups/0/Group/elements/1");
        if (body.equals("missing")) {
            address.remove("Group");
        } else {
            address.set("Group", read(body));
        }
        return root.toString();
    }

    private static JsonNode read(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}

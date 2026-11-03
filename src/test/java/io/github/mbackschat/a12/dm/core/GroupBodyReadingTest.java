package io.github.mbackschat.a12.dm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.modelkit.MountOptions;

/**
 * A Group element's body is read as A12's reader reads it on kernel 31.1.1 ([KERNEL-FINDINGS KF3]; the A12 rows are
 * {@code adapter.WorkspaceIncludeStructureKernelTest}): a missing or {@code null} body is an empty group, on the
 * entry's root, a nested group, and a provider's root or nested group under either include level, while a body that
 * is not an object leaves no model. The {@code {}} body is the control each row is compared against. Own domain (a
 * permit and the component it includes).
 */
class GroupBodyReadingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final List<String> SPELLINGS = List.of("missing", "null", "true", "7", "\"x\"", "[]");

    @Test
    void aBodyIsReadAsA12ReadsItOnEveryCarrier() {
        Map<String, BiFunction<String, String, String>> carriers = new LinkedHashMap<>();
        carriers.put("entry root", (body, ignored) -> paths(withBody(entry(), "", body), null));
        carriers.put("entry nested", (body, ignored) -> paths(withBody(entry(), "Address", body), null));
        for (boolean modelRoot : new boolean[] {false, true}) {
            String level = modelRoot ? "MODEL_ROOT" : "SINGLE_RG";
            carriers.put(level + " provider root",
                    (body, ignored) -> paths(host(modelRoot), withBody(component(), "", body)));
            carriers.put(level + " provider nested",
                    (body, ignored) -> paths(host(modelRoot), withBody(component(), "Sub", body)));
        }
        Map<String, String> expected = new LinkedHashMap<>();
        Map<String, String> observed = new LinkedHashMap<>();
        carriers.forEach((carrier, read) -> {
            String control = read.apply("{}", null);
            for (String spelling : SPELLINGS) {
                boolean emptyGroup = spelling.equals("missing") || spelling.equals("null");
                expected.put(carrier + " " + spelling, emptyGroup ? control : "refused");
                observed.put(carrier + " " + spelling, read.apply(spelling, null));
            }
        });
        assertEquals(expected, observed);
    }

    private static String paths(String entry, String provider) {
        try {
            return ModelIndex.of(entry, reference -> "component".equals(reference) ? provider : null)
                    .elementPaths().toList().toString();
        } catch (ModelInputException refused) {
            return "refused";
        }
    }

    private static String entry() {
        return DocumentModelDraft.named("permit").locale("en_US")
                .rootGroup("Permit", p -> p.field("Note", FieldKind.STRING)
                        .group("Address", a -> a.field("Street", FieldKind.STRING)))
                .build().toJson();
    }

    private static String component() {
        return DocumentModelDraft.named("component").locale("en_US")
                .rootGroup("Component", c -> c.field("Description", FieldKind.STRING)
                        .group("Sub", s -> s.field("Code", FieldKind.STRING)))
                .build().toJson();
    }

    private static String host(boolean modelRoot) {
        MountOptions options = MountOptions.single();
        return DocumentModelDraft.named("permit").locale("en_US")
                .rootGroup("Permit", p -> p.mount("Details", "component", modelRoot ? options.asModelRoot() : options))
                .build().toJson();
    }

    /** {@code json} with the body of its root group (an empty {@code nested}) or of the named child group replaced. */
    private static String withBody(String json, String nested, String body) {
        ObjectNode root = (ObjectNode) read(json);
        ObjectNode group = (ObjectNode) root.at("/content/modelRoot/rootGroups/0");
        if (!nested.isEmpty()) {
            for (JsonNode element : group.path("Group").path("elements")) {
                if (nested.equals(element.path("name").asText())) {
                    group = (ObjectNode) element;
                }
            }
        }
        if (body.equals("missing")) {
            group.remove("Group");
        } else {
            group.set("Group", read(body));
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

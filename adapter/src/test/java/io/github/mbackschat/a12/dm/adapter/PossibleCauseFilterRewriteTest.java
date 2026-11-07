package io.github.mbackschat.a12.dm.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.core.PossibleCauseFilter;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.LocalizedText;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter;

/**
 * What a rename or move does to a rule's possible-cause filter, through the {@link ReferenceRewriter} round trip
 * every rename and move verb runs first. A12's own move support rewrites the condition and leaves the filter alone,
 * so an entry naming a moved entity would stop matching the condition and the verb's kernel gate would refuse it
 * ({@code MVK_REF_ERROR_ENTITY_IF_FILLED_NOT_REFERENCED}). An entry the move does not reach keeps its spelling, so a
 * move never turns an entry A12 refuses into one it admits. Own domain (insurance policies).
 */
class PossibleCauseFilterRewriteTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static String policy() {
        String json = DocumentModelDraft.named("policy-causes").locale("en_US")
                .rootGroup("Policy", p -> p
                        .numberField("Premium", f -> { })
                        .stringField("Holder", f -> { })
                        .group("Rider", r -> r.stringField("Code", f -> { }))
                        .group("Office", o -> o.stringField("Branch", f -> { })))
                .build().toJson();
        json = ModelWriter.addRule(json, rule("/Policy", "NeedsHolder", "Holder",
                "FieldFilled(Premium) And FieldNotFilled(Holder) And FieldFilled(Rider/Code)",
                new PossibleCauseFilter(List.of("Premium", "Rider/Code"), List.of("./Holder"))));
        return ModelWriter.addRule(json, rule("/Policy/Rider", "NeedsPremium", "Code",
                "FieldFilled(Code) And FieldNotFilled(../Premium)", new PossibleCauseFilter(List.of(), List.of("../Premium"))));
    }

    private static DraftRule rule(String group, String name, String errorField, String condition, PossibleCauseFilter filter) {
        return DraftRule.builder().groupPath(group).name(name).errorEntityRelPath("../" + errorField)
                .errorCondition(condition).errorCode(name).severity("ERROR")
                .messages(List.of(new LocalizedText("en_US", "Check " + name + "."))).possibleCauses(filter).build();
    }

    /** Each rule's filter as `name: ifFilled | ifNotFilled`, in model order. */
    private static List<String> filters(String json) throws Exception {
        List<String> out = new ArrayList<>();
        collect(JSON.readTree(json).path("content").path("modelRoot").path("rootGroups"), out);
        return out;
    }

    private static void collect(JsonNode elements, List<String> out) {
        for (JsonNode element : elements) {
            if ("Rule".equals(element.path("type").asText())) {
                JsonNode filter = element.path("Rule").path("filterForPossiblyCausingEntities");
                out.add(element.path("name").asText() + ": " + entries(filter.path("entitiesEnabledAsPossibleCauseIfFilled"))
                        + " | " + entries(filter.path("entitiesEnabledAsPossibleCauseIfNotFilled")));
            }
            collect(element.path("Group").path("elements"), out);
        }
    }

    private static String entries(JsonNode list) {
        List<String> paths = new ArrayList<>();
        list.forEach(entry -> paths.add(entry.path("relativePath").asText()));
        return String.join(",", paths);
    }

    @Test
    void aRenameOrMoveRewritesTheFilterEntriesItReaches() throws Exception {
        Map<String, List<String>> actual = new LinkedHashMap<>();
        actual.put("unchanged", filters(ReferenceRewriter.rewriteReferences(policy(), Map.of())));
        actual.put("a field renamed", filters(ReferenceRewriter.rewriteReferences(policy(),
                Map.of("/Policy/Premium", "/Policy/Amount"))));
        actual.put("a field moved into a group", filters(ReferenceRewriter.rewriteReferences(policy(),
                Map.of("/Policy/Premium", "/Policy/Office/Premium"))));
        actual.put("an entry's group renamed", filters(ReferenceRewriter.rewriteReferences(policy(),
                Map.of("/Policy/Rider", "/Policy/Cover"))));
        actual.put("the root renamed", filters(ReferenceRewriter.rewriteReferences(policy(),
                Map.of("/Policy", "/Contract"))));

        Map<String, List<String>> expected = new LinkedHashMap<>();
        expected.put("unchanged", List.of("NeedsPremium:  | ../Premium", "NeedsHolder: Premium,Rider/Code | ./Holder"));
        expected.put("a field renamed", List.of("NeedsPremium:  | ../Amount", "NeedsHolder: Amount,Rider/Code | ./Holder"));
        expected.put("a field moved into a group",
                List.of("NeedsPremium:  | ../Office/Premium", "NeedsHolder: Office/Premium,Rider/Code | ./Holder"));
        // The rule in Rider moves with its group, so its entry climbing out of it is unchanged.
        expected.put("an entry's group renamed", List.of("NeedsPremium:  | ../Premium", "NeedsHolder: Premium,Cover/Code | ./Holder"));
        expected.put("the root renamed", List.of("NeedsPremium:  | ../Premium", "NeedsHolder: Premium,Rider/Code | ./Holder"));
        assertEquals(expected, actual);
    }
}

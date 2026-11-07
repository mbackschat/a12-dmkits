package io.github.mbackschat.a12.dm.edit;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.PossibleCauses;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter;

/**
 * Edits keep the members of the elements they do not rewrite: kernel 31.3's `changeNote`, on any element, and a
 * rule's possible-cause filter, through an added field, a re-expressed condition and a new severity on the rule
 * that carries them.
 */
class EditSessionUnmodeledMemberTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void editsKeepTheChangeNotesAndTheFilterOfWhatTheyDoNotRewrite() throws Exception {
        String base = DocumentModelDraft.named("claims").locale("en_US")
                .rootGroup("Policy", p -> p.numberField("Premium", f -> { }).stringField("Holder", f -> { }))
                .build().toJson();
        GroupEditor policy = EditSession.read(base).group("/Policy");
        NumberRef premium = policy.num("Premium");
        String ruled = ModelWriter.addRule(base, policy.rule(r -> r.name("PremiumKnown").code("KNOWN")
                .errorField(premium).when(premium.filled()).possibleCauses(PossibleCauses.notFilled(premium.field()))
                .message(en("Enter the premium.")).build()));
        EditSession noted = EditSession.read(ruled);
        List.of("/Policy", "/Policy/Premium", "/Policy/PremiumKnown")
                .forEach(path -> noted.setChangeNote(path, "reviewed " + path.substring(path.lastIndexOf('/') + 1)));

        String edited = EditSession.read(noted.toJson())
                .addField("/Policy", "Broker", FieldKind.STRING)
                .modifyRule("/Policy/PremiumKnown", "FieldFilled(Premium) And [Premium] > 0")
                .modifyRuleSeverity("/Policy/PremiumKnown", Severity.WARNING)
                .toJson();

        JsonNode after = MAPPER.readTree(edited);
        assertEquals(List.of("reviewed Policy", "reviewed Premium", "reviewed PremiumKnown"),
                List.of("Policy", "Premium", "PremiumKnown").stream()
                        .map(name -> element(after, name).path("changeNote").asText()).toList());
        assertEquals(MAPPER.readTree("""
                {"entitiesEnabledAsPossibleCauseIfNotFilled": [{"relativePath": "Premium"}]}"""),
                element(after, "PremiumKnown").path("Rule").path("filterForPossiblyCausingEntities"));
    }

    private static JsonNode element(JsonNode model, String name) {
        return model.findParents("name").stream().filter(node -> node.path("name").asText().equals(name))
                .findFirst().orElseThrow();
    }
}

package io.github.mbackschat.a12.dm.cli.rule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * <b>A client-side refusal must not make a legal model UNREADABLE.</b> The standing rule for any refusal we
 * invent has two halves, and this is the sharper one: a check in a builder also runs when the adapter converts
 * a kernel model INBOUND, so an over-strict one turns a model the kernel accepts into a model dmtool cannot
 * show you — a far worse failure than refusing to author the same shape.
 *
 * <p>The shape under test is the known over-rejection: a rule whose error field sits OUTSIDE its group. Five
 * loci refuse to author it ({@code RK_ERROR_FIELD_OUTSIDE_GROUP}), deliberately and won't-fix, while the
 * kernel admits the pair whenever the rule does not ITERATE ([KF212]). So it is exactly a model a user can
 * hold and dmtool cannot write — the case where an unreadable outcome would be silent and permanent.
 *
 * <p>The fixture is hand-built for that reason: no dmtool verb can produce it, which is the point. Its
 * kernel-validity is asserted here rather than assumed, because a fixture the kernel rejects would make the
 * read succeed for the wrong reason.
 *
 * <p>This is the read-path arm of the enumeration recorded in CLI-CONFORMANCE-SPEC beside the AP8 gate: the
 * open question there was whether a builder pre-check could reach a read verb's prose branch. It cannot.
 */
class BuilderRefusedShapeStillReadsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A rule DECLARED in one fixed group whose error field lives in a sibling — unauthorable through dmtool. */
    private static Path relocatedRuleModel(Path dir) throws Exception {
        String json = DocumentModelDraft.named("Probe_DM").locale("en_US")
                .rootGroup("Probe", p -> p
                        .group("Store", s -> s.field("RangeTarget", FieldKind.STRING))
                        .group("Alpha", a -> a.field("AlphaOne", FieldKind.STRING)))
                .build().toJson();
        JsonNode root = MAPPER.readTree(json);
        var alpha = (com.fasterxml.jackson.databind.node.ObjectNode) locate(root, "/Probe/Alpha");
        var rule = alpha.withArray("elements").addObject();
        rule.put("type", "Rule");
        rule.put("id", "/Probe/Alpha/P");
        rule.put("name", "P");
        var body = rule.putObject("Rule");
        body.put("errorEntityRelPath", "../../Store/RangeTarget");
        body.put("errorCondition", "FieldNotFilled(/Probe/Store/RangeTarget)");
        body.put("errorCode", "P");
        body.put("severity", "ERROR");
        body.withArray("errorMessage").addObject().put("locale", "en_US").put("text", "needed");

        Path model = dir.resolve("Probe_DM.json");
        Files.writeString(model, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        return model;
    }

    /** The {@code Group} node at {@code id}, wherever the serializer nested it. */
    private static JsonNode locate(JsonNode node, String id) {
        if (node.isObject() && "Group".equals(node.path("type").asText()) && id.equals(node.path("id").asText())) {
            return node.get("Group");
        }
        for (JsonNode child : node) {
            JsonNode found = locate(child, id);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    @Test
    void aRuleDmtoolCannotAuthorIsStillReadableAndTheModelStillChecks(@TempDir Path dir) throws Exception {
        Path model = relocatedRuleModel(dir);

        CliTestSupport.Result checked = CliTestSupport.run("-m", model.toString(), "model", "check");
        JsonNode verdict = MAPPER.readTree(checked.out());
        assertTrue(verdict.path("valid").asBoolean(),
                () -> "the fixture must be KERNEL-legal or the read below proves nothing: " + checked.out());
        assertEquals("KERNEL_CONFIRMED", verdict.path("verification").asText(), checked::out);

        CliTestSupport.Result read = CliTestSupport.run("-m", model.toString(), "rule", "read", "/Probe/Alpha/P");
        assertEquals(0, read.code(), () -> "an over-strict authoring check must not make it unreadable: "
                + read.out() + read.err());
        JsonNode envelope = MAPPER.readTree(read.out());
        assertEquals("read", envelope.path("outcome").asText(), read::out);
        assertEquals("/Probe/Alpha/P", envelope.path("data").path("rule").asText(), read::out);
    }
}

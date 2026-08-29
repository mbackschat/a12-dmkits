package io.github.mbackschat.a12.dm.cli.field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * The BUILDER half of a spec verb's input gate rides the {@code rejected} envelope, like the schema half
 * beside it. A value the schema admits as well-typed but the kits will not build from — a fractional-digit
 * scale above the kernel's cap, an enum category that does not classify every value — used to be prose on
 * stderr with an empty stdout and exit 2, so one input answered in two contracts.
 *
 * <p><b>Why the whole class and not the reported verb.</b> a12-kernel-lean reported it on {@code field add}
 * (dmtool feedback, 2026-08-29); the same {@code catch (IllegalArgumentException) -> fail(...)} shape sat on
 * {@code field modify} and both {@code typedef} spec verbs, so all four are pinned here.
 *
 * <p><b>What made it worth fixing is not tidiness.</b> With no envelope there is no {@code source} either, so
 * a consumer recording the outcome cannot tell a local gate from a kernel verdict — the reporter came one step
 * from filing this one as a kernel gate. Hence the two assertions that carry the finding: {@code source} is
 * {@code PRECHECK}, and the message no longer attributes the refusal to the kernel that never saw it.
 */
class SpecValueRefusalEnvelopeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SCALE_ABOVE_CAP = """
            { "group": "/Store", "name": "Probe", "kind": "NUMBER",
              "number": { "maxFractionalDigits": 15 } }""";

    private static final String UNALIGNED_CATEGORY = """
            { "group": "/Store", "name": "Tier", "kind": "ENUM",
              "enum": { "values": [ {"value":"A"}, {"value":"B"} ],
                        "categories": [ {"name":"Grp", "values": ["A"] } ] } }""";

    private static Path storeModel(Path dir) throws Exception {
        Path model = dir.resolve("Store_DM.json");
        Files.writeString(model, DocumentModelDraft.named("Store_DM").locale("en_US")
                .rootGroup("Store", s -> s.field("Reference", FieldKind.STRING))
                .build().toJson());
        return model;
    }

    /** Runs one spec verb over a spec written to {@code name}, and returns its parsed envelope. */
    private static JsonNode reject(Path dir, Path model, String name, String spec, String... verbAndFlags)
            throws Exception {
        Path file = dir.resolve(name);
        Files.writeString(file, spec);
        String[] argv = new String[4 + verbAndFlags.length];
        argv[0] = "-m";
        argv[1] = model.toString();
        System.arraycopy(verbAndFlags, 0, argv, 2, verbAndFlags.length);
        argv[2 + verbAndFlags.length] = file.toString();
        argv[3 + verbAndFlags.length] = "--dry-run";
        CliTestSupport.Result result = CliTestSupport.run(argv);
        assertFalse(result.out().isBlank(),
                () -> "the refusal must reach STDOUT as the envelope, not stderr prose: " + result.err());
        assertEquals(1, result.code(),
                () -> "a rejection exits 1, not the 2 the plain fail returned: " + result.out() + result.err());
        return MAPPER.readTree(result.out());
    }

    /** The reported case, pinned whole: outcome, code, source, wording, and that nothing was written. */
    @Test
    void theReportedRefusalCarriesACodeAndNamesItselfAsLocal(@TempDir Path dir) throws Exception {
        JsonNode envelope = reject(dir, storeModel(dir), "cap.json", SCALE_ABOVE_CAP, "field", "add");
        JsonNode diagnostic = envelope.path("diagnostics").get(0);

        assertEquals("rejected", envelope.path("outcome").asText(), envelope::toString);
        assertEquals("RK_INVALID_VALUE", diagnostic.path("code").asText(), envelope::toString);
        assertEquals("PRECHECK", diagnostic.path("source").asText(),
                () -> "the source is what tells a consumer this is NOT a kernel verdict: " + envelope);
        assertTrue(diagnostic.path("summary").asText().contains("before the kernel is asked"),
                () -> "a local refusal must not attribute its rule to the kernel: " + envelope);
        assertFalse(envelope.path("written").asBoolean(), envelope::toString);
    }

    /** The class the reported case belongs to — every spec verb whose builder can refuse a well-typed value. */
    @Test
    void everySpecVerbAnswersInTheSameShape(@TempDir Path dir) throws Exception {
        Path model = storeModel(dir);
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("field add — scale above the cap",
                code(reject(dir, model, "a.json", SCALE_ABOVE_CAP, "field", "add")));
        actual.put("field add — unaligned enum category",
                code(reject(dir, model, "b.json", UNALIGNED_CATEGORY, "field", "add")));
        actual.put("field modify — scale above the cap",
                code(reject(dir, model, "c.json", """
                        { "group": "/Store", "name": "Reference", "kind": "NUMBER",
                          "number": { "minFractionalDigits": 15 } }""", "field", "modify")));
        actual.put("typedef add — scale above the cap",
                code(reject(dir, model, "d.json", """
                        { "kind": "NUMBER", "number": { "maxFractionalDigits": 15 } }""",
                        "typedef", "add", "--id", "Scale_TDM")));
        Path aligned = dir.resolve("aligned.json");
        Files.writeString(aligned, """
                { "kind": "ENUM", "enum": { "values": [ {"value":"A"}, {"value":"B"} ] } }""");
        CliTestSupport.Result declared = CliTestSupport.run("-m", model.toString(), "typedef", "add",
                "--id", "Tier_TDM", aligned.toString());
        assertEquals(0, declared.code(), declared::err);
        actual.put("typedef modify — unaligned enum category",
                code(reject(dir, model, "e.json", """
                        { "kind": "ENUM",
                          "enum": { "values": [ {"value":"A"}, {"value":"B"} ],
                                    "categories": [ {"name":"Grp", "values": ["A"] } ] } }""",
                        "typedef", "modify", "--id", "Tier_TDM")));

        Map<String, String> expected = new LinkedHashMap<>();
        for (String row : actual.keySet()) {
            expected.put(row, "RK_INVALID_VALUE");
        }
        assertEquals(expected, actual, "one input gate, one contract, whichever half of it refuses");
    }

    private static String code(JsonNode envelope) {
        return envelope.path("diagnostics").get(0).path("code").asText();
    }
}

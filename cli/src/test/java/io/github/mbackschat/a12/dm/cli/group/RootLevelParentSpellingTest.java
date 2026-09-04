package io.github.mbackschat.a12.dm.cli.group;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code --parent /} names the ROOT LEVEL in every add-verb that can create something there.
 *
 * <p><b>Why this exists.</b> A two-root model is the only shape that separates "model-wide" from "root-wide"
 * addressing, so authoring one gates a whole class of measurement — and the only route was the undocumented
 * empty string, while the spelling an author reaches for first failed with {@code no group at '/'}, a message
 * that reads as "your model has no root". a12-kernel-lean's agent concluded from exactly that pair that
 * multi-root models are unauthorable through this CLI (their instrument note, 2026-09-04), which is the sort of
 * unreachability claim a measurement is supposed to prevent.
 *
 * <p><b>The denominator is the point, not the reported verb.</b> Four verbs key the root level on an empty
 * {@code --parent} — {@code group add}, {@code include add}, {@code group multiselect} and
 * {@code group attachment} — and only the first was reported. Accepting the synonym in one of them would leave
 * the same dead end in three, so the normalization is single-sourced ({@code ModelPaths.rootLevelParent}) and
 * this test asserts the whole class rather than the instance.
 *
 * <p><b>Both directions.</b> The synonym must WORK, and the refusal for a parent that genuinely does not exist
 * must still refuse — while naming the root spelling, since the value an author most often gets wrong in this
 * slot is one that meant the root level.
 */
class RootLevelParentSpellingTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void everyAddVerbThatCanCreateAtTheRootLevelAcceptsTheSlashSpelling(@TempDir Path dir) throws Exception {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("group add", rootLevelAdd(dir, "one", "group", "add", "--name", "SecondRoot"));
        actual.put("group multiselect", rootLevelAdd(dir, "two", "group", "multiselect",
                "--name", "Choices", "--field", "Choice", "--max-rows", "3", "--values", "A,B"));
        actual.put("group attachment", rootLevelAdd(dir, "three", "group", "attachment", "--name", "Files"));
        actual.put("include add", rootLevelAdd(dir, "four", "include", "add", "--name", "Mounted",
                "--reference", "Other_DM", "--alias", "other"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("group add", "applied");
        expected.put("group multiselect", "applied");
        expected.put("group attachment", "applied");
        expected.put("include add", "applied");
        assertEquals(expected, actual, "the root-level synonym must reach every verb that keys on an empty parent");
    }

    /**
     * The {@code apply} op-record route keys the root level the same way.
     *
     * <p>It was missed by the first pass, which enumerated the standalone VERBS: {@code apply} reads
     * {@code parent} raw from the record, so `/` stayed a dead end on the one surface documented as being at
     * parity with those verbs, and the undocumented empty string remained its only route to a second root (a
     * cold review, 2026-09-04). {@code batch} needs nothing — it re-enters picocli with argv, so it inherits
     * the verbs' own normalization.
     */
    @Test
    void theApplyRouteKeysTheRootLevelOnTheSameSpelling(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        Path ops = Files.writeString(dir.resolve("ops.json"), """
                [ {"target":"group","op":"add","parent":"/","name":"SecondRoot"} ]""");

        var res = CliTestSupport.run("-m", model.toString(), "apply", ops.toString());

        assertEquals(0, res.code(), res.err() + res.out());
        JsonNode record = M.readTree(res.out()).path("data").path("results").get(0);
        assertEquals("/SecondRoot", record.path("changed").path("added").asText(),
                () -> "the op-record route must report the root-level target, not `//SecondRoot`: " + record);
        assertTrue(Files.readString(model).contains("SecondRoot"), "and persist the second root");
    }

    /**
     * `field add --parent /` is still refused — a field cannot be a root element — but with the corrective that
     * says so, not the generic "no group at '/'". This verb's help calls its `--parent` the alias matching every
     * sibling add-verb, and those now accept `/`, so the generic message would read as an inconsistent tool.
     */
    @Test
    void fieldAddRefusesTheRootLevelWithACorrectiveThatExplainsWhy(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        var res = CliTestSupport.run("-m", model.toString(),
                "field", "add", "--parent", "/", "--name", "Stray", "--kind", "STRING");

        assertEquals(2, res.code(), res.out());
        String fix = M.readTree(res.out()).path("diagnostics").get(0).path("fix").asText();
        assertTrue(fix.contains("a field always lives inside a group"),
                () -> "the corrective must name the real situation rather than a missing group: " + fix);
    }

    /**
     * The EMPTY STRING is the other root-level spelling, and it is asserted here because two surfaces now cite
     * this class for it — every add-verb's `--parent` help and {@code ParamKind}'s {@code entityRef} note. It
     * was the undocumented original route; `/` was added beside it, so a regression that dropped the empty form
     * would break the spelling those texts still name (a cold review, 2026-09-04).
     */
    @Test
    void theEmptyStringNamesTheRootLevelToo(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);

        var res = CliTestSupport.run("-m", model.toString(), "group", "add", "--parent", "", "--name", "SecondRoot");

        assertEquals(0, res.code(), res.err() + res.out());
        assertEquals("/SecondRoot", M.readTree(res.out()).path("changed").path("added").asText(),
                "the empty parent must key the root level exactly as `/` does");
        assertTrue(Files.readString(model).contains("SecondRoot"), "and persist the second root");
    }

    @Test
    void aParentThatGenuinelyDoesNotExistIsStillRefusedAndIsToldTheRootSpelling(@TempDir Path dir)
            throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        var res = CliTestSupport.run("-m", model.toString(),
                "group", "add", "--parent", "/NoSuchGroup", "--name", "X");

        assertEquals(2, res.code(), res.out());
        JsonNode diagnostic = M.readTree(res.out()).path("diagnostics").get(0);
        assertEquals("RK_NO_SUCH_GROUP", diagnostic.path("code").asText());
        assertTrue(diagnostic.path("fix").asText().contains("ROOT level"),
                () -> "the corrective must name the root-level spelling: " + diagnostic.path("fix").asText());
        assertTrue(Files.readString(model).contains("Order"), "a refused add writes nothing");
    }

    /**
     * Run one add-verb with {@code --parent /} on its own copy of the fixture; returns the envelope outcome.
     *
     * <p>Each slot gets its own directory because {@code include add} resolves its {@code --reference} from the
     * model's own folder, so the mountable model has to sit beside the host — and a shared directory would let
     * one verb's write reach another's fixture.
     */
    private static String rootLevelAdd(Path dir, String slot, String... verbAndArgs) throws Exception {
        Path slotDir = Files.createDirectories(dir.resolve(slot));
        Path model = CliTestSupport.orderRuled(slotDir);
        Path mountablePath = slotDir.resolve("Other_DM.json");
        var mountable = CliTestSupport.run("model", "new", "--id", "Other_DM", "--locale", "en_US",
                "--root", "Other", "-o", mountablePath.toString());
        assertEquals(0, mountable.code(), mountable.err());
        // An include of a model whose root group is EMPTY is refused by the kernel's own expansion under
        // includeLevel=SINGLE_RG, so the mountable model needs content before the mount can be about --parent.
        var content = CliTestSupport.run("-m", mountablePath.toString(),
                "field", "add", "--parent", "/Other", "--name", "Note", "--kind", "STRING");
        assertEquals(0, content.code(), content.err());
        String[] args = new String[verbAndArgs.length + 4];
        args[0] = "-m";
        args[1] = model.toString();
        System.arraycopy(verbAndArgs, 0, args, 2, verbAndArgs.length);
        args[args.length - 2] = "--parent";
        args[args.length - 1] = "/";
        var res = CliTestSupport.run(args);
        assertEquals(0, res.code(), () -> String.join(" ", args) + " -> " + res.err() + res.out());

        // The envelope's outcome alone does not say a SECOND ROOT was created, which is the whole point —
        // the normalization feeds the reported path, so an un-normalized `/` would still apply and report
        // `//Name`. Both the reported path and the persisted model are checked.
        JsonNode envelope = M.readTree(res.out());
        String reported = envelope.path("changed").path(reportedPathKey(verbAndArgs[0])).asText();
        String expected = "/" + newName(verbAndArgs);
        assertEquals(expected, reported,
                () -> "the root-level target must be reported as " + expected + ": " + envelope.path("changed"));
        assertTrue(Files.readString(model).contains("\"" + newName(verbAndArgs) + "\""),
                "the new root element must be persisted in the model");
        return envelope.path("outcome").asText();
    }

    /** Which `changed` key carries the created element's path — `include add` reports its mount separately. */
    private static String reportedPathKey(String target) {
        return "include".equals(target) ? "mountedAt" : "added";
    }

    /** The `--name` value the verb was given, which is the root-level element's own name. */
    private static String newName(String... verbAndArgs) {
        for (int i = 0; i < verbAndArgs.length - 1; i++) {
            if ("--name".equals(verbAndArgs[i])) {
                return verbAndArgs[i + 1];
            }
        }
        throw new IllegalArgumentException("every add-verb under test takes --name: " + String.join(" ", verbAndArgs));
    }
}

package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * A model-editing verb ends through {@link BaseCommand#emitMutation}, not through its own copy of the tail.
 *
 * <p><b>Why this is guarded rather than trusted.</b> The fragment was hand-written thirty-three times and did not
 * stay identical — it stayed identical until someone edited one of them. Threading the gate receipt had to visit
 * every copy; before that, the sibling gate-rejection mapping had drifted into three versions, each having lost
 * something different, and the branch all three got wrong was the one carrying no notifications
 * (docs/archived/RESULT-CONTRACT-PROPOSAL.md §1d item 19). Collapsing the duplication removes today's drift; only a guard stops it
 * growing back one command at a time, which is exactly how it arrived.
 *
 * <p>The check is a source scan, which is the weaker kind of evidence — it sees what is written, not what runs. It
 * is the right kind here anyway, because the property <em>is</em> about the source: "no second copy of this
 * exists". The behaviour that second copy would get wrong is covered separately by
 * {@link MutationWriteFailureTest} and {@link MutationEvidenceTest}.
 *
 * <p>{@link #WRITES_ITS_OWN_TAIL} is the reasoned exemption, and each row states why the shared ending does not
 * fit. A new verb is in scope automatically: it is neither exempt nor using the helper, so it fails here.
 */
class MutationTailSingleSourceTest {

    private static final Path COMMANDS = Path.of("src/main/java/io/github/mbackschat/a12/dm/cli");

    /** The write call every collapsed verb used to make for itself. */
    private static final String OWN_WRITE = "Files.writeString(";

    /**
     * Verbs that legitimately write outside {@code emitMutation}, with the reason the shared ending cannot serve
     * them. Every row is checked to be real, so a stale exemption fails rather than quietly widening the hole.
     */
    private static final Map<String, String> WRITES_ITS_OWN_TAIL = Map.of(
            "BaseCommand.java", "the shared ending itself",
            "PatternsCommand.java", "four routes, two of which are a verdict and a rejection rather than a write",
            "group/GroupExtractCommand.java", "writes TWO artifacts — the slimmed base and the extracted sub-model",
            "ApplyCommand.java", "one transaction envelope over a staged sequence, not a single edit",
            "ExportCommand.java", "renders an artifact, not an edited model; no --dry-run",
            "profile/ProfileSynthesizeCommand.java", "writes a synthesized artifact; no --dry-run",
            "profile/ProfileExtractCommand.java", "writes a profile artifact; no --dry-run",
            "model/ModelExpandCommand.java", "writes an expanded artifact; no --dry-run",
            "model/ModelNewCommand.java", "originates a model rather than editing one; no --dry-run",
            "model/ModelImportJsonschemaCommand.java", "transcodes an external schema; no --dry-run");

    /** The one exemption that needs a second key, since two commands share the import/export pair's shape. */
    private static final String EXPORT_JSONSCHEMA = "model/ModelExportJsonschemaCommand.java";

    @Test
    void noVerbHandWritesTheMutationTailAgain() throws Exception {
        List<String> offenders = new ArrayList<>();
        try (var paths = Files.walk(COMMANDS)) {
            for (Path p : paths.filter(x -> x.toString().endsWith(".java")).toList()) {
                String key = COMMANDS.relativize(p).toString();
                if (WRITES_ITS_OWN_TAIL.containsKey(key) || EXPORT_JSONSCHEMA.equals(key)) {
                    continue;
                }
                if (Files.readString(p).contains(OWN_WRITE)) {
                    offenders.add(key);
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                () -> "these verbs write the model themselves instead of ending through BaseCommand.emitMutation: "
                        + offenders + ". The tail (destination resolution, the write, the failure text, the "
                        + "envelope) is single-sourced precisely because thirty-three hand-written copies drifted; "
                        + "use ModelWrite, or add a reasoned row to WRITES_ITS_OWN_TAIL");
    }

    /** Every exemption names a file that exists and really does write for itself — no stale rows. */
    @Test
    void theExemptionLedgerIsHonest() throws Exception {
        for (var entry : WRITES_ITS_OWN_TAIL.entrySet()) {
            Path p = COMMANDS.resolve(entry.getKey());
            assertTrue(Files.exists(p), () -> "WRITES_ITS_OWN_TAIL names a file that is gone: " + entry.getKey());
            assertFalse(entry.getValue().isBlank(), () -> entry.getKey() + " needs a reason");
            assertTrue(Files.readString(p).contains(OWN_WRITE),
                    () -> entry.getKey() + " no longer writes for itself — drop the exemption rather than "
                            + "leaving it to cover a future regression");
        }
    }

    /** The collapse actually happened: the shared ending is used across the surface, not by one or two verbs. */
    @Test
    void theSharedEndingCarriesTheEditingSurface() throws Exception {
        int users = 0;
        try (var paths = Files.walk(COMMANDS)) {
            for (Path p : paths.filter(x -> x.toString().endsWith(".java")).toList()) {
                if (Files.readString(p).contains("ModelWrite.of(")) {
                    users++;
                }
            }
        }
        assertEquals(33, users,
                "every model-editing verb ends through the shared tail; change this number only alongside a verb");
    }
}

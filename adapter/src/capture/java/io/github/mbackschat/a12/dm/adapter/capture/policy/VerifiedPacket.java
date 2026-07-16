package io.github.mbackschat.a12.dm.adapter.capture.policy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.mbackschat.a12.dm.adapter.capture.json.Digests;
import io.github.mbackschat.a12.dm.adapter.capture.json.Json;
import io.github.mbackschat.a12.dm.adapter.capture.packet.CaptureRefusedException;
import io.github.mbackschat.a12.dm.adapter.capture.packet.Receipt;
import io.github.mbackschat.a12.dm.adapter.capture.schema.ScenarioSet;
import io.github.mbackschat.a12.dm.adapter.capture.schema.SchemaIds;

/**
 * An IMMUTABLE packet, verified in two layers before any policy runs (proposal §5/§7; audit Finding 2):
 * BYTE integrity — the receipt file's SHA-256 must equal the supplied out-of-band handoff value and the
 * live inventory must equal the stored receipt exactly (additions, removals, renames, byte drift,
 * symlinks, and unclassifiable strays all refuse) — then the closed RELATIONAL validation
 * ({@link PacketRelations}): the envelope must DESCRIBE exactly the artifacts, registry echoes, and
 * copied request the packet contains. Verification READS this view and never writes into the packet.
 */
public final class VerifiedPacket {

    private final Path dir;
    private final Receipt receipt;
    private final String receiptSha256;
    private final Json.Obj envelope;
    private final ScenarioSet request;
    private final Map<String, Map<String, Json.Obj>> observationsByCase;

    private VerifiedPacket(Path dir, Receipt receipt, String receiptSha256, Json.Obj envelope,
            ScenarioSet request, Map<String, Map<String, Json.Obj>> observationsByCase) {
        this.dir = dir;
        this.receipt = receipt;
        this.receiptSha256 = receiptSha256;
        this.envelope = envelope;
        this.request = request;
        this.observationsByCase = observationsByCase;
    }

    public static VerifiedPacket load(Path dir, String expectedReceiptSha256) {
        byte[] receiptBytes = read(dir.resolve(Receipt.FILE_NAME));
        String actualSha = Digests.sha256(receiptBytes);
        if (!actualSha.equals(expectedReceiptSha256)) {
            throw new CaptureRefusedException("packet integrity: RECEIPT.json digest " + actualSha
                    + " does not match the supplied out-of-band digest " + expectedReceiptSha256);
        }
        Receipt stored = Receipt.fromJson(Json.parse(receiptBytes));
        Receipt live = Receipt.over(dir);
        if (!stored.equals(live)) {
            throw new CaptureRefusedException("packet integrity: the live inventory drifted from RECEIPT.json"
                    + " (an added, removed, renamed, or byte-drifted artifact) — the packet is not the one the"
                    + " receipt binds");
        }

        Json parsedEnvelope = Json.parse(read(dir.resolve("PACKET.json")));
        if (!(parsedEnvelope instanceof Json.Obj envelope)) {
            throw new CaptureRefusedException("packet integrity: PACKET.json is not an object");
        }
        if (!(envelope.members().get("schema") instanceof Json.Str schema)
                || !SchemaIds.EVIDENCE_PACKET.equals(schema.value())) {
            throw new CaptureRefusedException("packet integrity: unknown envelope schema — this verifier"
                    + " supports exactly \"" + SchemaIds.EVIDENCE_PACKET + "\" (unknown versions are rejected)");
        }

        // Bytes established (the receipt), the CLOSED RELATIONAL validator establishes that the envelope
        // DESCRIBES those bytes: every claimed association verified, every artifact accounted for exactly
        // once, unknown nested members refused (audit Finding 2).
        PacketRelations.Relations relations = PacketRelations.check(dir, envelope, stored);
        return new VerifiedPacket(dir, stored, actualSha, envelope, relations.request(),
                relations.observationsByCase());
    }

    public Path dir() {
        return dir;
    }

    public Receipt receipt() {
        return receipt;
    }

    public String receiptSha256() {
        return receiptSha256;
    }

    public ScenarioSet request() {
        return request;
    }

    public boolean developmentMode() {
        return ((Json.Bool) capture().members().get("developmentMode")).value();
    }

    public String workingTree() {
        return str(capture(), "workingTree");
    }

    public String sourceRevision() {
        return str(capture(), "sourceRevision");
    }

    /** The envelope-bound digest of the copied request bytes ({@code scenario.requestSha256}). */
    public String requestSha256() {
        return str((Json.Obj) envelope.members().get("scenario"), "requestSha256");
    }

    /** Every model ref → legality verdict, from the envelope. */
    public Map<String, String> legalityVerdicts() {
        Map<String, String> verdicts = new LinkedHashMap<>();
        for (Json entry : arr(envelope, "legality")) {
            Json.Obj obj = (Json.Obj) entry;
            verdicts.put(str(obj, "ref"), str(obj, "verdict"));
        }
        return verdicts;
    }

    /** Every case's envelope entry (caseId, modelRef, legality, executed, observation refs). */
    public List<Json> caseEntries() {
        return arr(envelope, "cases");
    }

    /** The parsed observation artifact of {@code caseId} × {@code runnerWire}, or null when absent. */
    public Json.Obj observation(String caseId, String runnerWire) {
        Map<String, Json.Obj> byRunner = observationsByCase.get(caseId);
        return byRunner == null ? null : byRunner.get(runnerWire);
    }

    private Json.Obj capture() {
        return (Json.Obj) envelope.members().get("capture");
    }

    private static List<Json> arr(Json.Obj obj, String member) {
        return ((Json.Arr) obj.members().get(member)).items();
    }

    static String str(Json.Obj obj, String member) {
        return ((Json.Str) obj.members().get(member)).value();
    }

    private static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read " + file, e);
        }
    }
}

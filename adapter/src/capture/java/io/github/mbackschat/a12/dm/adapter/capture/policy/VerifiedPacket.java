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
import io.github.mbackschat.a12.dm.adapter.capture.schema.ScenarioDecoder;
import io.github.mbackschat.a12.dm.adapter.capture.schema.ScenarioSet;
import io.github.mbackschat.a12.dm.adapter.capture.schema.SchemaIds;

/**
 * An IMMUTABLE packet, integrity-verified against the out-of-band receipt digest before any policy runs
 * (proposal §5/§7): the receipt file's SHA-256 must equal the supplied handoff value, the live inventory
 * must equal the stored receipt exactly (additions, removals, renames, byte drift, symlinks, and
 * unclassifiable strays all refuse), the envelope must parse CLOSED under a known schema id, and every
 * envelope artifact reference must agree with the receipt. Verification READS this view and never writes
 * into the packet.
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

    /** The envelope's closed top-level member set (exactly what {@code PacketWriter} emits). */
    private static final List<String> ENVELOPE_MEMBERS = List.of("schema", "operationSchema", "capabilities",
            "kernel", "capture", "scenario", "world", "runners", "inputs", "legality", "cases", "command");

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
        for (String member : envelope.members().keySet()) {
            if (!ENVELOPE_MEMBERS.contains(member)) {
                throw new CaptureRefusedException("packet integrity: unknown envelope member \"" + member
                        + "\" — " + SchemaIds.EVIDENCE_PACKET + " is closed");
            }
        }

        ScenarioSet request = ScenarioDecoder.decode(Json.parse(read(dir.resolve("request/scenarios.json"))));

        Map<String, Map<String, Json.Obj>> observations = new LinkedHashMap<>();
        for (Json caseEntry : arr(envelope, "cases")) {
            Json.Obj caseObj = (Json.Obj) caseEntry;
            String caseId = str(caseObj, "caseId");
            Map<String, Json.Obj> byRunner = new LinkedHashMap<>();
            for (Json observationRef : ((Json.Arr) caseObj.members().get("observations")).items()) {
                Json.Obj ref = (Json.Obj) observationRef;
                String artifact = str(ref, "artifact");
                byte[] bytes = read(dir.resolve(artifact));
                if (!Digests.sha256(bytes).equals(str(ref, "sha256"))) {
                    throw new CaptureRefusedException("packet integrity: envelope digest mismatch for "
                            + artifact);
                }
                byRunner.put(str(ref, "runner"), (Json.Obj) Json.parse(bytes));
            }
            observations.put(caseId, byRunner);
        }
        return new VerifiedPacket(dir, stored, actualSha, envelope, request, observations);
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

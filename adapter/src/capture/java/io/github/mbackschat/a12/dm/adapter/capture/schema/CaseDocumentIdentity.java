package io.github.mbackschat.a12.dm.adapter.capture.schema;

import java.util.List;
import java.util.Optional;

import io.github.mbackschat.a12.dm.adapter.capture.dto.ConsumedDocument;
import io.github.mbackschat.a12.dm.adapter.capture.json.Digests;
import io.github.mbackschat.a12.dm.adapter.capture.json.Json;

/**
 * THE canonical case-document identity (the audit's Finding 3): one algorithm — canonical JSON of the
 * placement stream ({@code kind}/{@code path}/{@code reps}/{@code value}), hashed — used on BOTH sides of
 * the binding. The request side ({@link #ofCase}) computes the SUPPLIED identity from the declared
 * placements; each runner computes the CONSUMED identity through a {@link Recorder} threaded into its own
 * document-building loop, recording each placement AS it is applied to the engine (the recorder call
 * shares the exact locals the engine call consumes — never a copy of the request). The verifier recomputes
 * the supplied identity from the packet's copied request and requires supplied == consumed (v1 adapts
 * nothing), so a placement-to-document adaptation defect — a dropped, reordered, or value-changed
 * placement — breaks the equality even when it hits both kernel routes identically.
 */
public final class CaseDocumentIdentity {

    private CaseDocumentIdentity() {
    }

    /** The SUPPLIED identity: the canonical digest of the case's declared placement stream. */
    public static ConsumedDocument ofCase(ScenarioSet.ScenarioCase scenarioCase) {
        Recorder recorder = recorder();
        for (ScenarioSet.Placement p : scenarioCase.placements()) {
            switch (p.kind()) {
                case GROUP -> recorder.group(p.path(), p.reps());
                case FIELD -> recorder.field(p.path(), p.reps(), p.value().orElseThrow());
                case EMPTY -> recorder.empty(p.path(), p.reps());
            }
        }
        return recorder.identity();
    }

    public static Recorder recorder() {
        return new Recorder();
    }

    /** Collects the applied placement stream in application order and canonicalizes it. */
    public static final class Recorder {
        private final Json.Arr placements = Json.Arr.of();
        private int count;

        private Recorder() {
        }

        public void group(String path, List<Integer> reps) {
            add("GROUP", path, reps, Optional.empty());
        }

        public void field(String path, List<Integer> reps, String value) {
            add("FIELD", path, reps, Optional.of(value));
        }

        public void empty(String path, List<Integer> reps) {
            add("EMPTY", path, reps, Optional.empty());
        }

        private void add(String kind, String path, List<Integer> reps, Optional<String> value) {
            Json.Arr indices = Json.Arr.of();
            reps.forEach(r -> indices.add(new Json.Int(r)));
            Json.Obj placement = Json.Obj.of()
                    .put("kind", kind)
                    .put("path", path)
                    .put("reps", indices);
            value.ifPresent(v -> placement.put("value", v));
            placements.add(placement);
            count++;
        }

        public ConsumedDocument identity() {
            return new ConsumedDocument(count, Digests.sha256(Json.canonicalBytes(placements)));
        }
    }
}

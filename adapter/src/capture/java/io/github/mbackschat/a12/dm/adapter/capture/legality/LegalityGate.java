package io.github.mbackschat.a12.dm.adapter.capture.legality;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.mbackschat.a12.dm.adapter.KernelConsistencyOracle;
import io.github.mbackschat.a12.dm.adapter.capture.json.Digests;
import io.github.mbackschat.a12.dm.adapter.capture.json.Json;
import io.github.mbackschat.a12.dm.adapter.capture.schema.ScenarioSets;
import io.github.mbackschat.a12.dm.adapter.capture.schema.SchemaIds;
import io.github.mbackschat.a12.dm.core.Notification;
import io.github.mbackschat.a12.dm.core.ReferenceResolvers;
import io.github.mbackschat.a12.dm.core.Severity;

/**
 * LEGALITY BEFORE RUNTIME (proposal §6): the kernel's authoring/consistency acceptance surface — the same
 * {@code checkConsistency} gate {@code RuleValidator}/{@code model check} route through, applied
 * model-level via the adapter's {@code ConsistencyOracle} — runs before any scenario executes. The result
 * is recorded as provenance with BOTH the supplied and adapted digests bound (v1 adapts nothing, so they
 * are recorded explicitly equal — the gate and the runtime consume the same exact bytes). An illegal model
 * yields a characterization artifact describing the rejection; it is never executed as runtime evidence.
 *
 * <p>The artifact carries severity + extracted diagnostic code + a SHA-256 of each notification text —
 * never the kernel's message text itself (German-only, EUPL — the IF3 rule), so drift stays detectable
 * without persisting kernel expression.
 */
public final class LegalityGate {

    private static final Pattern DIAGNOSTIC_CODE = Pattern.compile("\\b(?:MVK|RK)_[A-Z0-9_]+\\b");

    /** One model's legality result: the verdict plus the closed artifact JSON. */
    public record Result(String modelRef, boolean legal, Json artifact) {
    }

    private LegalityGate() {
    }

    /** Gate {@code modelRef}'s exact bytes through the kernel consistency surface. */
    public static Result check(String modelRef, ScenarioSets.ModelBytes model, String kernelVersion) {
        String dmJson = new String(model.bytes(), StandardCharsets.UTF_8);
        List<Notification> notifications;
        try {
            notifications = new KernelConsistencyOracle()
                    .checkConsistency(dmJson, ReferenceResolvers.selfContained());
        } catch (RuntimeException conversionFailure) {
            // The kernel can throw during conversion BEFORE reporting a structured problem (the same
            // foreseeable-collision class RuleValidator.checkDraft closes) — an unconvertible model is
            // ILLEGAL, characterized without persisting the exception's kernel text.
            notifications = List.of(new Notification(Severity.ERROR,
                    "model conversion failed before consistency reporting: "
                            + conversionFailure.getClass().getSimpleName()));
        }
        boolean legal = notifications.stream().noneMatch(n -> n.severity() == Severity.ERROR);

        Json.Arr entries = Json.Arr.of();
        for (Notification n : notifications) {
            Json.Obj entry = Json.Obj.of().put("severity", n.severity().name());
            Matcher code = DIAGNOSTIC_CODE.matcher(n.message());
            if (code.find()) {
                entry.put("code", code.group());
            } else {
                entry.put("code", Json.Null.NULL);
            }
            entry.put("textSha256", Digests.sha256(n.message().getBytes(StandardCharsets.UTF_8)));
            entries.add(entry);
        }

        Json artifact = Json.Obj.of()
                .put("schema", SchemaIds.LEGALITY)
                .put("validator", Json.Obj.of()
                        .put("runner", "kernel-checkConsistency")
                        .put("kernelVersion", kernelVersion))
                .put("modelRef", modelRef)
                .put("suppliedSha256", model.sha256())
                .put("adaptedSha256", model.sha256())   // v1 adapts nothing — both digests bound, explicitly equal
                .put("verdict", legal ? "legal" : "illegal")
                .put("notifications", entries);
        return new Result(modelRef, legal, artifact);
    }
}

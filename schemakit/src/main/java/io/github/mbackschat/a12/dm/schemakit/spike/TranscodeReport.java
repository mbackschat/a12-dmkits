package io.github.mbackschat.a12.dm.schemakit.spike;

import java.util.List;

/**
 * The audit trail of one transcode (SCHEMAKIT-SPEC §4/§5; JSON-SCHEMA-PROPOSAL §9.1): every element's
 * disposition and every non-obvious decision, so the inherently lossy, choice-driven mapping is
 * <em>auditable, never silent</em>. It is first-class output — returned as a value (not logging) and
 * asserted against — so it doubles as the spike's own evidence: a report that accurately states every
 * disposition <em>is</em> the proof the framework works.
 */
public record TranscodeReport(List<Disposition> dispositions, List<String> diagnostics) {

    public TranscodeReport {
        dispositions = List.copyOf(dispositions);
        diagnostics = List.copyOf(diagnostics);
    }

    /** One source element → its target, classified by the tier it crossed in. */
    public record Disposition(String source, String target, Tier tier) {}

    /**
     * The project-or-carry taxonomy (SCHEMAKIT-SPEC §4): a structural mapping, a native-keyword projection
     * (Tier A / synthesized Tier A+), opaque {@code x-a12-*} carriage (CARRIED rule / COMPUTATION), or a
     * refusal under the gate-and-refuse discipline.
     */
    public enum Tier { STRUCTURE, TIER_A, TIER_A_PLUS, CARRIED, COMPUTATION, REFUSED }
}

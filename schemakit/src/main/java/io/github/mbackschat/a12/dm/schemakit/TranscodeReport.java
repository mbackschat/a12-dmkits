package io.github.mbackschat.a12.dm.schemakit;

import java.util.ArrayList;
import java.util.List;

/**
 * The audit trail of one transcode (SCHEMAKIT-SPEC §4/§5; JSON-SCHEMA-PROPOSAL §9.1): every element's
 * disposition and every non-obvious decision, so the inherently lossy, choice-driven mapping is
 * <em>auditable, never silent</em>. It is first-class output — returned as a value (not logging) and
 * asserted against — so it doubles as the transcoder's own evidence: a report that accurately states every
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
     * (Tier A / synthesized Tier A+), opaque {@code x-a12-*} carriage (CARRIED rule / COMPUTATION), or an
     * {@code OMITTED} element. <b>Import never blocks</b> (owner directive, 2026-06-28): a feature a12 cannot
     * represent is OMITTED — skipped with a corrective to-do in the report — so the import still yields a
     * usable, kernel-valid DM skeleton the user completes by hand. Omission is loud, never silent.
     */
    public enum Tier { STRUCTURE, TIER_A, TIER_A_PLUS, CARRIED, COMPUTATION, OMITTED }

    public static Builder builder() {
        return new Builder();
    }

    /** Accumulates dispositions + diagnostics during a transcode; one shared by importer and exporter. */
    public static final class Builder {
        private final List<Disposition> dispositions = new ArrayList<>();
        private final List<String> diagnostics = new ArrayList<>();

        public Builder mapped(String source, String target, Tier tier) {
            dispositions.add(new Disposition(source, target, tier));
            return this;
        }

        public Builder structure(String source, String target) {
            return mapped(source, target, Tier.STRUCTURE);
        }

        /** Omit an element a12 can't represent: skip it (import proceeds) and record a corrective to-do. */
        public Builder omit(String source, String corrective) {
            dispositions.add(new Disposition(source, "<omitted>", Tier.OMITTED));
            diagnostics.add(corrective);
            return this;
        }

        public Builder diagnostic(String message) {
            diagnostics.add(message);
            return this;
        }

        public TranscodeReport build() {
            return new TranscodeReport(dispositions, diagnostics);
        }
    }
}

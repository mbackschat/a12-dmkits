package io.github.mbackschat.a12.rulekit.cli;

import java.util.Optional;

/**
 * The agent-facing dump artifacts (finding 13). Three JSONL (one self-contained JSON object per
 * line — {@code grep}/{@code jq} both work) plus the read-whole markdown skeleton. See CLI-SPEC §5.
 */
enum Artifact {

    FIELDS("fields", "fields.jsonl"),
    GROUPS("groups", "groups.jsonl"),
    RULES("rules", "rules.jsonl"),
    MODEL_CARD("card", "model-card.md");

    final String arg;
    final String fileName;

    Artifact(String arg, String fileName) {
        this.arg = arg;
        this.fileName = fileName;
    }

    /** Resolve the positional artifact argument ({@code fields}/{@code groups}/{@code rules}/{@code card}). */
    static Optional<Artifact> fromArg(String arg) {
        for (Artifact a : values()) {
            if (a.arg.equals(arg)) {
                return Optional.of(a);
            }
        }
        return Optional.empty();
    }
}

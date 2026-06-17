package io.github.mbackschat.a12.dm.cli;

import java.util.List;

import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;

/**
 * Why a refactor was refused (CLI-SPEC §6 safety gate): a one-line {@code summary}, the structured
 * {@code data} that lets an agent act (e.g. the referrers under {@code data.referencedBy}), and the
 * corrective {@link Diagnostic} (detect → locate → repair). A {@link SafetyCheck} yields a Refusal
 * <em>instead of</em> mutating; {@link BaseCommand#refuse} turns it into a {@code refused} envelope.
 */
public record Refusal(String summary, ObjectNode data, Diagnostic diagnostic) {

    List<Diagnostic> diagnostics() {
        return List.of(diagnostic);
    }
}

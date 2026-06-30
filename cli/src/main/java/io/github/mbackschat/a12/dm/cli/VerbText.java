package io.github.mbackschat.a12.dm.cli;

import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A per-verb compact renderer for the {@code --text} body of a result envelope's {@code data} (LEDGER
 * MR5). {@link TextRenderer} dispatches on the envelope's {@code (target, op)} discriminator: a verb with
 * a bespoke view (e.g. {@code model diff}'s risk-sorted change list) renders here, and every other verb
 * falls back to the generic indented tree — so specializing one verb never touches the rest.
 *
 * <p>Kept out of {@link TextRenderer} on purpose: the boundary stays generic and consults this small
 * registry, rather than growing a per-verb switch (the smell MODEL-REVIEW-LEDGER MR5 calls out).
 */
interface VerbText {

    /** Render the envelope's {@code data} as a compact human-readable body (no leading {@code data:} label). */
    String render(JsonNode data);

    /** The registry, keyed by {@code "<target> <op>"}. */
    Map<String, VerbText> REGISTRY = Map.of(
            "model diff", new DiffText(),
            "model report", new ReportText());

    /** The bespoke renderer for {@code (target, op)}, if one is registered. */
    static Optional<VerbText> forVerb(String target, String op) {
        if (target == null || op == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(REGISTRY.get(target + " " + op));
    }
}

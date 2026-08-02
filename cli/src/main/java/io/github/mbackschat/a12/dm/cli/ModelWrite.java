package io.github.mbackschat.a12.dm.cli;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;

/**
 * One completed model edit, ready to be reported — what changed, the JSON to persist, and who cleared it.
 *
 * <p><b>Why a value object rather than a helper's parameter list.</b> Thirty-three verbs end the same way: preview
 * under {@code --dry-run}, else resolve the destination, write, and emit an {@code applied} envelope. Collapsing
 * that into one method means passing ten values, of which four are {@code String} and two are {@code boolean} —
 * exactly the silently-swap-prone shape the project's long-parameter-list rule forbids, and the shape whose
 * {@code boolean valid} neighbour produced the fabricated verdicts this whole contract exists to remove. So the
 * values are named at the call site and travel as one thing, the way {@code ApplyCommand.Terminal} does.
 *
 * <p><b>Why collapse it at all.</b> The duplication is the kind that has already cost twice. Threading the gate
 * receipt had to visit every copy; before that, the gate-rejection mapping lived in three places and each copy had
 * lost something different — and the branch they all got wrong was the one carrying no notifications, because a
 * branch with nothing in it is the branch nobody notices (docs/archived/RESULT-CONTRACT-PROPOSAL.md §1d item 19). A fragment
 * repeated thirty-three times does not stay identical; it stays identical until someone edits one of them.
 *
 * <p><b>What deliberately stays at the call site:</b> {@code --dry-run} and {@code --output}. They are picocli
 * options declared per command, and hoisting them to a shared base or mixin would reorder every verb's
 * {@code --help} — a change to the published help surface, which is its own decision with its own guards, not a
 * side effect of removing duplication. So the caller passes them; see {@link BaseCommand#emitMutation}.
 */
public final class ModelWrite {

    private final ResultIdentity identity;
    private String previewSummary;
    private String appliedSummary;
    private JsonNode changed;
    private List<Diagnostic> advisories = List.of();
    private String json;
    private ConsistencyEvidence evidence;
    private boolean dryRun;
    private String destination;

    private ModelWrite(ResultIdentity identity) {
        this.identity = identity;
    }

    /** The verb this write belongs to — echoed by the envelope so the result identifies itself. */
    public static ModelWrite of(ResultIdentity identity) {
        return new ModelWrite(identity);
    }

    /**
     * The two human lines, in the order the two outcomes occur: what a {@code --dry-run} <em>would</em> do, then
     * what the write <em>did</em>. Taken together rather than one per branch so the pair cannot drift apart —
     * they describe the same edit in two tenses.
     */
    public ModelWrite summaries(String wouldDo, String did) {
        this.previewSummary = wouldDo;
        this.appliedSummary = did;
        return this;
    }

    /** The delta this edit made — the envelope's {@code changed}. */
    public ModelWrite changed(JsonNode delta) {
        this.changed = delta;
        return this;
    }

    /** Advisory diagnostics that ride alongside a successful write (lint warnings, config notes). */
    public ModelWrite advisories(List<Diagnostic> diagnostics) {
        this.advisories = diagnostics == null ? List.of() : diagnostics;
        return this;
    }

    /** The DM-JSON to persist — the gated result, not the pre-edit source. */
    public ModelWrite json(String resultJson) {
        this.json = resultJson;
        return this;
    }

    /**
     * The receipt of the gate that cleared {@link #json}. Required, and required to be <em>obtained</em>: an
     * ungated route states {@link ConsistencyEvidence#none()} out loud rather than omitting the call.
     */
    public ModelWrite gatedBy(ConsistencyEvidence receipt) {
        this.evidence = receipt;
        return this;
    }

    /** {@code --dry-run}: compute and report, write nothing. */
    public ModelWrite dryRun(boolean previewOnly) {
        this.dryRun = previewOnly;
        return this;
    }

    /** Where to write: {@code --output} when given, else the model in place. */
    public ModelWrite destination(String outputPath, String model) {
        this.destination = outputPath != null ? outputPath : model;
        return this;
    }

    /**
     * Everything the record must carry is present. Called by {@link BaseCommand#emitMutation} <b>before</b> the
     * write, so a verb that forgot to state its gate is refused instead of mutating the model and then throwing
     * on the way out — required state is validated ahead of the side effect, never after it.
     */
    void requireComplete() {
        require(evidence);
    }

    boolean previewOnly() {
        return dryRun;
    }

    String destination() {
        return destination;
    }

    String json() {
        return json;
    }

    ResultEnvelope preview() {
        return ResultEnvelope.preview(identity, previewSummary, changed, advisories, require(evidence));
    }

    ResultEnvelope applied() {
        return ResultEnvelope.applied(identity, appliedSummary, changed, advisories, destination,
                require(evidence));
    }

    private ConsistencyEvidence require(ConsistencyEvidence receipt) {
        if (receipt == null) {
            throw new IllegalStateException(identity + " built a ModelWrite without stating its gate — "
                    + "pass the object that gated, or ConsistencyEvidence.none() if none did");
        }
        return receipt;
    }
}

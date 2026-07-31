package io.github.mbackschat.a12.dm.cli;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.core.Verification;
import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * The uniform result envelope every {@code dmtool} verb (and every {@code apply} op) emits — CLI-SPEC §3b.
 * One op-record {@code {target, op, args}} in, one envelope out, so an agent learns a single output shape.
 *
 * <p>It embeds the existing {@link Diagnostic} array <b>verbatim</b> (via {@link ValidationResult}); the wrapper
 * adds the three orthogonal axes an agent must not conflate:
 *
 * <ul>
 *   <li>{@code ok} — <b>the operation executed as asked</b>. A property of the invocation, never of the subject.</li>
 *   <li>{@code valid} — <b>the subject model is kernel-valid</b>. A consistency <em>verdict</em>, so it exists only
 *       where a gate produced one: {@code model check} that finds the model invalid is {@code ok:true, valid:false}.</li>
 *   <li>{@code verification} / {@code engine}+{@code engineVersion}+{@code toolVersion} — <b>who vouches</b> for
 *       the verdict, and which implementation produced a semantic observation. See {@link ConsistencyEvidence} and
 *       {@link RuntimeEvidence}.</li>
 * </ul>
 *
 * <h2>Why this is a closed class with eight factories</h2>
 *
 * <p>The predecessor was a {@code record} with a public canonical constructor and a
 * {@code read(target, op, summary, boolean valid, diagnostics, data)} factory. Both were defect generators, and the
 * 2026-07-31 field feedback is what they generated:
 *
 * <ul>
 *   <li>the {@code boolean valid} sat in a long, largely same-typed parameter list, and <b>35 call sites passed a
 *       literal</b> — overwhelmingly {@code true}. Seven of them ({@code model info}, {@code field read},
 *       {@code group read}, {@code config read}, {@code meta}, {@code model compute}, {@code model eval}) were
 *       measured reporting {@code valid:true} on a model the kernel <b>rejects</b>. Nothing was lying on purpose:
 *       the parameter simply had to be given a value, and {@code true} is what one writes for a read that
 *       succeeded;</li>
 *   <li>the public constructor meant any new route could assemble any combination of members, so no invariant
 *       about the shape could be relied on downstream;</li>
 *   <li>{@code withVerification} let evidence be bolted on <em>after</em> the fact, by a caller who by then no
 *       longer held the gate that would have justified it.</li>
 * </ul>
 *
 * <p>So the wrong claim is now <b>unrepresentable</b> rather than merely discouraged. Construction is private; every
 * route picks the one factory that matches what it actually did, and that choice fixes which members may appear.
 * A read cannot mention {@code valid} because {@link #queryResult} takes no such argument; a verdict cannot omit its
 * provenance because {@link #consistencyVerdict} demands a {@link ConsistencyEvidence} that only a real gate hands
 * out. {@code withVerification} is gone.
 *
 * <p>{@link Family} is the discriminator, and it drives {@link #ok()} and {@link #exitCode()} through exhaustive
 * switches — so a new family cannot be added without deciding, at the compiler's insistence, what it exits with.
 * <b>Serialization is member-driven, not family-driven</b>, and deliberately stated here rather than implied: a new
 * family compiles and publishes whatever members its factory happens to set. The factories are what constrain that,
 * so a new one must be read against this list, not trusted to be caught.
 *
 * <h2>What this refactor did NOT fix: the leading {@code (target, op, summary)}</h2>
 *
 * <p>Every factory below opens with <b>three adjacent {@code String}s</b> — the exact shape the project's own
 * long-parameter-list rule names as worst (several same-typed parameters, silently swap-prone), and the shape whose
 * {@code boolean valid} neighbour caused the defect this class exists to close. Saying so plainly matters, because the
 * builder rule was cited to justify the private {@link Draft} while the public surface kept the smell.
 *
 * <p>It is not closed here, and the reason is that the two available fixes are differently sized:
 * <ul>
 *   <li><b>A value object for the pair</b> ({@code Verb.of("field", "read")}) touches ~150 call sites, still leaves
 *       {@code summary} adjacent to it, and makes every call noisier — it moves the smell rather than removing it.</li>
 *   <li><b>Deriving the identity</b> from the live picocli spec, so a verb never states its own name, would make the
 *       swap <em>unrepresentable</em> — the outcome the style rules actually prefer. That is a cross-cutting change to
 *       every factory plus the static callers ({@code BaseCommand.kernelRejectionEnvelope}, {@code Cli}'s exception
 *       seam) that hold no command instance, so it is a design call to take deliberately, not a side effect of this
 *       one.</li>
 * </ul>
 *
 * <p>Until then the invariant is <b>checked instead of assumed</b>: {@code VerbDemonstrationTest} asserts that every
 * demonstrated verb's envelope echoes its own command path, so a swapped pair fails the build across the surface even
 * though it still compiles. Tracked in {@code CLI-CONFORMANCE-GAPS}.
 *
 * @see ConsistencyEvidence the gate receipt that gates the {@code verification} key
 * @see RuntimeEvidence the engine/version triple every runtime observation carries
 */
public final class ResultEnvelope {

    /**
     * What kind of result this is — the discriminator every downstream decision switches on exhaustively.
     * Deliberately NOT the same axis as {@link Outcome}: {@code Outcome} is the wire value an agent reads, and four
     * different families all legitimately serialize as {@code read}.
     */
    enum Family {
        /** A read/inspection payload. Carries no verdict, so it can never exit nonzero. */
        QUERY,
        /** A consistency verdict about a subject model, with the gate receipt that produced it. */
        VERDICT,
        /** A runtime evaluation observation, attributed to the engine that computed it. */
        RUNTIME_OBSERVATION,
        /** A comparison of two like artifacts; owns its own exit code and is not a validity claim. */
        COMPARISON,
        /** A write, a preview of one, or a staged step of a transaction. */
        MUTATION,
        /**
         * Every child of a stateless multi-verb dispatch was run. The aggregate reports that it dispatched them
         * all; each child's own exit rides its item and <b>never</b> determines the outer one.
         *
         * <p>Its own family because "we ran everything you asked" is not any of the others: not a mutation (the
         * children own their writes), not a verdict, and not a failure just because a child failed. The outer exit
         * stays 0, which is the measured pre-existing contract for {@code batch} — a caller reads per-child results
         * from the items, and collapsing them into one code would destroy exactly the information a batch exists to
         * return.
         */
        DISPATCH,
        /** A safety gate refused before mutating (CLI-SPEC §6) — exit 2, nothing written. */
        REFUSAL,
        /** The input was rejected — by the kernel gate, or by the CLI's own pre-flight. Exit 1. */
        REJECTION,
        /** The tool itself failed (an unexpected throwable at the command boundary) — exit 70. */
        INTERNAL_ERROR
    }

    /** The execution result class as published on the wire (distinct from the subject's validity). */
    enum Outcome { APPLIED, PREVIEW, REFUSED, REJECTED, READ, STAGED, COMPLETED, ERROR }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Family family;
    private final String target;
    private final String op;
    private final Outcome outcome;
    private final String summary;
    private final JsonNode changed;
    private final JsonNode data;
    private final List<Diagnostic> diagnostics;
    private final Boolean valid;
    private final boolean written;
    private final String output;
    private final Verification verification;
    private final RuntimeEvidence runtimeEvidence;
    private final ComparisonOutcome comparison;

    private ResultEnvelope(Draft draft) {
        this.family = draft.family;
        this.target = draft.target;
        this.op = draft.op;
        this.outcome = draft.outcome;
        this.summary = draft.summary;
        this.changed = draft.changed;
        this.data = draft.data;
        this.diagnostics = draft.diagnostics == null ? List.of() : List.copyOf(draft.diagnostics);
        this.valid = draft.valid;
        this.written = draft.written;
        this.output = draft.output;
        this.verification = draft.verification;
        this.runtimeEvidence = draft.runtimeEvidence;
        this.comparison = draft.comparison;
    }

    /**
     * The internal assembly step. Private because the eight factories are the whole public construction surface;
     * fluent because the envelope has twelve members of which four are {@code String} and two are {@code JsonNode},
     * and a positional constructor over those is the silent-swap smell the project's builder rule exists to
     * forbid — most of all here, where a swap would misattribute a verdict.
     */
    private static final class Draft {
        private final Family family;
        private final String target;
        private final String op;
        private final Outcome outcome;
        private final String summary;
        private JsonNode changed;
        private JsonNode data;
        private List<Diagnostic> diagnostics = List.of();
        private Boolean valid;
        private boolean written;
        private String output;
        private Verification verification;
        private RuntimeEvidence runtimeEvidence;
        private ComparisonOutcome comparison;

        private Draft(Family family, String target, String op, Outcome outcome, String summary) {
            this.family = family;
            this.target = target;
            this.op = op;
            this.outcome = outcome;
            this.summary = summary;
        }

        private Draft changed(JsonNode value) {
            this.changed = value;
            return this;
        }

        private Draft data(JsonNode value) {
            this.data = value;
            return this;
        }

        private Draft diagnostics(List<Diagnostic> value) {
            this.diagnostics = value;
            return this;
        }

        private Draft valid(boolean value) {
            this.valid = value;
            return this;
        }

        private Draft writtenTo(String path) {
            this.written = true;
            this.output = path;
            return this;
        }

        private Draft verification(Verification value) {
            this.verification = value;
            return this;
        }

        private Draft runtimeEvidence(RuntimeEvidence value) {
            this.runtimeEvidence = value;
            return this;
        }

        private Draft comparison(ComparisonOutcome value) {
            this.comparison = value;
            return this;
        }

        private ResultEnvelope build() {
            return new ResultEnvelope(this);
        }
    }

    // --- 1. query -------------------------------------------------------------------------------------

    /**
     * A read/inspection payload: the op executed and {@code data} carries what it read (a model card, an
     * explanation tree, a name index, …).
     *
     * <p><b>Emits no {@code valid}, ever.</b> A read is not a verdict; the seven verbs that used to claim
     * {@code valid:true} here were claiming a validity they had not established — and on a kernel-invalid model
     * some of them were flatly wrong. When the route DID gate (most model-loading reads do, via
     * {@code BoundModel.ofJson}), that fact is published as {@code verification} instead: honest provenance without
     * a fabricated verdict.
     *
     * @param evidence the gate receipt, or {@link ConsistencyEvidence#none()} — required explicitly, so an ungated
     *                 read is a decision on the record rather than a forgotten argument
     */
    public static ResultEnvelope queryResult(String target, String op, String summary, JsonNode data,
            ConsistencyEvidence evidence) {
        return new Draft(Family.QUERY, target, op, Outcome.READ, summary)
                .data(data)
                .verification(evidence.verification().orElse(null))
                .build();
    }

    /** A query carrying advisory diagnostics alongside its payload (e.g. a version-skew note on a read). */
    public static ResultEnvelope queryResult(String target, String op, String summary, JsonNode data,
            List<Diagnostic> diagnostics, ConsistencyEvidence evidence) {
        return new Draft(Family.QUERY, target, op, Outcome.READ, summary)
                .data(data)
                .diagnostics(diagnostics)
                .verification(evidence.verification().orElse(null))
                .build();
    }

    // --- 2. consistency verdict ----------------------------------------------------------------------

    /**
     * A genuine consistency verdict about a subject — {@code model check}, {@code rule check},
     * {@code workspace check}. The <em>only</em> family that publishes envelope-level {@code valid}, and it
     * publishes {@code valid} and its provenance <b>inseparably</b>: the gate that produced the verdict is a
     * required argument, so a verdict without evidence cannot be built.
     *
     * <p>A verdict may carry {@code data}: {@code workspace check} returns per-model entries and
     * {@code rule check --suggest-error-field} returns a candidate list beside its verdict. Omitting {@code data}
     * here would push both onto {@link #queryResult} and re-create the read/verdict conflation being removed.
     *
     * @throws IllegalStateException if {@code evidence} attests no gate — a verdict with nothing behind it is a
     *                               programming error, not a shape to serialize
     */
    public static ResultEnvelope consistencyVerdict(String target, String op, String summary, boolean valid,
            List<Diagnostic> diagnostics, JsonNode data, ConsistencyEvidence evidence) {
        if (!evidence.gateRan()) {
            throw new IllegalStateException("a consistency verdict for `" + target + " " + op + "` must carry the "
                    + "gate that produced it — no gate ran on this path, so there is no verdict to report "
                    + "(use queryResult if this route only reads)");
        }
        return new Draft(Family.VERDICT, target, op, Outcome.READ, summary)
                .valid(valid)
                .diagnostics(diagnostics)
                .data(data)
                .verification(evidence.verification().orElseThrow())
                .build();
    }

    // --- 3. runtime observation ----------------------------------------------------------------------

    /**
     * A runtime evaluation result — {@code model eval}, {@code model compute}, {@code rule eval}. The observation
     * rides {@code data}, attributed to the engine that computed it.
     *
     * <p><b>Emits no {@code valid}.</b> Evaluating a document against a model produces fired rules and computed
     * values, not a verdict about the model; these verbs are also the ones that were measured claiming
     * {@code valid:true} while the model was kernel-invalid. What they can honestly state is who computed the
     * answer, which {@link RuntimeEvidence} makes mandatory rather than optional.
     */
    public static ResultEnvelope runtimeObservation(String target, String op, String summary, JsonNode data,
            RuntimeEvidence evidence) {
        return new Draft(Family.RUNTIME_OBSERVATION, target, op, Outcome.READ, summary)
                .data(data)
                .runtimeEvidence(evidence)
                .build();
    }

    // --- 4. comparison --------------------------------------------------------------------------------

    /**
     * A comparison of two like artifacts — {@code profile compare}. The typed {@link ComparisonOutcome} owns both
     * the reported result and the exit code, so neither is recovered by re-reading serialized JSON.
     *
     * <p>Profile equality is not model validity: this family exists so {@code compare} stops calling it
     * {@code valid} while <b>keeping exit 1 for {@code differs}</b> exactly as before.
     */
    public static ResultEnvelope comparisonResult(String target, String op, String summary,
            ComparisonOutcome outcome, JsonNode data) {
        return new Draft(Family.COMPARISON, target, op, Outcome.READ, summary)
                .comparison(outcome)
                .data(data)
                .build();
    }

    /**
     * Every child of a stateless multi-verb dispatch ran — {@code batch}. {@code data} carries the per-child items;
     * the aggregate exits <b>0</b> whatever the children returned.
     *
     * <p>This is deliberate, and it is the one place an aggregate does <em>not</em> derive its status from its parts.
     * A batch's product IS the item list: a caller reads each child's exit from its item, and folding them into a
     * single code would discard the very information the verb exists to return. It also preserves the measured
     * pre-existing behaviour. Contrast {@code apply}, whose children are steps of ONE transaction with one
     * all-or-nothing outcome, and which therefore does derive its exit from that transaction.
     */
    public static ResultEnvelope dispatchCompleted(String target, String op, String summary, JsonNode data) {
        return new Draft(Family.DISPATCH, target, op, Outcome.COMPLETED, summary)
                .data(data)
                .build();
    }

    // --- 5. mutation ----------------------------------------------------------------------------------

    /**
     * The three mutation dispositions. This enum is the single authority for how a mutation maps onto the wire
     * {@code outcome} and {@code written} — the mapping used to be re-derived at each factory.
     *
     * <p>Exposed through the three named factories {@link #applied}/{@link #preview}/{@link #staged} rather than as
     * a parameter: the name states the disposition at the call site, which reads better and cannot be passed the
     * wrong constant, while the enum keeps the mapping single-sourced and exhaustive.
     */
    enum MutationDisposition {
        /** Written to disk. */
        APPLIED(Outcome.APPLIED),
        /** {@code --dry-run}: computed, nothing written. */
        PREVIEW(Outcome.PREVIEW),
        /** A step of an {@code apply} transaction — staged in the session, committed only by the terminal gate. */
        STAGED(Outcome.STAGED);

        private final Outcome outcome;

        MutationDisposition(Outcome outcome) {
            this.outcome = outcome;
        }

        Outcome outcome() {
            return outcome;
        }
    }

    /**
     * A mutating op that succeeded and wrote the model.
     *
     * <p><b>Publishes no evidence yet.</b> The edit verbs <em>do</em> run the real consistency oracle before writing
     * — a write that would break the model fails there — so this family has a receipt it could publish, and §2 says
     * it should. Threading it through the ~75 mutation call sites is a separate, purely additive change with its own
     * commit; this one closes the fabricated-{@code valid} defect. Until then a mutation stays silent about
     * provenance, which is the pre-existing shape and never a false claim.
     */
    public static ResultEnvelope applied(String target, String op, String summary, JsonNode changed, String output) {
        return applied(target, op, summary, changed, List.of(), output);
    }

    /** A mutating op that succeeded, carrying advisory diagnostics (e.g. lint warnings) alongside the write. */
    public static ResultEnvelope applied(String target, String op, String summary, JsonNode changed,
            List<Diagnostic> diagnostics, String output) {
        return mutation(MutationDisposition.APPLIED, target, op, summary, changed, diagnostics)
                .writtenTo(output)
                .build();
    }

    /** A mutating op previewed under {@code --dry-run} — nothing written. */
    public static ResultEnvelope preview(String target, String op, String summary, JsonNode changed) {
        return preview(target, op, summary, changed, List.of());
    }

    /** A mutating op previewed under {@code --dry-run} — nothing written — carrying advisory diagnostics. */
    public static ResultEnvelope preview(String target, String op, String summary, JsonNode changed,
            List<Diagnostic> diagnostics) {
        return mutation(MutationDisposition.PREVIEW, target, op, summary, changed, diagnostics).build();
    }

    /**
     * One staged step of an {@code apply} transaction: the edit is in the session but nothing is written, and the
     * sequence's single terminal gate decides whether it ever will be. Individually ungated <em>by design</em> —
     * deferring validation to one terminal check is {@code apply}'s invariant — so a staged step passes
     * {@link ConsistencyEvidence#none()} and the aggregate carries the evidence.
     */
    public static ResultEnvelope staged(String target, String op, String summary, JsonNode changed,
            List<Diagnostic> diagnostics) {
        return mutation(MutationDisposition.STAGED, target, op, summary, changed, diagnostics).build();
    }

    /**
     * A <b>transaction</b>'s outcome — {@code apply} — whose payload rides {@code data} rather than {@code changed}.
     *
     * <p>The distinction is not cosmetic. {@code changed} is "the delta this op made"; a transaction report
     * ({@code ops}, {@code committed}, {@code failedAt}, and the per-op envelopes) is a <em>payload about the run</em>,
     * which is what {@code data} is for. Without this factory the aggregate had to be squeezed into
     * {@link #applied}'s {@code changed} parameter — and that is exactly what happened on the first attempt: the
     * argument landed in the neighbouring slot, {@code data} came out absent, and every success assertion failed. The
     * two are adjacent {@code JsonNode} parameters, i.e. precisely the silent-swap shape this class was built to
     * eliminate, still live inside the class itself. Naming the case removes the hazard rather than documenting it.
     */
    public static ResultEnvelope transaction(String target, String op, String summary, JsonNode data,
            MutationDisposition disposition, String output) {
        Draft draft = new Draft(Family.MUTATION, target, op, disposition.outcome(), summary).data(data);
        return (output == null ? draft : draft.writtenTo(output)).build();
    }

    private static Draft mutation(MutationDisposition disposition, String target, String op, String summary,
            JsonNode changed, List<Diagnostic> diagnostics) {
        return new Draft(Family.MUTATION, target, op, disposition.outcome(), summary)
                .changed(changed)
                .diagnostics(diagnostics);
    }

    // --- 6. refusal -----------------------------------------------------------------------------------

    /** A refactor/cascade safety gate refused (CLI-SPEC §6) — nothing written, exit 2. */
    public static ResultEnvelope refused(String target, String op, String summary, List<Diagnostic> diagnostics) {
        return refused(target, op, summary, null, diagnostics);
    }

    /**
     * A gate refused, carrying structured {@code data} (e.g. the referrers) — nothing written, exit 2.
     *
     * <p>A refusal is the CLI declining to proceed, so it asserts nothing about the subject: no {@code valid}, and
     * no {@code verification} — the kernel was never asked for a verdict here.
     */
    public static ResultEnvelope refused(String target, String op, String summary, JsonNode data,
            List<Diagnostic> diagnostics) {
        return new Draft(Family.REFUSAL, target, op, Outcome.REFUSED, summary)
                .data(data)
                .diagnostics(diagnostics)
                .build();
    }

    // --- 7. rejection ---------------------------------------------------------------------------------

    /**
     * The input was rejected — nothing written, exit 1. The {@link RejectionBasis} decides the shape:
     *
     * <ul>
     *   <li>{@link RejectionBasis.KernelRejection} — the gate ran and refused, so {@code valid:false} <b>and</b>
     *       {@code verification}. Previously an invalid-model rejection published neither, which was the sharpest
     *       form of the provenance defect: the kernel had just arbitrated, and the envelope said nothing about
     *       it;</li>
     *   <li>{@link RejectionBasis.PreflightRejection} — the CLI's own input gate refused first, so neither.</li>
     * </ul>
     */
    public static ResultEnvelope rejected(String target, String op, String summary, List<Diagnostic> diagnostics,
            RejectionBasis basis) {
        return rejected(target, op, summary, null, diagnostics, basis);
    }

    /**
     * A rejection carrying structured {@code data} — the corrective payload a verb had already computed and that
     * stays useful precisely because the input was rejected ({@code rule check --suggest-error-field}'s candidate
     * list is the case: it exists to answer "then what should I have written?").
     */
    public static ResultEnvelope rejected(String target, String op, String summary, JsonNode data,
            List<Diagnostic> diagnostics, RejectionBasis basis) {
        Draft draft = new Draft(Family.REJECTION, target, op, Outcome.REJECTED, summary)
                .data(data)
                .diagnostics(diagnostics);
        return switch (basis) {
            case RejectionBasis.KernelRejection kernel ->
                    draft.valid(false).verification(kernel.verification()).build();
            case RejectionBasis.PreflightRejection ignored -> draft.build();
        };
    }

    // --- 8. internal error ----------------------------------------------------------------------------

    /**
     * The tool itself failed — an unexpected throwable caught at the command boundary (CLI-CONFORMANCE: no
     * raw stack trace escapes a verb), NOT a kernel rejection of input. {@code ok:false}, exit 70, nothing
     * written; the carried {@code RK_INTERNAL_ERROR} diagnostic names the failure (full trace on stderr).
     */
    public static ResultEnvelope internalError(String target, String op, String summary,
            List<Diagnostic> diagnostics) {
        return new Draft(Family.INTERNAL_ERROR, target, op, Outcome.ERROR, summary)
                .diagnostics(diagnostics)
                .build();
    }

    // --- accessors (record-style) ---------------------------------------------------------------------

    Family family() {
        return family;
    }

    public String target() {
        return target;
    }

    public String op() {
        return op;
    }

    Outcome outcome() {
        return outcome;
    }

    public String summary() {
        return summary;
    }

    public JsonNode changed() {
        return changed;
    }

    public JsonNode data() {
        return data;
    }

    public List<Diagnostic> diagnostics() {
        return diagnostics;
    }

    /** The consistency verdict, present only on a verdict or a kernel rejection. */
    public Optional<Boolean> valid() {
        return Optional.ofNullable(valid);
    }

    public boolean written() {
        return written;
    }

    public Optional<String> output() {
        return Optional.ofNullable(output);
    }

    /** How a consistency gate arbitrated THIS invocation — empty when no gate ran. */
    public Optional<Verification> verification() {
        return Optional.ofNullable(verification);
    }

    // --- derived contract -----------------------------------------------------------------------------

    /** {@code ok} = the operation executed as asked — NOT the subject's validity (that is {@link #valid()}). */
    boolean ok() {
        return switch (family) {
            case QUERY, VERDICT, RUNTIME_OBSERVATION, COMPARISON, MUTATION, DISPATCH -> true;
            case REFUSAL, REJECTION, INTERNAL_ERROR -> false;
        };
    }

    /**
     * 0 = executed with nothing to report against it; 1 = rejected, or a verdict/comparison that came out negative;
     * 2 = refused; 70 = internal tool error.
     *
     * <p>Dispatching on {@link Family} rather than inspecting members is what makes two contract rules structural
     * instead of conventional. A {@code QUERY} <b>cannot</b> exit 1, because it holds no verdict to be negative
     * about. And a <b>nested</b> verdict — {@code workspace list --validate}'s per-model {@code valid}, a batch
     * child's — can never leak into the process's exit status, because nested verdicts live inside {@code data} and
     * nothing here reads {@code data}.
     */
    public int exitCode() {
        return switch (family) {
            case INTERNAL_ERROR -> 70;   // EX_SOFTWARE — the tool failed, distinct from rejected=1 / refused=2
            case REFUSAL -> 2;
            case REJECTION -> 1;
            case VERDICT -> Boolean.FALSE.equals(valid) ? 1 : 0;
            case COMPARISON -> comparison.exitCode();
            case QUERY, RUNTIME_OBSERVATION, MUTATION, DISPATCH -> 0;
        };
    }

    JsonNode toJson() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("target", target);
        root.put("op", op);
        root.put("outcome", outcome.name().toLowerCase(Locale.ROOT));
        root.put("ok", ok());
        if (valid != null) {
            root.put("valid", valid);
        }
        if (verification != null) {
            root.put("verification", verification.name());
        }
        if (runtimeEvidence != null) {
            runtimeEvidence.writeTo(root);
        }
        root.put("summary", summary);
        if (changed != null && !changed.isNull()) {
            root.set("changed", changed);
        }
        if (data != null && !data.isNull()) {
            root.set("data", data);
        }
        // reuse the canonical Diagnostic-array serialization verbatim (CLI-SPEC §4)
        try {
            root.set("diagnostics", MAPPER.readTree(ValidationResult.of(diagnostics).toJson()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to render diagnostics", e);
        }
        root.put("written", written);
        if (output != null) {
            root.put("output", output);
        }
        return root;
    }

    /** Pretty-printed, newline-terminated — what a verb prints to stdout. */
    public String toJsonString() {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(toJson()) + "\n";
        } catch (JsonProcessingException e) {
            return toJson().toString() + "\n";
        }
    }
}

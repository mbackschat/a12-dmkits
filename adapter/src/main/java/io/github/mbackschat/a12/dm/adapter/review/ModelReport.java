package io.github.mbackschat.a12.dm.adapter.review;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.rulekit.read.ReadComputation;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.rulekit.context.ComputationExplanation;
import io.github.mbackschat.a12.dm.rulekit.context.ModelCard;
import io.github.mbackschat.a12.dm.rulekit.context.ModelContext;
import io.github.mbackschat.a12.dm.rulekit.context.RuleExplanation;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.LocalizedText;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;

/**
 * MODEL-REVIEW — the comprehension engine (a library primitive, MODEL-REVIEW-SPEC §4), the peer of
 * {@link ModelDiff}. Assembles a single model's comprehension view by <b>reusing</b> the existing reads —
 * no reinvention:
 * <ul>
 *   <li><b>identity</b> (J1, from {@link ModelDocument});</li>
 *   <li>the <b>structure</b> outline (J2, via {@link ModelContext#describe()} → {@link ModelCard});</li>
 *   <li>the unreferenced (dead-field) set (J4, via the whole-model reference inverse
 *       {@link KernelAdapter#referrersByEntity} — built once, {@code O(R+C+F)}, not the per-field
 *       {@code whereUsed} recomputation that made a report {@code O(F·(R+C))} and stall on huge models);</li>
 *   <li>the <b>glossed rules/computations catalog</b> (J3, the centerpiece) — every rule/computation in plain
 *       language: the tool-computed {@code gloss} ({@link ModelContext#explain}) + the fixed {@code polarity}
 *       (a rule fires on the VIOLATION) + the verbatim stored {@code messages} (the modeller's own text), so a
 *       reviewer sees what the condition DOES next to what it CLAIMS (the Hybrid-gloss drift signal).</li>
 * </ul>
 *
 * <p>Tool-computed and deterministic (the trust principle); tolerant of conditions rulekit reads as
 * {@code Opaque} (reported {@code explained:false} with the kernel-canonical text, never dropped).
 */
public record ModelReport(Identity identity, ModelCard structure, Usage usage,
        List<RuleEntry> rules, List<ComputationEntry> computations) {

    /** The fixed polarity fact — an A12 rule condition is TRUE on the violation, not the requirement. */
    public static final Polarity VIOLATION_POLARITY = new Polarity("violation",
            "An A12 rule condition is TRUE when the rule is VIOLATED — it models the state to reject, not the "
                    + "requirement. Read it as the forbidden case; the message states the requirement positively.");

    /** The model's header facts — its identity card (J1). */
    public record Identity(String id, String modelType, String modelVersion, List<String> locales) {}

    /** The fields no rule or computation reads — the dead-field audit headline. (The full field→referrers map is `model usage`.) */
    public record Usage(List<String> unreferenced) {}

    /** Why a rule fires — constant across ALL rules, so the report carries it ONCE at the top (not per rule). */
    public record Polarity(String firesOn, String note) {}

    /** A localized stored message (the modeller's own NL — the verbatim half of the Hybrid gloss). */
    public record Message(String locale, String text) {}

    /** The closed explanation state of a rule catalog entry. */
    public sealed interface RuleExplanationOutcome permits ExplainedRule, OpaqueExplanation {}

    /** The closed explanation state of a computation catalog entry. */
    public sealed interface ComputationExplanationOutcome permits ExplainedComputation, OpaqueExplanation {}

    /** A rule that rulekit modeled completely. */
    public record ExplainedRule(RuleExplanation explanation) implements RuleExplanationOutcome {
        public ExplainedRule {
            Objects.requireNonNull(explanation, "explanation");
        }

        public String gloss() {
            return explanation.gloss();
        }

        public String canonicalText() {
            return explanation.text();
        }
    }

    /**
     * A computation that rulekit modeled completely, including the same common-precondition and alternative
     * detail exposed by {@code computation read}.
     */
    public record ExplainedComputation(ComputationExplanation explanation, DraftComputation draft,
            List<Message> messages)
            implements ComputationExplanationOutcome {
        public ExplainedComputation {
            Objects.requireNonNull(explanation, "explanation");
            Objects.requireNonNull(draft, "draft");
            messages = List.copyOf(messages);
        }
    }

    /** A rule or computation that rulekit preserved verbatim because it could not model one construct. */
    public record OpaqueExplanation(String canonicalText, String reason)
            implements RuleExplanationOutcome, ComputationExplanationOutcome {
        public OpaqueExplanation {
            Objects.requireNonNull(canonicalText, "canonicalText");
            Objects.requireNonNull(reason, "reason");
        }

        public static Builder builder() {
            return new Builder();
        }

        public static final class Builder {
            private String canonicalText;
            private String reason;

            public Builder canonicalText(String canonicalText) {
                this.canonicalText = canonicalText;
                return this;
            }

            public Builder reason(String reason) {
                this.reason = reason;
                return this;
            }

            public OpaqueExplanation build() {
                return new OpaqueExplanation(canonicalText, reason);
            }
        }
    }

    /**
     * One rule, glossed for comprehension. Polarity is the model-level constant
     * {@link #VIOLATION_POLARITY}, not repeated here.
     */
    public record RuleEntry(String rule, String scope, RuleExplanationOutcome explanation,
            String severity, String code, String errorField, List<Message> messages) {
        public RuleEntry {
            Objects.requireNonNull(explanation, "explanation");
            messages = List.copyOf(messages);
        }

        public static Builder builder(String rule, String scope, RuleExplanationOutcome explanation) {
            return new Builder(rule, scope, explanation);
        }

        public boolean explained() {
            return explanation instanceof ExplainedRule;
        }

        public String gloss() {
            return switch (explanation) {
                case ExplainedRule explained -> explained.gloss();
                case OpaqueExplanation ignored -> null;
            };
        }

        public String canonicalText() {
            return switch (explanation) {
                case ExplainedRule explained -> explained.canonicalText();
                case OpaqueExplanation opaque -> opaque.canonicalText();
            };
        }

        public String reason() {
            return switch (explanation) {
                case ExplainedRule ignored -> null;
                case OpaqueExplanation opaque -> opaque.reason();
            };
        }

        public static final class Builder {
            private final String rule;
            private final String scope;
            private final RuleExplanationOutcome explanation;
            private String severity;
            private String code;
            private String errorField;
            private List<Message> messages = List.of();

            private Builder(String rule, String scope, RuleExplanationOutcome explanation) {
                this.rule = rule;
                this.scope = scope;
                this.explanation = explanation;
            }

            public Builder severity(String severity) {
                this.severity = severity;
                return this;
            }

            public Builder code(String code) {
                this.code = code;
                return this;
            }

            public Builder errorField(String errorField) {
                this.errorField = errorField;
                return this;
            }

            public Builder messages(List<Message> messages) {
                this.messages = messages;
                return this;
            }

            public RuleEntry build() {
                return new RuleEntry(rule, scope, explanation, severity, code, errorField, messages);
            }
        }
    }

    /**
     * One computation, glossed for comprehension. The explained branch carries the computed field,
     * common precondition, alternatives, and messages together; the Opaque branch carries only the preserved
     * text and conversion reason, so contradictory branch states cannot be constructed.
     */
    public record ComputationEntry(String computation, String scope,
            ComputationExplanationOutcome explanation) {
        public ComputationEntry {
            Objects.requireNonNull(explanation, "explanation");
        }

        public static Builder builder(String computation, String scope) {
            return new Builder(computation, scope);
        }

        public boolean explained() {
            return explanation instanceof ExplainedComputation;
        }

        public String computedField() {
            return switch (explanation) {
                case ExplainedComputation explained -> explained.explanation().computedField();
                case OpaqueExplanation ignored -> null;
            };
        }

        public String gloss() {
            return switch (explanation) {
                case ExplainedComputation explained -> explained.explanation().gloss();
                case OpaqueExplanation ignored -> null;
            };
        }

        public String canonicalText() {
            return switch (explanation) {
                case ExplainedComputation ignored -> null;
                case OpaqueExplanation opaque -> opaque.canonicalText();
            };
        }

        public String commonPrecondition() {
            return switch (explanation) {
                case ExplainedComputation explained -> explained.draft().commonPrecondition();
                case OpaqueExplanation ignored -> null;
            };
        }

        public List<DraftComputation.Alternative> alternatives() {
            return switch (explanation) {
                case ExplainedComputation explained -> explained.draft().alternatives();
                case OpaqueExplanation ignored -> List.of();
            };
        }

        public List<Message> messages() {
            return switch (explanation) {
                case ExplainedComputation explained -> explained.messages();
                case OpaqueExplanation ignored -> List.of();
            };
        }

        public String reason() {
            return switch (explanation) {
                case ExplainedComputation ignored -> null;
                case OpaqueExplanation opaque -> opaque.reason();
            };
        }

        public static final class Builder {
            private final String computation;
            private final String scope;
            private ComputationExplanationOutcome explanation;

            private Builder(String computation, String scope) {
                this.computation = computation;
                this.scope = scope;
            }

            public Builder explanation(ComputationExplanationOutcome explanation) {
                this.explanation = explanation;
                return this;
            }

            public ComputationEntry build() {
                return new ComputationEntry(computation, scope, explanation);
            }
        }
    }

    /** Assemble the comprehension view of {@code modelJson} (a self-contained, kernel-loadable model). */
    public static ModelReport of(String modelJson) {
        return of(modelJson, BoundModel.ofJson(modelJson));
    }

    /**
     * Assemble the comprehension view, resolving {@code include}s/imports through a <b>kernel-free</b>
     * {@link io.github.mbackschat.a12.dm.core.ModelResolver} — the abstraction the rest of the dmkits surface
     * uses (a {@code id → DM-JSON} SAM), so a consumer need not import {@code com.mgmtp.*}. {@code null} = none.
     */
    public static ModelReport of(String modelJson, io.github.mbackschat.a12.dm.core.ModelResolver resolver) {
        return of(modelJson, resolver == null ? BoundModel.ofJson(modelJson) : BoundModel.ofJson(modelJson, resolver));
    }

    /** The shared assembly over an already-bound model — the public overloads differ only in how the
     *  {@link BoundModel} was resolved. */
    private static ModelReport of(String modelJson, BoundModel model) {
        return assemble(modelJson, model, KernelAdapter.of(model));
    }

    /**
     * The assembly over a caller-provided {@link KernelAdapter} — a package-private seam so the Set RP perf
     * lock can build the adapter itself and read its {@link KernelAdapter#referenceParseCount()} after
     * assembly, pinning that one report parses each rule/computation at most once (never once per field).
     */
    static ModelReport assemble(String modelJson, BoundModel model, KernelAdapter adapter) {
        ModelDocument doc = ModelDocument.read(modelJson);
        Identity identity = new Identity(doc.id(), doc.modelType(), doc.modelVersion(), doc.locales());
        ModelContext context = new ModelContext(model);
        ModelCard structure = context.describe();
        return new ModelReport(identity, structure, usage(adapter, structure),
                ruleCatalog(adapter, context), computationCatalog(adapter, context));
    }

    /** The dead-field set — served from the whole-model reference inverse ({@link KernelAdapter#referrersByEntity},
     *  built once), so it is {@code O(R+C+F)} instead of re-parsing every rule/computation per field. A field is
     *  unreferenced iff no rule or computation references it, i.e. iff it is not a key in the inverse. */
    private static Usage usage(KernelAdapter adapter, ModelCard card) {
        Map<String, Set<String>> referrers = adapter.referrersByEntity();
        List<String> unreferenced = new ArrayList<>();
        for (ModelCard.Field field : card.fields()) {
            if (!referrers.containsKey(field.path())) {
                unreferenced.add(field.path());
            }
        }
        return new Usage(List.copyOf(unreferenced));
    }

    private static List<RuleEntry> ruleCatalog(KernelAdapter adapter, ModelContext context) {
        List<RuleEntry> out = new ArrayList<>();
        for (String name : adapter.ruleFullNames()) {
            ReadResult read = adapter.read(name);
            RuleExplanationOutcome explanation = switch (read) {
                case ReadResult.Converted converted -> {
                    RuleExplanation explained = context.explain(converted.condition());
                    yield new ExplainedRule(explained);
                }
                case ReadResult.Opaque opaque ->
                    OpaqueExplanation.builder()
                            .canonicalText(opaque.canonicalText())
                            .reason(opaque.reason())
                            .build();
            };
            out.add(RuleEntry.builder(name, scopeOf(name), explanation)
                    .severity(adapter.ruleSeverity(name))
                    .code(adapter.ruleErrorCode(name))
                    .errorField(adapter.ruleErrorField(name).orElse(null))
                    .messages(messages(adapter.ruleMessages(name)))
                    .build());
        }
        return out;
    }

    private static List<ComputationEntry> computationCatalog(KernelAdapter adapter, ModelContext context) {
        List<ComputationEntry> out = new ArrayList<>();
        for (String name : adapter.computationFullNames()) {
            ReadComputation read = adapter.readComputation(name);
            if (read instanceof ReadComputation.Converted converted) {
                ComputationExplanation ex = context.explainComputation(converted.computation());
                // the (precondition?, operation) formulae — the SAME DraftComputation `computation read`
                // emits, so the report and the per-computation read agree on when a field is filled
                DraftComputation draft = converted.computation().draft();
                out.add(ComputationEntry.builder(name, scopeOf(name))
                        .explanation(new ExplainedComputation(
                                ex, draft, computationMessages(ex.messages())))
                        .build());
            } else {
                ReadComputation.Opaque opaque = (ReadComputation.Opaque) read;
                out.add(ComputationEntry.builder(name, scopeOf(name))
                        .explanation(OpaqueExplanation.builder()
                                .canonicalText(opaque.canonicalText())
                                .reason(opaque.reason())
                                .build())
                        .build());
            }
        }
        return out;
    }

    /** The element's scope group — its containing group (the path with the trailing element name removed). */
    private static String scopeOf(String fullName) {
        int slash = fullName.lastIndexOf('/');
        return slash > 0 ? fullName.substring(0, slash) : fullName;
    }

    private static List<Message> messages(List<LocalizedText> stored) {
        List<Message> out = new ArrayList<>();
        for (LocalizedText t : stored) {
            out.add(new Message(t.locale(), t.text()));
        }
        return out;
    }

    private static List<Message> computationMessages(List<ComputationExplanation.Message> stored) {
        List<Message> out = new ArrayList<>();
        for (ComputationExplanation.Message t : stored) {
            out.add(new Message(t.locale(), t.text()));
        }
        return out;
    }
}

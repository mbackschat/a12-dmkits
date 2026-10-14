package io.github.mbackschat.a12.dm.adapter.laws;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;

/**
 * The kernel-runtime probe's observation artifact: the value {@link KernelRuntimeProbe} produces and the exact
 * bytes it writes. Kernel-free by construction — the probe converts, this renders — so a consumer of the shape
 * (and the acceptance case that a strategy disagreement survives) can build one without a kernel in reach.
 *
 * <p><b>{@code enginesAgree} is DERIVED, never authored.</b> A {@link Row} holds one {@link Engine} block per
 * kernel strategy and computes agreement from them, so no caller can merge two observations into one set, keep
 * only the first, or state agreement a row's own content contradicts. That is the artifact's whole evidence
 * value: a strategy split is the finding, and an implementation that could lose it silently would be worse than
 * no probe (the ratified oracle records a static-Java split, never adopts it — INTERPRETER-FINDINGS IF136,
 * {@code docs/CONFORMANCE-CORPUS-SPEC.md} §11).
 *
 * <p><b>Determinism is structural, not conventional.</b> {@link Engine} sorts its messages and {@link
 * Computations} its outcomes and operand errors, each in a compact constructor; the header carries no clock
 * reading; and the renderer pins a {@code "\n"} indenter rather than inheriting the platform separator. So two
 * runs over identical input and an identical tree produce identical bytes, which is what lets a consumer pin the
 * artifact by SHA-256.
 *
 * <p>Contract + key meanings: {@code docs/KERNEL-PROBE-SPEC.md}; machine-readable shape:
 * {@code adapter/src/test/resources/kernel-probe/artifact.schema.json}.
 */
public record KernelProbeArtifact(Header header, List<Row> rows,
        Optional<Map<KernelAdapter.MandatorySet, List<String>>> mandatoryInformation,
        Optional<KernelAdapter.DynamicEnumerations> dynamicEnumerations) {

    /**
     * The artifact shape's own version, bumped when a key's presence or meaning changes.
     *
     * <p><b>Why the optional {@code computations} block did NOT bump it, nor {@code mandatoryInformation}.</b>
     * A consumer only receives either block by asking for it ({@code observe: ["validateFull", "compute"]},
     * {@code observe: ["mandatoryInformation"]}), so no artifact any existing consumer can obtain changes
     * shape, and no existing key changes meaning. Bumping would instead have broken the harder guarantee: a
     * request that does not ask for them must produce <em>byte-identical</em> output, because consumers pin
     * these bytes by SHA-256, and the version rides in the first line of every artifact.
     *
     * <p><b>Nor did {@link Fault}, and its argument is the same one a step stronger.</b> {@code fault} appears
     * only where a run used to produce <em>no artifact at all</em> — the probe died with the output path
     * unwritten — so no artifact any consumer holds or can obtain carries the key, and none changes meaning. It
     * does make {@code messages} conditional, which is a presence change on paper; on every artifact that could
     * exist before this, {@code messages} is still there, because a block omits it exactly when it faulted.
     */
    public static final int SCHEMA_VERSION = 1;

    public KernelProbeArtifact {
        rows = List.copyOf(rows);
        mandatoryInformation = mandatoryInformation.map(Map::copyOf);
    }

    /** The row-only artifact — every request that observes no model-level channel. */
    public KernelProbeArtifact(Header header, List<Row> rows) {
        this(header, rows, Optional.empty(), Optional.empty());
    }

    /** The pre-dyn-enum form, retained so existing construction sites keep compiling. */
    public KernelProbeArtifact(Header header, List<Row> rows,
            Optional<Map<KernelAdapter.MandatorySet, List<String>>> mandatoryInformation) {
        this(header, rows, mandatoryInformation, Optional.empty());
    }

    /**
     * What produced the observation, so an artifact stays citable after the producer moves on.
     *
     * @param dmtoolVersion      this repository's released version
     * @param kernelBuiltAgainst the kernel pin the probe compiled against
     * @param kernelRuntime      the kernel jar actually on the classpath (may be newer — the F11 posture)
     * @param sourceRevision     the full Git revision, or {@code (unknown)}
     * @param sourceState        {@code CLEAN} (the revision was used), {@code DIRTY} (the worktree differed
     *                           from it), or {@code UNKNOWN}
     * @param model              the request's model reference, verbatim as the request spelled it
     * @param locale             the evaluation locale, which decides the {@code message} channel's language
     * @param clock              the pinned evaluation instant, when the request pinned one
     */
    public record Header(String dmtoolVersion, String kernelBuiltAgainst, String kernelRuntime,
            String sourceRevision, String sourceState, String model, String locale, Optional<String> clock) {
    }

    /** One named request row: the same document observed on every kernel strategy. */
    public record Row(String name, List<Engine> engines) {

        public Row {
            engines = List.copyOf(engines);
        }

        /**
         * Whether every strategy produced the identical observation — <b>every</b> channel it reported, not the
         * messages alone: a compute-only split is exactly as much a kernel fact as a message split, and an
         * agreement flag that ignored a channel would state agreement the row's own content contradicts. Derived
         * from {@link #engines}; see the class note on why this is not a field a caller may set.
         */
        public boolean enginesAgree() {
            return engines.stream().map(Engine::observation).distinct().count() <= 1;
        }
    }

    /** What a strategy reported, minus which strategy reported it — the value {@link Row#enginesAgree} compares. */
    private record Observation(List<Message> messages, Optional<Computations> computations,
            Optional<List<Message>> partialMessages, Optional<ComputeResults> computeResults,
            Optional<List<ConditionCall>> customConditionCalls, Optional<Fault> fault) {
    }

    /**
     * One invocation the kernel made of a custom condition the request declared, in call order across the block's
     * channels. {@code observation} names the channel the call came from, which the arguments cannot: both
     * {@code validateFull} and {@code compute} hand over no relevant set.
     */
    public record ConditionCall(KernelRuntimeProbe.Observation observation, String name,
            ConditionArguments arguments, boolean result) {
    }

    /**
     * What the kernel handed the condition, as addresses: the relevant set ({@link Optional#empty()} when the
     * kernel passed none, which full validation does), the formally incorrect set, and the error entity with its
     * repetition indices. Both sets are sorted, since the kernel hands over sets.
     */
    public record ConditionArguments(Optional<List<String>> relevant, List<String> formallyIncorrect,
            String errorEntity) {

        public ConditionArguments {
            relevant = relevant.map(List::copyOf);
            formallyIncorrect = List.copyOf(formallyIncorrect);
        }
    }

    /**
     * One strategy's complete observation of a row. {@code engine} uses the corpus strategy vocabulary
     * ({@code kernel-groovy-dynamic}, {@code kernel-java-static}) rather than a probe-local one.
     *
     * <p>{@code computations} is present exactly when the request asked for {@code compute}. Absent is
     * <b>not</b> "nothing computed": it is "not observed", and the distinction is the same one the probe's
     * refusal of an inconsistent model protects — an empty observation that reads as an answer is worse than no
     * answer. So the block is omitted from the JSON entirely rather than rendered empty.
     *
     * <p>{@code partialMessages} follows the same rule for {@code validatePart}: present exactly when the
     * request asked for it, and omitted otherwise so an artifact pinned before the observation existed stays
     * byte-identical. Here the distinction bites hardest, because the {@code noneRelevant} coverage legitimately
     * produces an EMPTY list — which is a measurement, not an absence.
     *
     * <p>{@code customConditionCalls} is present exactly when the request declares a custom condition, again so
     * an existing artifact keeps its bytes, and an empty list then means the kernel called none. It is no channel:
     * the calls a raise interrupted are kept, because each one completed.
     */
    public record Engine(String engine, List<Message> messages, Optional<Computations> computations,
            Optional<List<Message>> partialMessages, Optional<ComputeResults> computeResults,
            Optional<List<ConditionCall>> customConditionCalls, Optional<Fault> fault) {

        public Engine {
            messages = messages.stream().sorted().toList();
            partialMessages = partialMessages.map(list -> list.stream().sorted().toList());
            customConditionCalls = customConditionCalls.map(List::copyOf);
            requireRaisedChannelAbsent(engine, messages, computations, partialMessages, computeResults, fault);
        }

        /**
         * A PUBLISHED channel is a COMPLETE channel — the invariant, checked rather than merely left unwritten.
         *
         * <p>The raise ends the block's observation, so the channel it happened in was never finished and must
         * not appear: an empty message list beside a {@code validateFull} raise reads as "the kernel found no
         * problem", which is the one misreading this whole artifact is built to prevent. The two loci that
         * precede every channel ({@code documentInstance}, {@code engineBuild}) therefore admit no channel at
         * all, which is what {@link #faulted} builds.
         *
         * <p><b>Channels that FINISHED before the raise survive it, and that is a correction.</b> Until
         * 2026-09-22 the rule was the stronger "a faulted block carries no channel", justified by "a strategy
         * that cannot validate a document is not one whose compute answer means anything" — true in that
         * direction and false in the other, because a strategy that validated a document perfectly well may
         * still raise later. Applied symmetrically it broke the invariant `observe` exists for: a12-kernel-lean
         * asked an over-capacity row for {@code computeResults}, whose {@code applyTo} copy the kernel refuses
         * to serialize, and LOST the two validation messages the same row returns without it — so ADDING an
         * observation removed one they already had. Measured both strategies at
         * {@link KernelProbeOverCapacityFaultTest}.
         */
        private static void requireRaisedChannelAbsent(String engine, List<Message> messages,
                Optional<Computations> computations, Optional<List<Message>> partialMessages,
                Optional<ComputeResults> computeResults, Optional<Fault> fault) {
            if (fault.isEmpty()) {
                return;
            }
            String locus = fault.get().observation();
            // Empty means a locus that precedes every channel, so every channel must be absent. The row-level
            // observations map one-to-one onto the channel each produces; a MODEL-level one never rides an
            // engine block, so it can only be a coding error here and is refused as an unpublishable channel.
            boolean published = KernelRuntimeProbe.Observation.find(locus)
                    .map(observation -> switch (observation) {
                        case VALIDATE_FULL -> !messages.isEmpty();
                        case COMPUTE -> computations.isPresent();
                        case VALIDATE_PART -> partialMessages.isPresent();
                        case COMPUTE_RESULTS -> computeResults.isPresent();
                        case MANDATORY_INFORMATION, DYNAMIC_ENUMERATIONS -> true;
                    })
                    .orElse(!(messages.isEmpty() && computations.isEmpty()
                            && partialMessages.isEmpty() && computeResults.isEmpty()));
            if (published) {
                throw new IllegalArgumentException("an engine block may not publish the channel it raised in: "
                        + engine + " raised at " + locus + " yet reports " + messages.size()
                        + " message(s), computations=" + computations.isPresent()
                        + ", partialMessages=" + partialMessages.isPresent()
                        + ", computeResults=" + computeResults.isPresent());
            }
        }

        /**
         * Whether {@code validateFull} was read to completion — false exactly when the raise happened at or
         * before it, which is when {@code messages} must be OMITTED rather than written as {@code []}.
         *
         * <p>Derived rather than stored, so the writer and the invariant above cannot disagree: {@code messages}
         * is a {@code List} and an unread channel and an empty one are the same value, so presence is a fact
         * about the FAULT, not about the list.
         */
        public boolean messagesObserved() {
            return fault.map(raised -> KernelRuntimeProbe.Observation.find(raised.observation())
                    .filter(observation -> observation != KernelRuntimeProbe.Observation.VALIDATE_FULL)
                    .isPresent()).orElse(true);
        }

        /**
         * Start an observed block. Every optional channel arrives through a NAMED setter rather than a
         * positional argument, because they are all {@code Optional} of something and a transposed pair would
         * compile, run, and publish one channel's content under another channel's key.
         */
        public static Builder of(String engine) {
            return new Builder(engine);
        }

        /** The strategy raised instead of observing — the whole block IS the fault. */
        public static Engine faulted(String engine, Fault fault) {
            return new Engine(engine, List.of(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.of(fault));
        }

        /** The public construction path for an observed block; an unset channel means "not observed". */
        public static final class Builder {

            private final String engine;
            private List<Message> messages = List.of();
            private Optional<Computations> computations = Optional.empty();
            private Optional<List<Message>> partialMessages = Optional.empty();
            private Optional<ComputeResults> computeResults = Optional.empty();
            private Optional<List<ConditionCall>> customConditionCalls = Optional.empty();
            private Optional<Fault> fault = Optional.empty();

            private Builder(String engine) {
                this.engine = engine;
            }

            public Builder messages(List<Message> observed) {
                this.messages = observed;
                return this;
            }

            public Builder computations(Optional<Computations> observed) {
                this.computations = observed;
                return this;
            }

            public Builder partialMessages(Optional<List<Message>> observed) {
                this.partialMessages = observed;
                return this;
            }

            public Builder computeResults(Optional<ComputeResults> observed) {
                this.computeResults = observed;
                return this;
            }

            public Builder customConditionCalls(Optional<List<ConditionCall>> recorded) {
                this.customConditionCalls = recorded;
                return this;
            }

            /**
             * The raise that ended this block's observation, leaving whatever channels had already completed.
             * Set it on a block that observed nothing and the result equals {@link Engine#faulted}.
             */
            public Builder fault(Fault raised) {
                this.fault = Optional.of(raised);
                return this;
            }

            public Engine build() {
                return new Engine(engine, messages, computations, partialMessages, computeResults,
                        customConditionCalls, fault);
            }
        }

        // partialMessages joins the agreement comparison deliberately: whether the static-JAVA codegen path
        // gates a relevant set identically to dynamic-Groovy is exactly the kind of split this probe exists to
        // surface, and leaving it out would report enginesAgree=true over an unread channel. The fault joins it
        // for the same reason and one more: two strategies raising the same fault IS an agreement, and a run
        // that could not state it left the reader with "one of them faults" (a12-kernel-lean, 2026-09-04).
        private Observation observation() {
            return new Observation(messages, computations, partialMessages, computeResults, customConditionCalls,
                    fault);
        }
    }

    /**
     * A kernel raise, published where the channels it prevented would have gone. The run keeps going and fails
     * at the end, so one bad row costs a request neither its clean rows nor its second strategy.
     *
     * <p><b>{@code observation} names the locus, not just the row.</b> It is the request's own {@code observe}
     * vocabulary for a channel that raised ({@code validateFull}, {@code compute}, {@code validatePart}), plus
     * two loci a request cannot ask for: {@code documentInstance} when the row's document could not be
     * deserialized at all, and {@code engineBuild} when the strategy's engine could not be built — the latter
     * appears on every row of the request, because that is the blast radius of a build that failed once.
     *
     * <p><b>{@code chain} is the kernel's own text, thrown exception first.</b> Types are stable across
     * machines; a MESSAGE is whatever the kernel wrote, and if a given raise embeds an environment-specific
     * detail (a temp path from the static-Java codegen, say) then a fault-carrying artifact is byte-stable only
     * as far as that text is. Stated rather than normalized: a message we rewrote would not be the observation.
     */
    public record Fault(String observation, List<Raise> chain) {

        public Fault {
            chain = List.copyOf(chain);
            if (chain.isEmpty()) {
                throw new IllegalArgumentException("a fault carries at least the raise itself: " + observation);
            }
        }

        /** One link of a cause chain: the exception's class name and the message it carried, if any. */
        public record Raise(String type, Optional<String> message) {
        }
    }

    /**
     * One strategy's {@code compute()} observation of a row: what each computed field became, plus the operand
     * formal errors that would otherwise be invisible.
     *
     * <p><b>The two lists are separate channels, not two views of one.</b> {@code formalErrorsInOperands} is the
     * kernel's PRE-COMPUTATION formal validation of operand fields — per {@code IDocumentRtService.compute},
     * every <em>non-computed</em> field that is an operand of any computation is validated first, and
     * "regardless of the formal errors found, computations will be performed. Any computations relying directly
     * or indirectly on invalid fields will be skipped."
     *
     * <p>So it is an <b>inventory, not a causal attribution</b>: it is emitted whether or not anything was
     * skipped and it names no edge to the outcome it explains. What it adds over the three buckets is real
     * nonetheless — a cleared outcome accompanied by an operand error and one accompanied by none are different
     * kernel facts, and the outcome alone cannot tell them apart — but a consumer that wants the edge joins the
     * two channels itself, leaning on the kernel's skip guarantee. The exact operand set is the kernel's
     * {@code CalculationOperandsCollector} (it includes fields merely CONTAINED by a referenced group), locked
     * by {@link ComputationEagerPrepassLawsTest}.
     */
    public record Computations(List<Outcome> outcomes, List<Message> formalErrorsInOperands) {

        public Computations {
            outcomes = outcomes.stream().sorted().toList();
            formalErrorsInOperands = formalErrorsInOperands.stream().sorted().toList();
        }
    }

    /**
     * One strategy's {@code compute()} RESULT observation: the kernel's own instance collections, and the
     * document those results apply to. Present exactly when the request asked for {@code computeResults}.
     *
     * <p><b>Why this is not a second view of {@link Computations}.</b> That block is the kernel's <em>change</em>
     * projection: its {@code outcomes} come from {@code getComputedFieldInstancesWithChanges} ∪
     * {@code …WithErrors} ∪ {@code getClearedFieldInstances}. The first of those is, per the kernel's own
     * javadoc, a strict subset of {@code getComputedFieldInstancesWithoutErrors} — the instances whose computed
     * value <em>differs</em> from the document's. So a computation that successfully recomputed a value the
     * document already held appears in NO channel of {@code computations}, and {@link #withoutErrors} is the
     * only place it is visible.
     *
     * <p><b>{@link #applied} is the observable a separate-destination computation otherwise has none of.</b>
     * The outcome channels report what was computed; they never report what the document then holds. This is
     * A12's canonical Document JSON after {@code IDocumentComputationResult.applyTo(DocumentV2)}, embedded as
     * an object rather than a string so one JSON parser reads the whole artifact.
     *
     * <p><b>{@link #errored} carries the per-instance message</b> {@code IComputedFieldInstance.getErrorMessage()},
     * which {@link Outcome}'s {@code errored} flag records the existence of but cannot carry — the channel
     * KERNEL-PROBE-SPEC named as unexposed until this block existed.
     */
    public record ComputeResults(List<ResultInstance> withoutErrors, List<ResultInstance> errored,
            JsonNode applied) {

        public ComputeResults {
            withoutErrors = withoutErrors.stream().sorted().toList();
            errored = errored.stream().sorted().toList();
        }
    }

    /**
     * One instance out of a result collection: its published address, its computed text, and — for an errored
     * instance — the kernel message that instance itself carries.
     *
     * <p>{@code value} is forwarded rather than normalized, unlike {@link Outcome}'s. The normalization there
     * exists to keep a clear distinguishable from a computed empty string, and neither collection published
     * here contains a cleared instance, so there is nothing to alias.
     */
    public record ResultInstance(String field, Optional<String> value, Optional<Message> errorMessage)
            implements Comparable<ResultInstance> {

        private static final Comparator<ResultInstance> ORDER = Comparator.comparing(ResultInstance::field)
                .thenComparing(instance -> instance.value().isPresent())
                .thenComparing(instance -> instance.value().orElse(""))
                .thenComparing(instance -> instance.errorMessage().map(Message::code).orElse(""));

        @Override
        public int compareTo(ResultInstance other) {
            return ORDER.compare(this, other);
        }
    }

    /**
     * What one computed field instance became under {@code compute()}, in three channels that stay separate:
     * {@code value} is the computed text, {@code cleared} that the kernel emptied a filled target without
     * computing one, {@code errored} that the value was formally invalid for its target.
     *
     * <p><b>No pair collapses.</b> A cleared field is not a field that computed the empty string — which is why
     * {@code value} is <em>absent</em> on a clear and {@code ""} really does mean a computed empty string. That
     * absence is a DELIBERATE normalization rather than a passthrough: a cleared instance's
     * {@code getStringValue()} is {@code Optional.of("")} for every clear (measured 2026-08-12, locked by
     * {@link ComputeResultChannelsLawsTest}), so the kernel's own string here is a constant carrying no
     * information, and forwarding it would alias the two states. And an errored instance can carry a value: the
     * kernel returns an out-of-range computed number and flags it (KERNEL-FINDINGS `kf19`), so {@code errored}
     * is not "no value" either.
     */
    public record Outcome(String field, Optional<String> value, boolean cleared, boolean errored)
            implements Comparable<Outcome> {

        /**
         * A total order over every published channel, for the reason {@link Message} sorts: the kernel
         * guarantees no emission order across its result channels, and an artifact pinned by SHA-256 cannot
         * depend on one. Value <em>presence</em> is ordered before value text, so an absent value and a computed
         * empty string cannot tie.
         */
        private static final Comparator<Outcome> ORDER = Comparator.comparing(Outcome::field)
                .thenComparing(Outcome::cleared)
                .thenComparing(Outcome::errored)
                .thenComparing(outcome -> outcome.value().isPresent())
                .thenComparing(outcome -> outcome.value().orElse(""));

        @Override
        public int compareTo(Outcome other) {
            return ORDER.compare(this, other);
        }
    }

    /**
     * One fired validation message, carrying the key names of a {@code model eval} {@code messages[]} entry —
     * {@code code}, {@code field}, {@code severity}, {@code type}, {@code message}, {@code referenced},
     * {@code fillToFix} — so one parser reads dmtool's runtime output and this artifact alike.
     *
     * <p>The three address channels keep dmtool's published {@code a12-message-address} contract: canonical A12
     * when the address names exactly one cell, and the interpreter house form when it does not (CLI-SPEC §5).
     * That distinction is load-bearing here rather than cosmetic — a starred group operand's expanded reference
     * carries a wildcard and cannot be canonical, and telling that apart from a concrete row is a question this
     * probe exists to answer.
     */
    public record Message(String code, String field, String severity, String type, String message,
            List<String> referenced, List<String> fillToFix) implements Comparable<Message> {

        /**
         * A total order over every published channel. Ordering on {@code code} alone would leave two firings of
         * one per-row rule in kernel emission order, which nothing guarantees to be stable.
         *
         * <p>The address channels join on {@code \0} rather than a space because no address can contain it, so
         * two different splits of the same characters cannot produce one sort key. It is written as the ESCAPE:
         * the byte itself makes the source file non-text, which costs every later reader a readable
         * {@code git diff} and a {@code grep} of this class.
         */
        private static final Comparator<Message> ORDER = Comparator.comparing(Message::code)
                .thenComparing(Message::field)
                .thenComparing(Message::type)
                .thenComparing(Message::severity)
                .thenComparing(m -> String.join("\0", m.referenced()))
                .thenComparing(m -> String.join("\0", m.fillToFix()))
                .thenComparing(Message::message);

        /**
         * The two address channels are sorted, for the same reason {@link Engine} sorts its messages: the
         * kernel guarantees no emission order, and an artifact whose bytes depend on one cannot be pinned. The
         * order is not information being discarded — every comparison of these channels in this repository is
         * already a set or a sorted list (the differentials, the KF183 table), and no consumer reads position.
         */
        public Message {
            referenced = referenced.stream().sorted().toList();
            fillToFix = fillToFix.stream().sorted().toList();
        }

        @Override
        public int compareTo(Message other) {
            return ORDER.compare(this, other);
        }
    }

    /** The artifact's exact bytes, as a UTF-8 string. */
    public String toJson() {
        // WRITE_BIGDECIMAL_AS_PLAIN because an embedded applied document carries the kernel's own decimals,
        // and A12 never writes a number in scientific notation (KERNEL-FINDINGS kf1's write stage).
        ObjectMapper mapper = new ObjectMapper().enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN);
        ObjectNode root = mapper.createObjectNode();
        root.put("schemaVersion", SCHEMA_VERSION);

        ObjectNode producer = root.putObject("producer");
        producer.put("dmtoolVersion", header.dmtoolVersion());
        ObjectNode kernel = producer.putObject("kernel");
        kernel.put("builtAgainst", header.kernelBuiltAgainst());
        kernel.put("runtime", header.kernelRuntime());
        ObjectNode source = producer.putObject("source");
        source.put("revision", header.sourceRevision());
        source.put("state", header.sourceState());

        ObjectNode request = root.putObject("request");
        request.put("model", header.model());
        request.put("locale", header.locale());
        header.clock().ifPresent(clock -> request.put("clock", clock));

        ArrayNode rowNodes = root.putArray("rows");
        for (Row row : rows) {
            ObjectNode rowNode = rowNodes.addObject();
            rowNode.put("name", row.name());
            rowNode.put("enginesAgree", row.enginesAgree());
            ArrayNode engineNodes = rowNode.putArray("engines");
            for (Engine engine : row.engines()) {
                ObjectNode engineNode = engineNodes.addObject();
                engineNode.put("engine", engine.engine());
                // The raise is written FIRST, before the channels that survived it, so every artifact that
                // could exist before a completed channel outlived a later channel's raise stays byte-identical:
                // such a block carried `fault` and nothing else.
                engine.fault().ifPresent(raised -> write(engineNode.putObject("fault"), raised));
                if (engine.messagesObserved()) {
                    // Omitted rather than `[]` when the raise reached validateFull: an empty list there would
                    // publish "the kernel found no problem" for a channel that never finished.
                    ArrayNode messageNodes = engineNode.putArray("messages");
                    engine.messages().forEach(message -> write(messageNodes.addObject(), message));
                }
                engine.computations().ifPresent(computations -> write(
                        engineNode.putObject("computations"), computations));
                // Rendered even when EMPTY: under noneRelevant coverage an empty list is the measurement.
                engine.partialMessages().ifPresent(partial -> {
                    ArrayNode partialNodes = engineNode.putArray("partialMessages");
                    partial.forEach(message -> write(partialNodes.addObject(), message));
                });
                engine.computeResults().ifPresent(results -> write(
                        engineNode.putObject("computeResults"), results));
                engine.customConditionCalls().ifPresent(calls -> {
                    ArrayNode callNodes = engineNode.putArray("customConditionCalls");
                    calls.forEach(call -> write(callNodes.addObject(), call));
                });
            }
        }

        // Written LAST and only when observed, so a request that does not ask for it keeps its bytes: the
        // model-level block is not a row and belongs to the request as a whole, not to any document.
        mandatoryInformation.ifPresent(sets -> {
            ObjectNode node = root.putObject("mandatoryInformation");
            // Iterated over the ENUM, not the map: the key order is then the declaration order whatever
            // Map implementation reached here, which is what a SHA-256 pin needs.
            for (KernelAdapter.MandatorySet set : KernelAdapter.MandatorySet.values()) {
                ArrayNode names = node.putArray(set.wireName());
                sets.getOrDefault(set, List.of()).forEach(names::add);
            }
        });
        // The second model-level block, on the same terms: written only when observed, and its own key order
        // fixed here rather than inherited from whatever Map arrived. `valuesByField` is already a TreeMap from
        // the adapter, so the field order is the field NAMES' — deterministic without sorting again here.
        dynamicEnumerations.ifPresent(derived -> {
            ObjectNode node = root.putObject("dynamicEnumerations");
            ObjectNode values = node.putObject("valuesByField");
            derived.valuesByField().forEach((field, allowed) -> {
                ArrayNode list = values.putArray(field);
                allowed.forEach(list::add);
            });
            ArrayNode diagnostics = node.putArray("diagnostics");
            derived.diagnostics().forEach(diagnostic -> {
                ObjectNode entry = diagnostics.addObject();
                entry.put("code", diagnostic.code().orElse(null));
                entry.put("severity", diagnostic.severity().name());
                entry.put("rule", diagnostic.where().rule());
                entry.put("message", diagnostic.summary());
            });
        });
        return render(mapper, root);
    }

    private static void write(ObjectNode node, ConditionCall call) {
        node.put("observation", call.observation().wireName());
        node.put("name", call.name());
        call.arguments().relevant().ifPresentOrElse(relevant -> {
            ArrayNode addresses = node.putArray("relevant");
            relevant.forEach(addresses::add);
        }, () -> node.putNull("relevant"));
        ArrayNode incorrect = node.putArray("formallyIncorrect");
        call.arguments().formallyIncorrect().forEach(incorrect::add);
        node.put("errorEntity", call.arguments().errorEntity());
        node.put("result", call.result());
    }

    private static void write(ObjectNode messageNode, Message message) {
        messageNode.put("code", message.code());
        messageNode.put("field", message.field());
        messageNode.put("severity", message.severity());
        messageNode.put("type", message.type());
        messageNode.put("message", message.message());
        ArrayNode referenced = messageNode.putArray("referenced");
        message.referenced().forEach(referenced::add);
        ArrayNode fillToFix = messageNode.putArray("fillToFix");
        message.fillToFix().forEach(fillToFix::add);
    }

    private static void write(ObjectNode node, Fault fault) {
        node.put("observation", fault.observation());
        ArrayNode chain = node.putArray("chain");
        for (Fault.Raise raise : fault.chain()) {
            ObjectNode raiseNode = chain.addObject();
            raiseNode.put("type", raise.type());
            // A throwable legitimately carries no message; absent rather than "" for the same reason `value` is.
            raise.message().ifPresent(message -> raiseNode.put("message", message));
        }
    }

    private static void write(ObjectNode node, Computations computations) {
        ArrayNode outcomeNodes = node.putArray("outcomes");
        for (Outcome outcome : computations.outcomes()) {
            ObjectNode outcomeNode = outcomeNodes.addObject();
            outcomeNode.put("field", outcome.field());
            outcomeNode.put("cleared", outcome.cleared());
            outcomeNode.put("errored", outcome.errored());
            // Written LAST and only when there is one: `value` absent and `value: ""` are different kernel
            // facts, and putting the conditional key at the end keeps the others' order the same either way.
            outcome.value().ifPresent(value -> outcomeNode.put("value", value));
        }
        ArrayNode formalErrors = node.putArray("formalErrorsInOperands");
        computations.formalErrorsInOperands().forEach(message -> write(formalErrors.addObject(), message));
    }

    private static void write(ObjectNode node, ComputeResults results) {
        writeInstances(node.putArray("withoutErrors"), results.withoutErrors());
        writeInstances(node.putArray("errored"), results.errored());
        node.set("applied", results.applied());
    }

    private static void writeInstances(ArrayNode nodes, List<ResultInstance> instances) {
        for (ResultInstance instance : instances) {
            ObjectNode instanceNode = nodes.addObject();
            instanceNode.put("field", instance.field());
            // Conditional keys last, for the reason an Outcome's `value` is: absent and present-empty are
            // different kernel facts, and keeping them at the end leaves the others' order the same either way.
            instance.value().ifPresent(value -> instanceNode.put("value", value));
            instance.errorMessage().ifPresent(message -> write(instanceNode.putObject("errorMessage"), message));
        }
    }

    /**
     * Pretty-print with an explicit {@code "\n"} indenter on both object and array bodies. Jackson's default
     * indenter uses the platform line separator, which would make the same observation two different SHA-256s
     * depending on where it ran.
     */
    private static String render(ObjectMapper mapper, ObjectNode root) {
        DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter()
                .withObjectIndenter(indenter)
                .withArrayIndenter(indenter);
        try {
            return mapper.writer(printer).writeValueAsString(root) + "\n";
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("failed to render the kernel-probe artifact", e);
        }
    }
}

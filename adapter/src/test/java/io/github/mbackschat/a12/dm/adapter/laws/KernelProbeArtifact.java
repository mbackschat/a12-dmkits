package io.github.mbackschat.a12.dm.adapter.laws;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

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
public record KernelProbeArtifact(Header header, List<Row> rows) {

    /**
     * The artifact shape's own version, bumped when a key's presence or meaning changes.
     *
     * <p><b>Why the optional {@code computations} block did NOT bump it.</b> A consumer only receives that block
     * by asking for it ({@code observe: ["validateFull", "compute"]}), so no artifact any existing consumer can
     * obtain changes shape, and no existing key changes meaning. Bumping would instead have broken the harder
     * guarantee: a request that does not ask for compute must produce <em>byte-identical</em> output, because
     * consumers pin these bytes by SHA-256, and the version rides in the first line of every artifact.
     */
    public static final int SCHEMA_VERSION = 1;

    public KernelProbeArtifact {
        rows = List.copyOf(rows);
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
    private record Observation(List<Message> messages, Optional<Computations> computations) {
    }

    /**
     * One strategy's complete observation of a row. {@code engine} uses the corpus strategy vocabulary
     * ({@code kernel-groovy-dynamic}, {@code kernel-java-static}) rather than a probe-local one.
     *
     * <p>{@code computations} is present exactly when the request asked for {@code compute}. Absent is
     * <b>not</b> "nothing computed": it is "not observed", and the distinction is the same one the probe's
     * refusal of an inconsistent model protects — an empty observation that reads as an answer is worse than no
     * answer. So the block is omitted from the JSON entirely rather than rendered empty.
     */
    public record Engine(String engine, List<Message> messages, Optional<Computations> computations) {

        public Engine {
            messages = messages.stream().sorted().toList();
        }

        /** The strategy observed for validation only — the shape every request produced before compute existed. */
        public Engine(String engine, List<Message> messages) {
            this(engine, messages, Optional.empty());
        }

        private Observation observation() {
            return new Observation(messages, computations);
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
        ObjectMapper mapper = new ObjectMapper();
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
                ArrayNode messageNodes = engineNode.putArray("messages");
                engine.messages().forEach(message -> write(messageNodes.addObject(), message));
                engine.computations().ifPresent(computations -> write(
                        engineNode.putObject("computations"), computations));
            }
        }
        return render(mapper, root);
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

package io.github.mbackschat.a12.dm.adapter.laws;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mgmtp.a12.kernel.md.document.apiV2.DocumentPointer;
import com.mgmtp.a12.kernel.md.document.apiV2.PartiallyKnownDocumentMultiPointer;
import com.mgmtp.a12.kernel.md.rt.api.IMessage;

import io.github.mbackschat.a12.dm.adapter.KernelConsistencyOracle;
import io.github.mbackschat.a12.dm.core.Notification;
import io.github.mbackschat.a12.dm.core.Severity;
import io.github.mbackschat.a12.dm.interpreter.A12PartiallyKnownMultiPointer;
import io.github.mbackschat.a12.dm.interpreter.A12Pointer;
import io.github.mbackschat.a12.dm.interpreter.A12PointerFormat;
import io.github.mbackschat.a12.dm.interpreter.eval.PointerKt;
import io.github.mbackschat.a12.dm.rulekit.validate.KernelVersion;

/**
 * Runs the <b>real kernel's runtime entry points</b> over a supplied model and documents and writes a
 * deterministic observation artifact — the JVM-only, non-shipped route by which a consumer asks a kernel runtime
 * question without a bespoke Kotlin test per question. Driven by {@code ./gradlew :adapter:kernelProbe
 * -Prequest=<request.json> -Pout=<artifact.json>}.
 *
 * <p><b>What is observed is the request's choice</b> ({@link Observation}): {@code validateFull} always, and
 * {@code compute} when asked for. A request that does not ask for compute produces the artifact it always
 * produced, byte for byte, because consumers pin these bytes by SHA-256.
 *
 * <p><b>It is a measurement instrument, not a verb.</b> Kernel runtime evaluation does Groovy runtime code
 * generation and is not AOT-compilable, which is exactly why dmtool's runtime verbs run the kernel-free
 * {@code :interpreter} and why the {@code --kernel} eval option was retired (ARCHITECTURE "The Groovy wall").
 * Living as a test-source entry point behind a Gradle task keeps that boundary untouched: no verb, no change to
 * {@code Cli.JVM_ONLY_VERBS}, no Groovy on the native classpath.
 *
 * <p><b>Both kernel strategies are observed, separately.</b> Dynamic Groovy is the production-normative anchor
 * and generated Java is co-evidence; a split between them is a kernel fact to record, so the artifact keeps one
 * block per strategy and derives {@code enginesAgree} rather than merging them ({@link KernelProbeArtifact}).
 *
 * <p><b>A statically refused model refuses here too.</b> The request's model runs through the kernel's own
 * consistency oracle — the one behind {@code rule check} — before any document is evaluated, and an
 * {@code ERROR} notification fails the run with its diagnostics. An inconsistent model would otherwise evaluate
 * to nothing at all, and an empty observation reads as "nothing fired".
 *
 * <p>Distinct from {@link KernelProbe}, which is the in-JVM probe-first harness for developing an operator
 * against the kernel; this one is the file-in/file-out route for an external consumer.
 *
 * <p>Contract: {@code docs/KERNEL-PROBE-SPEC.md}.
 */
public final class KernelRuntimeProbe {

    /** The corpus strategy vocabulary (CONFORMANCE-CORPUS-SPEC §11), reused rather than re-coined. */
    static final String GROOVY_DYNAMIC = "kernel-groovy-dynamic";
    static final String JAVA_STATIC = "kernel-java-static";

    /**
     * The default evaluation locale. A request may override it, and must for a model that does not declare
     * {@code en_US}: the kernel refuses a locale the model has not declared outright — measured 2026-08-11,
     * {@code IllegalArgumentException: Locale 'en_US' is not supported in the document model '…'} — so a fixed
     * pin would make a German-only model unprobeable rather than merely produce English text.
     */
    private static final Locale DEFAULT_LOCALE = Locale.US;

    private static final String USAGE =
            "usage: ./gradlew :adapter:kernelProbe -Prequest=<request.json> -Pout=<artifact.json>";

    private KernelRuntimeProbe() {
    }

    /** One named row of the request: a document, evaluated on every strategy. */
    public record Row(String name, String documentJson) {
    }

    /**
     * A kernel runtime entry point the probe can be asked to observe. Named on the wire exactly as the kernel
     * names the method, so a request says which kernel API produced a block rather than a probe-local synonym.
     */
    public enum Observation {

        /** {@code IDocumentRtService.validateFull} — the message channel, always observed. */
        VALIDATE_FULL("validateFull"),

        /** {@code IDocumentRtService.compute} — the computed-field outcomes and the operand formal errors. */
        COMPUTE("compute");

        private final String wireName;

        Observation(String wireName) {
            this.wireName = wireName;
        }

        /** The name a request spells. */
        public String wireName() {
            return wireName;
        }

        static Observation of(String wireName) {
            for (Observation observation : values()) {
                if (observation.wireName.equals(wireName)) {
                    return observation;
                }
            }
            throw new IllegalArgumentException("'observe' names an unknown observation '" + wireName
                    + "'; the probe observes " + names());
        }

        static String names() {
            return Arrays.stream(values()).map(Observation::wireName).collect(Collectors.joining(", "));
        }
    }

    /**
     * A parsed request: the model to evaluate, the evaluation locale, an optional pinned clock, what to observe,
     * and the rows.
     */
    public record Request(String modelRef, String modelJson, Locale locale, Optional<Date> clock,
            Set<Observation> observe, List<Row> rows) {

        /**
         * The {@code validateFull} invariant lives HERE, not in the request parser, because this record is
         * public: a caller that built one directly would otherwise produce engine blocks whose {@code messages}
         * was empty because nobody looked — indistinguishable from a row where nothing fired, which is the
         * failure mode §5's refusal exists to prevent. Checked before {@code EnumSet.copyOf}, whose own
         * "Collection is empty" would otherwise speak in place of the domain rule.
         */
        public Request {
            if (!observe.contains(Observation.VALIDATE_FULL)) {
                throw new IllegalArgumentException("'observe' must include '"
                        + Observation.VALIDATE_FULL.wireName() + "': the artifact carries no key saying a "
                        + "channel went unobserved, so an empty message list nobody looked at would be "
                        + "indistinguishable from one where nothing fired. Received " + observe);
            }
            observe = Collections.unmodifiableSet(EnumSet.copyOf(observe));
            rows = List.copyOf(rows);
        }
    }

    /**
     * The model the request names is not kernel-consistent, so no runtime observation was attempted. Carries the
     * oracle's own notifications: a refusal that named no diagnostics would be no more useful than silence.
     */
    public static final class Refused extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final transient List<String> diagnostics;

        Refused(String modelRef, List<String> diagnostics) {
            super("the kernel refuses the model '" + modelRef + "' as inconsistent, so no runtime observation "
                    + "was made:\n  " + String.join("\n  ", diagnostics));
            this.diagnostics = List.copyOf(diagnostics);
        }

        /** The kernel consistency notifications that caused the refusal, in the kernel's order. */
        public List<String> diagnostics() {
            return diagnostics;
        }
    }

    // ---- request ---------------------------------------------------------------------------------

    /**
     * Read a request file. Relative {@code model} and {@code document} references resolve against the request
     * file's own directory, so a request plus its inputs is one movable bundle.
     */
    public static Request parse(Path requestFile) throws IOException {
        JsonNode root = new ObjectMapper().readTree(Files.readString(requestFile, StandardCharsets.UTF_8));
        Path base = requestFile.toAbsolutePath().getParent();

        String modelRef = required(root, "model").asText();
        Locale locale = root.hasNonNull("locale") ? locale(root.get("locale").asText()) : DEFAULT_LOCALE;
        Optional<Date> clock = root.hasNonNull("clock") ? Optional.of(clock(root.get("clock").asText()))
                : Optional.empty();
        // Read BEFORE any file is opened: a syntactic mistake in the request should be reported as itself,
        // not masked by whichever document path happens to be missing too.
        Set<Observation> observe = observe(root);

        JsonNode rowNodes = required(root, "rows");
        if (!rowNodes.isArray() || rowNodes.isEmpty()) {
            throw new IllegalArgumentException("'rows' must be a non-empty array of named rows");
        }
        Set<String> names = new LinkedHashSet<>();
        List<Row> rows = new ArrayList<>();
        for (JsonNode rowNode : rowNodes) {
            String name = required(rowNode, "name").asText();
            if (!names.add(name)) {
                throw new IllegalArgumentException("duplicate row name '" + name
                        + "'; a row name identifies an observation and must be unique");
            }
            rows.add(new Row(name, document(rowNode, base, name)));
        }
        return new Request(modelRef, read(base, modelRef), locale, clock, observe, rows);
    }

    /**
     * What the request asked the kernel for, defaulting to validation alone — the shape every request had
     * before {@code compute} was observable, and the reason an existing request's artifact is unchanged.
     *
     * <p>The {@code validateFull} requirement itself is {@link Request}'s invariant, not this parser's; here
     * only the wire-level shape is decided.
     */
    private static Set<Observation> observe(JsonNode root) {
        if (!root.hasNonNull("observe")) {
            return EnumSet.of(Observation.VALIDATE_FULL);
        }
        JsonNode node = root.get("observe");
        if (!node.isArray() || node.isEmpty()) {
            throw new IllegalArgumentException("'observe' must be a non-empty array naming what to observe ("
                    + Observation.names() + "), or be omitted for " + Observation.VALIDATE_FULL.wireName());
        }
        Set<Observation> observe = EnumSet.noneOf(Observation.class);
        node.forEach(entry -> observe.add(Observation.of(entry.asText())));
        return observe;
    }

    /** A12 spells a locale {@code language_COUNTRY} ({@code en_US}, {@code de_DE}), not as a BCP-47 tag. */
    private static Locale locale(String text) {
        String[] parts = text.split("_");
        if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            throw new IllegalArgumentException("'locale' must be A12's language_COUNTRY form, such as en_US or "
                    + "de_DE, and must be one the model DECLARES — the kernel refuses any other outright. "
                    + "Received '" + text + "'");
        }
        return Locale.of(parts[0], parts[1]);
    }

    /** Exactly one of {@code document} (a path) or {@code documentInline} (an embedded A12 Document JSON). */
    private static String document(JsonNode rowNode, Path base, String name) throws IOException {
        boolean hasPath = rowNode.hasNonNull("document");
        boolean hasInline = rowNode.hasNonNull("documentInline");
        if (hasPath == hasInline) {
            throw new IllegalArgumentException("row '" + name
                    + "' must carry exactly one of 'document' (a path) or 'documentInline' (an embedded "
                    + "A12 Document JSON), not " + (hasPath ? "both" : "neither"));
        }
        return hasPath ? read(base, rowNode.get("document").asText())
                : rowNode.get("documentInline").toString();
    }

    private static JsonNode required(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("the request is missing the required key '" + key + "'");
        }
        return value;
    }

    private static Date clock(String text) {
        try {
            return Date.from(Instant.parse(text));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "'clock' must be an ISO-8601 instant such as 2026-08-11T00:00:00Z, but was '" + text + "'", e);
        }
    }

    private static String read(Path base, String ref) throws IOException {
        Path path = Path.of(ref);
        return Files.readString(path.isAbsolute() ? path : base.resolve(path), StandardCharsets.UTF_8);
    }

    // ---- observation -----------------------------------------------------------------------------

    /**
     * Evaluate every row on both kernel strategies.
     *
     * @throws Refused if the kernel's consistency oracle reports an {@code ERROR} on the request's model
     */
    public static KernelProbeArtifact run(Request request, KernelProbeArtifact.Header header) {
        refuseInconsistentModel(request);

        // Built once for the whole request: generating and compiling the kernel's Java validator is the
        // expensive half, and every row shares one model.
        RuntimeLaws.KernelEngine groovyDynamic = RuntimeLaws.kernelEngine(request.modelJson());
        RuntimeLaws.JavaKernelEngine javaStatic = RuntimeLaws.kernelJavaEngine(request.modelJson());
        Date clock = request.clock().orElse(null);
        Locale locale = request.locale();

        boolean compute = request.observe().contains(Observation.COMPUTE);

        List<KernelProbeArtifact.Row> rows = new ArrayList<>();
        for (Row row : request.rows()) {
            // One immutable DocumentV2 per strategy, observed by every requested entry point: DocumentV2 is
            // immutable and neither validateFull nor compute writes to it, so a second deserialization would
            // only be a second chance to differ.
            var groovyInstance = groovyDynamic.instance(row.documentJson());
            var javaInstance = javaStatic.instance(row.documentJson());
            rows.add(new KernelProbeArtifact.Row(row.name(), List.of(
                    new KernelProbeArtifact.Engine(GROOVY_DYNAMIC,
                            project(groovyDynamic.validationMessages(groovyInstance, clock, locale)),
                            compute ? Optional.of(project(groovyDynamic.computation(groovyInstance, clock, locale)))
                                    : Optional.empty()),
                    new KernelProbeArtifact.Engine(JAVA_STATIC,
                            project(javaStatic.validationMessages(javaInstance, clock, locale)),
                            compute ? Optional.of(project(javaStatic.computation(javaInstance, clock, locale)))
                                    : Optional.empty()))));
        }
        return new KernelProbeArtifact(header, rows);
    }

    /** The consistency gate — the same oracle {@code rule check} reports {@code KERNEL_CONFIRMED} from. */
    private static void refuseInconsistentModel(Request request) {
        List<String> errors = new KernelConsistencyOracle()
                .checkConsistency(request.modelJson(), modelId -> null).stream()
                .filter(notification -> notification.severity() == Severity.ERROR)
                .map(KernelRuntimeProbe::render)
                .toList();
        if (!errors.isEmpty()) {
            throw new Refused(request.modelRef(), errors);
        }
    }

    private static String render(Notification notification) {
        return notification.severity() + ": " + notification.message();
    }

    private static List<KernelProbeArtifact.Message> project(List<IMessage> messages) {
        return messages.stream().map(KernelRuntimeProbe::project).toList();
    }

    private static KernelProbeArtifact.Message project(IMessage message) {
        return new KernelProbeArtifact.Message(
                message.getErrorCode(),
                address(message.getErrorFieldPointer()),
                message.getSeverity().name(),
                message.getMessageType().name(),
                message.getErrorText(),
                addresses(message.getReferencedFieldsPointers()),
                addresses(message.getRefOmissionErrorResponsiblePointers()));
    }

    /**
     * One strategy's {@code compute()} run. The operand formal errors are projected through the <b>same</b>
     * {@link #project(IMessage)} the message channel uses, so one parser reads both and a consumer comparing an
     * operand error with the validation message it explains is comparing like with like.
     */
    private static KernelProbeArtifact.Computations project(RuntimeLaws.ComputationObservation observation) {
        return new KernelProbeArtifact.Computations(
                observation.outcomes().stream().map(KernelRuntimeProbe::project).toList(),
                project(observation.formalErrorsInOperands()));
    }

    private static KernelProbeArtifact.Outcome project(RuntimeLaws.ComputedInstance instance) {
        return new KernelProbeArtifact.Outcome(
                address(instance.pointer()), instance.value(), instance.cleared(), instance.errored());
    }

    private static List<String> addresses(Collection<PartiallyKnownDocumentMultiPointer> pointers) {
        return pointers.stream().map(KernelRuntimeProbe::address).toList();
    }

    /**
     * dmtool's published {@code a12-message-address} rendering of a kernel message pointer: canonical A12 when
     * the address names exactly one cell, and the interpreter's house form when it does not, decided on the
     * typed value rather than by re-reading a string.
     *
     * <p>The policy owner is {@code cli.interp.A12Pointers.canonical}, whose Javadoc carries the full reasoning.
     * It cannot be called from here — {@code :cli} depends on {@code :adapter}, not the reverse — so this is a
     * second implementation of a one-branch policy, recorded as {@code IAG15} in
     * {@code docs/INTERPRETER-API-GAPS.md} rather than left as silent duplication.
     */
    private static String address(PartiallyKnownDocumentMultiPointer pointer) {
        return address(pointer.fullName(), pointer.repetitionIndexes());
    }

    /**
     * The same policy over a computed field instance's {@link DocumentPointer}. It is a different A12 type from
     * a message pointer — exact rather than partially known — but the artifact publishes <b>one</b> address
     * contract, so it is decided by the same branch rather than by a second rendering rule. In practice an
     * exact pointer takes the canonical arm; running it through the same code is what keeps that a measured
     * outcome instead of an assumption.
     */
    private static String address(DocumentPointer pointer) {
        return address(pointer.fullName(), pointer.repetitionIndexes());
    }

    private static String address(String fullName, List<Integer> repetitionIndexes) {
        A12PartiallyKnownMultiPointer typed =
                A12PartiallyKnownMultiPointer.Companion.of(fullName, repetitionIndexes);
        A12Pointer exact = typed.getRepetitionIndexes().isEmpty() ? null : typed.toExactPointer();
        return exact != null ? A12PointerFormat.INSTANCE.render(exact)
                : PointerKt.formatPointer(fullName, repetitionIndexes);
    }

    // ---- entry point -----------------------------------------------------------------------------

    /**
     * The Gradle task's entry point. Provenance arrives as system properties from the build, single-sourced
     * from {@code scripts/SourceProvenance.java} exactly as {@code :cli}'s version resource is, so the header
     * cannot claim a revision the build did not measure.
     */
    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException(USAGE);
        }
        requirePinnedSource(System.getProperty(SOURCE_STATE_PROPERTY, UNDETERMINED_STATE),
                Boolean.parseBoolean(System.getProperty(REQUIRE_PINNED_PROPERTY, "false")));
        Path requestFile = Path.of(args[0]);
        Path out = Path.of(args[1]);

        Request request = parse(requestFile);
        KernelProbeArtifact artifact = run(request, header(request));
        write(out, artifact.toJson());
        System.out.println("[kernel-probe] " + request.rows().size() + " row(s) → " + out.toAbsolutePath());
    }

    /**
     * The system property carrying the measured working-tree state. Named as a constant because two things read
     * it and they must not diverge: the header that STAMPS it and the gate that REFUSES on it.
     */
    static final String SOURCE_STATE_PROPERTY = "a12.probe.sourceState";

    /**
     * Set by the Gradle task to say that this artifact is meant to be handed over and pinned. Deliberately
     * explicit rather than inferred from the state property's absence: the in-process callers in
     * {@code KernelRuntimeProbeTest} supply no provenance at all, so "absent means refuse" would break every one
     * of them and "absent means allow" would be a loophole nobody re-reads.
     */
    static final String REQUIRE_PINNED_PROPERTY = "a12.probe.requirePinnedSource";

    /** The state's own "we could not tell" spelling — a member of the closed domain, unlike {@code (unknown)}. */
    private static final String UNDETERMINED_STATE = "UNKNOWN";

    private static final String PINNED_STATE = "CLEAN";

    /**
     * Refuses to produce a hand-over artifact whose {@code sourceRevision} names a tree that did not produce it.
     *
     * <p>The header's revision is only meaningful as an address a consumer can check out. On a dirty tree it
     * addresses a tree that never existed anywhere, and on an undetermined one it addresses nothing at all — both
     * are unpinnable, so both refuse. The escape exists because local exploration is legitimate; it permits the
     * run and leaves the stamp alone, so an artifact produced under it still declares what it is.
     */
    static void requirePinnedSource(String sourceState, boolean requirePinned) {
        if (requirePinned && !PINNED_STATE.equals(sourceState)) {
            throw new IllegalStateException("kernel-probe refuses to write a hand-over artifact from a "
                    + sourceState + " working tree: its sourceRevision would name a tree that did not produce "
                    + "these rows. Commit (or stash) first, then re-run. For a local exploration run whose "
                    + "artifact you will not hand over, pass -PprobeAllowUnpinned=true — the artifact still "
                    + "stamps state=" + sourceState + ".");
        }
    }

    /** The producer header, from the build-supplied properties plus the kernel actually on the classpath. */
    static KernelProbeArtifact.Header header(Request request) {
        return new KernelProbeArtifact.Header(
                property("a12.probe.dmtoolVersion"),
                property("a12.probe.kernelBuiltAgainst"),
                Optional.ofNullable(KernelVersion.runtimeVersion()).orElse("(unknown)"),
                property("a12.probe.sourceRevision"),
                // The state's own "we could not tell" spelling, not the generic one: it is a closed domain,
                // shared with dmtool's version surface, and `(unknown)` is not a member of it.
                System.getProperty(SOURCE_STATE_PROPERTY, UNDETERMINED_STATE),
                request.modelRef(),
                request.locale().getLanguage() + "_" + request.locale().getCountry(),
                request.clock().map(clock -> clock.toInstant().toString()));
    }

    private static String property(String key) {
        return System.getProperty(key, "(unknown)");
    }

    private static void write(Path out, String json) {
        try {
            Path parent = out.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(out, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write the kernel-probe artifact to " + out, e);
        }
    }
}

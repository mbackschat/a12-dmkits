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
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldTypeFactory;
import com.mgmtp.a12.kernel.md.document.apiV2.DocumentPointer;
import com.mgmtp.a12.kernel.md.document.apiV2.PartiallyKnownDocumentMultiPointer;
import com.mgmtp.a12.kernel.md.rt.api.IMessage;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.adapter.KernelConsistencyOracle;
import io.github.mbackschat.a12.dm.adapter.testing.KernelMessageAddressProjector;
import io.github.mbackschat.a12.dm.core.Notification;
import io.github.mbackschat.a12.dm.core.Severity;
import io.github.mbackschat.a12.dm.interpreter.A12PartiallyKnownMultiPointer;
import io.github.mbackschat.a12.dm.rulekit.validate.KernelVersion;

/**
 * Runs the <b>real kernel's runtime entry points</b> over a supplied model and documents and writes a
 * deterministic observation artifact — the JVM-only, non-shipped route by which a consumer asks a kernel runtime
 * question without a bespoke Kotlin test per question. Driven by {@code ./gradlew :adapter:kernelProbe
 * -Prequest=<request.json> -Pout=<artifact.json>}.
 *
 * <p><b>What is observed is the request's choice</b> ({@link Observation}), and each observation is either
 * per-ROW or per-MODEL. The row observations are {@code validateFull} — required whenever any row is observed
 * — {@code compute}, {@code computeResults} and {@code validatePart}; the model observations are
 * {@code mandatoryInformation} and {@code dynamicEnumerations}, which read no document and may be asked for
 * alone, with no {@code rows} at all.
 * A request that does not ask for an optional block produces
 * the artifact it always produced, byte for byte, because consumers pin these bytes by SHA-256.
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

    /**
     * Configured exactly like A12's own document mapper ({@code ObjectMapperProvider
     * .createObjectMapperForDocSerializer()}) — {@code USE_BIG_DECIMAL_FOR_FLOATS} with
     * {@code STRIP_TRAILING_BIGDECIMAL_ZEROES} disabled, written back plain — and used for EVERY JSON this
     * class reads or writes; an inline document alone is written back in its nodes' own notation
     * ({@link #document}).
     *
     * <p><b>A default mapper is a measurement defect here, not a style choice.</b> A row's
     * {@code documentInline} is re-serialized out of the parsed request before the kernel ever sees it, so on a
     * default mapper {@code 5.00} arrives as a {@code DoubleNode}, is written back {@code 5.0}, and the kernel
     * then stores a differently-scaled value than the request wrote. The scale is not cosmetic: the runtime
     * renders a NumberType's text from the stored {@code BigDecimal}, and its length bound counts the characters
     * of that text, so a stored {@code 250} and a stored {@code 250.00} fire different codes on one field
     * (KERNEL-FINDINGS KF1). A row given by PATH never had the defect, because the file's bytes reach the
     * kernel unparsed — which is what makes the two ingresses a control pair.
     */
    private static final ObjectMapper DOCUMENT_MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .configure(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false)
            .enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN);

    private KernelRuntimeProbe() {
    }

    /**
     * One named row of the request: a document, evaluated on every strategy.
     *
     * <p>{@code relevant} is the caller's coverage for {@link Observation#VALIDATE_PART} and is present exactly
     * when that observation was asked for. {@link Optional#empty()} means "not observed"; an empty SET means
     * {@code noneRelevant}, which is a distinct and deliberate request. Conflating those two is why this is an
     * {@code Optional<Set<…>>} rather than a set that happens to be empty.
     */
    public record Row(String name, String documentJson, Optional<Set<RuntimeLaws.Relevant>> relevant) {

        /** A row observing no relevant set — the shape every pre-{@code validatePart} request has. */
        public Row(String name, String documentJson) {
            this(name, documentJson, Optional.empty());
        }
    }

    /**
     * A kernel runtime entry point the probe can be asked to observe. Named on the wire exactly as the kernel
     * names the method, so a request says which kernel API produced a block rather than a probe-local synonym.
     */
    public enum Observation {

        /** {@code IDocumentRtService.validateFull} — the message channel, observed for every row. */
        VALIDATE_FULL("validateFull", Scope.ROW),

        /** {@code IDocumentRtService.compute} — the computed-field outcomes and the operand formal errors. */
        COMPUTE("compute", Scope.ROW),

        /**
         * {@code IDocumentRtService.compute} again, read through the RESULT rather than through the change
         * projection: the kernel's own successful-instance collection, the errored instances with their
         * per-instance message, and the document after {@code IDocumentComputationResult.applyTo}.
         *
         * <p><b>It is a separate entry rather than more keys on {@code compute} because the two answer different
         * questions and one of them is strictly bigger.</b> {@code getComputedFieldInstancesWithChanges} is a
         * subset of {@code …WithoutErrors} by the kernel's own javadoc, so a computation that recomputed a value
         * the document already held is a successful result that {@code computations} cannot show at all. And a
         * computation whose destination differs from its source has no outcome-channel observable for what the
         * document then holds — only the applied document has that. Both were asked for by a12-kernel-lean,
         * whose carriers were marked {@code route-state: blocked} on them (their 2026-09-21 note §4).
         */
        COMPUTE_RESULTS("computeResults", Scope.ROW),

        /**
         * {@code IDocumentRtService.validatePart} — the message channel under a CALLER-SUPPLIED relevant set,
         * which is the whole point: two shapes are unreachable through {@code validateFull} because they are
         * properties of the caller's coverage rather than of the document — a call-local PARTIAL coverage, and
         * the empty ({@code noneRelevant}) set. Each row supplies its own {@code relevant}; an empty array is a
         * legitimate request for the second shape, not an omission.
         */
        VALIDATE_PART("validatePart", Scope.ROW),

        /**
         * {@code IRuleCollectionMetaInformation.getMandatoryInformationFromRules} — the three name sets the
         * kernel derives from the model's rule CONDITIONS. Model-level: it reads no document, runs no
         * evaluation, and is reported once for the request rather than per row or per strategy.
         */
        MANDATORY_INFORMATION("mandatoryInformation", Scope.MODEL),

        /**
         * {@code IRuleCollectionMetaInformation.getDynamicEnumerationStringValues} — the field&rarr;allowed-values
         * map the kernel's dynamic-enumeration derivation extracts, and every problem it reports on the way.
         * Model-level, like its sibling.
         *
         * <p><b>It is here because the consistency gate has no route to it.</b> {@code DynamicEnumerationService}
         * is constructed at two sites in the kernel — that facade method, and a facade factory with no in-kernel
         * caller — and neither sits on a consistency rule, so its twelve {@code MVK_DYNENUM_*} classes are
         * unobservable through {@code model check} / {@code rule check} however the model is shaped. <b>Its diagnostics are not defects:</b> the derivation
         * checks every rule and reports one problem per rule that is not a dynamic enumeration IN FORM, which is
         * true of almost every ordinary rule. Read the block as "which rules are dynamic enumerations, and why
         * the rest are not", never as a model-health verdict.
         */
        DYNAMIC_ENUMERATIONS("dynamicEnumerations", Scope.MODEL);

        /** Whether an observation needs a document. The request contract is derived from this, never listed. */
        enum Scope { ROW, MODEL }

        private final String wireName;
        private final Scope scope;

        Observation(String wireName, Scope scope) {
            this.wireName = wireName;
            this.scope = scope;
        }

        /** Whether this observation evaluates a document row. */
        boolean isRowLevel() {
            return scope == Scope.ROW;
        }

        /** The name a request spells. */
        public String wireName() {
            return wireName;
        }

        static Observation of(String wireName) {
            return find(wireName).orElseThrow(() -> new IllegalArgumentException(
                    "'observe' names an unknown observation '" + wireName
                            + "'; the probe observes " + names()));
        }

        /**
         * The observation a wire name denotes, or empty when it denotes none.
         *
         * <p>Empty is a real answer rather than a failure here: a {@link KernelProbeArtifact.Fault} locus is
         * drawn from a wider vocabulary than a request is — {@code documentInstance} and {@code engineBuild}
         * name raises that precede every channel — and {@link KernelProbeArtifact.Engine} distinguishes them
         * from a channel raise to decide which channels a faulted block may still publish.
         */
        static Optional<Observation> find(String wireName) {
            return Arrays.stream(values()).filter(o -> o.wireName.equals(wireName)).findFirst();
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
            Set<Observation> observe, List<Row> rows, Bindings bindings) {

        /** A request binding nothing — the shape every request had before host bindings were declarable. */
        public Request(String modelRef, String modelJson, Locale locale, Optional<Date> clock,
                Set<Observation> observe, List<Row> rows) {
            this(modelRef, modelJson, locale, clock, observe, rows, Bindings.NONE);
        }

        /**
         * The request contract lives HERE, not in the parser, because this record is public: a caller that
         * built one directly would otherwise produce engine blocks whose {@code messages} was empty because
         * nobody looked — indistinguishable from a row where nothing fired, which is the failure mode §5's
         * refusal exists to prevent. Checked before {@code EnumSet.copyOf}, whose own "Collection is empty"
         * would otherwise speak in place of the domain rule.
         *
         * <p>Two halves, both derived from {@link Observation.Scope} rather than from a list of names:
         * a request that observes any ROW-level channel must observe {@code validateFull} and must carry
         * rows; a request that observes only MODEL-level channels must carry none, because a row nothing
         * looks at is the same silent no-op the first half refuses.
         */
        public Request {
            if (observe.isEmpty()) {
                throw new IllegalArgumentException("'observe' must name at least one of " + Observation.names());
            }
            boolean anyRowLevel = observe.stream().anyMatch(Observation::isRowLevel);
            if (anyRowLevel && !observe.contains(Observation.VALIDATE_FULL)) {
                throw new IllegalArgumentException("'observe' must include '"
                        + Observation.VALIDATE_FULL.wireName() + "' whenever it names a per-row observation: "
                        + "the artifact carries no key saying a channel went unobserved, so an empty message "
                        + "list nobody looked at would be indistinguishable from one where nothing fired. "
                        + "Received " + observe);
            }
            if (anyRowLevel == rows.isEmpty()) {
                throw new IllegalArgumentException(anyRowLevel
                        ? "'rows' must be a non-empty array: " + observe + " names a per-row observation"
                        : "'rows' must be absent or empty: " + observe + " names only model-level "
                                + "observations, which read no document, so a row would be observed by nothing");
            }
            // A relevant set and the observation that reads it must agree, in BOTH directions: without the
            // observation the key is one nothing looks at (the same silent no-op as an unobserved row), and
            // without the key validatePart has no coverage to run under and would silently become validateFull.
            boolean partial = observe.contains(Observation.VALIDATE_PART);
            for (Row row : rows) {
                if (partial != row.relevant().isPresent()) {
                    throw new IllegalArgumentException(partial
                            ? "row '" + row.name() + "' must carry 'relevant' because " + observe
                                    + " names validatePart; write [] for the noneRelevant coverage, which is a "
                                    + "deliberate request rather than an omission"
                            : "row '" + row.name() + "' carries 'relevant' but " + observe
                                    + " does not name validatePart, so nothing would read it");
                }
            }
            observe = Collections.unmodifiableSet(EnumSet.copyOf(observe));
            rows = List.copyOf(rows);
        }
    }

    /**
     * What a request registers with the kernel for every call: custom field types and custom conditions, both
     * declarative so the artifact is reproducible from the request bytes alone.
     */
    public record Bindings(Map<String, DeclarativeCustomFieldTypes.Declared> customFieldTypes,
            Map<String, DeclarativeCustomConditions.Declared> customConditions) {

        static final Bindings NONE = new Bindings(Map.of(), Map.of());

        public Bindings {
            customFieldTypes = Map.copyOf(customFieldTypes);
            // Insertion order kept: it is the request's order, which a reader matches calls against.
            customConditions = Collections.unmodifiableMap(new LinkedHashMap<>(customConditions));
        }

        /** The kernel binding for {@link #customFieldTypes}, or {@code null} when the request declares none. */
        ICustomFieldTypeFactory fieldTypeFactory() {
            return DeclarativeCustomFieldTypes.factoryOf(customFieldTypes);
        }

        /** A fresh recorder for one (row, strategy) block. */
        DeclarativeCustomConditions.Recorder recorder() {
            return new DeclarativeCustomConditions.Recorder(customConditions);
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
        JsonNode root = DOCUMENT_MAPPER.readTree(Files.readString(requestFile, StandardCharsets.UTF_8));
        Path base = requestFile.toAbsolutePath().getParent();

        String modelRef = required(root, "model").asText();
        Locale locale = root.hasNonNull("locale") ? locale(root.get("locale").asText()) : DEFAULT_LOCALE;
        Optional<Date> clock = root.hasNonNull("clock") ? Optional.of(clock(root.get("clock").asText()))
                : Optional.empty();
        // Read BEFORE any file is opened: a syntactic mistake in the request should be reported as itself,
        // not masked by whichever document path happens to be missing too.
        Set<Observation> observe = observe(root);

        // Whether rows are required is the Request's rule, derived from what is observed; here only the
        // wire-level shape is decided, so a model-level-only request may simply omit the key.
        JsonNode rowNodes = root.hasNonNull("rows") ? root.get("rows") : null;
        if (rowNodes != null && !rowNodes.isArray()) {
            throw new IllegalArgumentException("'rows' must be an array of named rows");
        }
        Set<String> names = new LinkedHashSet<>();
        List<Row> rows = new ArrayList<>();
        for (JsonNode rowNode : rowNodes == null ? List.<JsonNode>of() : rowNodes) {
            String name = required(rowNode, "name").asText();
            if (!names.add(name)) {
                throw new IllegalArgumentException("duplicate row name '" + name
                        + "'; a row name identifies an observation and must be unique");
            }
            rows.add(new Row(name, document(rowNode, base, name), relevant(rowNode, name)));
        }
        return new Request(modelRef, read(base, modelRef), locale, clock, observe, rows,
                new Bindings(customFieldTypes(root), root.hasNonNull("customConditions")
                        ? DeclarativeCustomConditions.parse(root.get("customConditions")) : Map.of()));
    }

    /**
     * The request's declarative custom-field-type registry, in dmtool's own {@code --predefined-types} shape.
     * Without it a filled CUSTOM cell is unobservable rather than merely unvalidated — see
     * {@link DeclarativeCustomFieldTypes} for why both of A12's resolution routes are empty by default.
     */
    private static Map<String, DeclarativeCustomFieldTypes.Declared> customFieldTypes(JsonNode root) {
        if (!root.hasNonNull("customFieldTypes")) {
            return Map.of();
        }
        JsonNode node = root.get("customFieldTypes");
        if (!node.isObject() || node.isEmpty()) {
            throw new IllegalArgumentException("'customFieldTypes' must be a non-empty object "
                    + "{\"<TypeName>\": {minLength?, maxLength?, pattern?}}, or be omitted; an empty one "
                    + "declares nothing and would leave a filled CUSTOM cell unobservable exactly as its "
                    + "absence does");
        }
        Map<String, DeclarativeCustomFieldTypes.Declared> declared = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> declared.put(entry.getKey(), declaredType(entry.getKey(),
                entry.getValue())));
        return declared;
    }

    private static DeclarativeCustomFieldTypes.Declared declaredType(String name, JsonNode spec) {
        if (!spec.isObject()) {
            throw new IllegalArgumentException("custom field type \"" + name
                    + "\" must be an object with minLength / maxLength / pattern");
        }
        spec.fieldNames().forEachRemaining(key -> {
            if (!Set.of("minLength", "maxLength", "pattern").contains(key)) {
                throw new IllegalArgumentException("custom field type \"" + name + "\": unknown key \"" + key
                        + "\" (allowed: minLength, maxLength, pattern)");
            }
        });
        return DeclarativeCustomFieldTypes.Declared.of(name,
                spec.hasNonNull("minLength") ? spec.get("minLength").asInt() : null,
                spec.hasNonNull("maxLength") ? spec.get("maxLength").asInt() : null,
                spec.hasNonNull("pattern") ? spec.get("pattern").asText() : null);
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

    /**
     * A row's caller-supplied relevant set for {@code validatePart}, or {@link Optional#empty()} when the key
     * is absent. The empty ARRAY is kept distinct from the absent key: it is the {@code noneRelevant} coverage,
     * one of the two shapes the observation exists to reach.
     *
     * <p>Each entry is a {@code path} plus its 1-based repetition indices — <b>one per path PART, not per
     * repeatable part</b>: {@code DocumentPointerImpl.parsePathParts} refuses a mismatch outright with
     * {@code "fullName and repetition have different length"}. {@code 0} is the kernel's own wildcard
     * ({@code IEntityInstance.ALL_SEMANTIC}) meaning "every repetition of that element".
     *
     * <p>An omitted {@code reps} therefore defaults to <b>all wildcards of the right length</b> rather than to
     * an empty list, which would be the length error above for every path but a one-part one. Hand-counting
     * path parts to write {@code [0, 0]} is a footgun with no upside: the coverage a consumer wants is almost
     * always "this element, every repetition", and the length is derivable from the path.
     */
    private static Optional<Set<RuntimeLaws.Relevant>> relevant(JsonNode rowNode, String name) {
        if (!rowNode.hasNonNull("relevant")) {
            return Optional.empty();
        }
        JsonNode node = rowNode.get("relevant");
        if (!node.isArray()) {
            throw new IllegalArgumentException("row '" + name + "': 'relevant' must be an array of "
                    + "{path, reps} entries; write [] for the noneRelevant coverage");
        }
        Set<RuntimeLaws.Relevant> relevant = new LinkedHashSet<>();
        for (JsonNode entry : node) {
            String path = required(entry, "path").asText();
            long parts = path.chars().filter(c -> c == '/').count();
            List<Integer> reps = new ArrayList<>();
            if (entry.hasNonNull("reps")) {
                if (!entry.get("reps").isArray()) {
                    throw new IllegalArgumentException("row '" + name + "': 'reps' must be an array of "
                            + "1-based repetition indices, or 0 for every repetition of that element");
                }
                entry.get("reps").forEach(rep -> reps.add(rep.asInt()));
                if (reps.size() != parts) {
                    throw new IllegalArgumentException("row '" + name + "': 'reps' for '" + path + "' must "
                            + "carry one entry per path PART (" + parts + "), not per repeatable part; got "
                            + reps.size() + ". Use 0 for every repetition of an element, or omit 'reps' "
                            + "entirely for the all-wildcard coverage.");
                }
            } else {
                for (long i = 0; i < parts; i++) {
                    reps.add(0);
                }
            }
            relevant.add(new RuntimeLaws.Relevant(path, List.copyOf(reps)));
        }
        return Optional.of(Collections.unmodifiableSet(relevant));
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
        // Written back WITHOUT plain notation, so each decimal node keeps BigDecimal.toString(), the spelling A12's
        // own parse yields at the same signed scale. Plain notation turned `1e3` into `1000`, which a String field
        // stores differently (KernelProbeExponentFidelityTest); the trailing-zero half of the fidelity fix is the
        // configured parse in DOCUMENT_MAPPER (KernelProbeDocumentFidelityTest).
        if (hasPath) {
            return read(base, rowNode.get("document").asText());
        }
        return DOCUMENT_MAPPER.writer().without(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN)
                .writeValueAsString(rowNode.get("documentInline"));
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
     * Evaluate every row on both kernel strategies, and derive whatever model-level observation was asked for.
     *
     * @throws Refused if the kernel's consistency oracle reports an {@code ERROR} on the request's model
     */
    public static KernelProbeArtifact run(Request request, KernelProbeArtifact.Header header) {
        refuseInconsistentModel(request);

        Optional<Map<KernelAdapter.MandatorySet, List<String>>> mandatory =
                request.observe().contains(Observation.MANDATORY_INFORMATION)
                        ? Optional.of(mandatoryInformation(request.modelJson()))
                        : Optional.empty();
        Optional<KernelAdapter.DynamicEnumerations> dynamicEnumerations =
                request.observe().contains(Observation.DYNAMIC_ENUMERATIONS)
                        ? Optional.of(dynamicEnumerations(request.modelJson()))
                        : Optional.empty();
        if (request.rows().isEmpty()) {
            // No engine is built at all. Generating and compiling the kernel's Java validator is the expensive
            // half of a run, and a model-level query reads the rule collection rather than generated code.
            return new KernelProbeArtifact(header, List.of(), mandatory, dynamicEnumerations);
        }
        KernelRaiseCapture.requireInstalled();

        // Built once for the whole request: generating and compiling the kernel's Java validator is the
        // expensive half, and every row shares one model. A build that RAISES is captured rather than fatal,
        // and its fault then rides every row of that strategy — that is the blast radius of one failed build.
        Built<RuntimeLaws.KernelEngine> groovyDynamic = build(() -> RuntimeLaws.kernelEngine(request.modelJson()));
        Built<RuntimeLaws.JavaKernelEngine> javaStatic =
                build(() -> RuntimeLaws.kernelJavaEngine(request.modelJson()));
        Date clock = request.clock().orElse(null);
        Locale locale = request.locale();

        boolean compute = request.observe().contains(Observation.COMPUTE);
        boolean computeResults = request.observe().contains(Observation.COMPUTE_RESULTS);
        // Bound identically on both strategies, because a type resolvable on one and not the other would
        // report a split that is the harness's rather than the kernel's.
        ICustomFieldTypeFactory fieldTypes = request.bindings().fieldTypeFactory();

        List<KernelProbeArtifact.Row> rows = new ArrayList<>();
        for (Row row : request.rows()) {
            // One immutable DocumentV2 per strategy, observed by every requested entry point: DocumentV2 is
            // immutable and neither validateFull nor compute writes to it, so a second deserialization would
            // only be a second chance to differ.
            rows.add(new KernelProbeArtifact.Row(row.name(), List.of(
                    // Each channel is SET as it completes rather than handed over together, so a raise in a
                    // later one leaves the earlier ones on the block instead of discarding them with it.
                    observe(GROOVY_DYNAMIC, groovyDynamic, request.bindings().recorder(), (engine, calls, block) -> {
                        var instance = instance(() -> engine.instance(row.documentJson()));
                        var conditions = calls.conditions();
                        block.messages(channel(Observation.VALIDATE_FULL, calls, () -> project(
                                engine.validationMessages(instance, clock, locale, conditions, fieldTypes))));
                        if (compute) {
                            block.computations(Optional.of(channel(Observation.COMPUTE, calls, () -> project(
                                    engine.computation(instance, clock, locale, conditions, fieldTypes)))));
                        }
                        row.relevant().ifPresent(rel -> block.partialMessages(Optional.of(
                                channel(Observation.VALIDATE_PART, calls, () -> project(engine
                                        .partialValidationMessages(
                                                instance, rel, clock, locale, conditions, fieldTypes))))));
                        if (computeResults) {
                            block.computeResults(Optional.of(channel(Observation.COMPUTE_RESULTS, calls,
                                    () -> project(engine.computeResults(
                                            instance, clock, locale, conditions, fieldTypes)))));
                        }
                    }),
                    observe(JAVA_STATIC, javaStatic, request.bindings().recorder(), (engine, calls, block) -> {
                        var instance = instance(() -> engine.instance(row.documentJson()));
                        var conditions = calls.conditions();
                        block.messages(channel(Observation.VALIDATE_FULL, calls, () -> project(
                                engine.validationMessages(instance, clock, locale, conditions, fieldTypes))));
                        if (compute) {
                            block.computations(Optional.of(channel(Observation.COMPUTE, calls, () -> project(
                                    engine.computation(instance, clock, locale, conditions, fieldTypes)))));
                        }
                        row.relevant().ifPresent(rel -> block.partialMessages(Optional.of(
                                channel(Observation.VALIDATE_PART, calls, () -> project(engine
                                        .partialValidationMessages(
                                                instance, rel, clock, locale, conditions, fieldTypes))))));
                        if (computeResults) {
                            block.computeResults(Optional.of(channel(Observation.COMPUTE_RESULTS, calls,
                                    () -> project(engine.computeResults(
                                            instance, clock, locale, conditions, fieldTypes)))));
                        }
                    }))));
        }
        return new KernelProbeArtifact(header, rows, mandatory, dynamicEnumerations);
    }

    // ---- fault capture ---------------------------------------------------------------------------

    /**
     * One strategy's engine, or the raise that stopped it being built. Not an {@code Optional} plus a nullable
     * fault: the two states are exclusive and a reader of this type cannot mistake which one it holds.
     */
    private sealed interface Built<E> {

        record Engine<E>(E engine) implements Built<E> {
        }

        record Failed<E>(KernelProbeArtifact.Fault fault) implements Built<E> {
        }
    }

    private static <E> Built<E> build(Supplier<E> builder) {
        try {
            return new Built.Engine<>(builder.get());
        } catch (RuntimeException e) {
            return new Built.Failed<>(fault(ENGINE_BUILD, e));
        }
    }

    /**
     * One (row, strategy) observation, with the first raise inside it captured beside whatever it did not reach.
     *
     * <p><b>The raise ends the block's observation, and keeps what already completed.</b> A channel is read in
     * request order, so a raise leaves the ones before it finished and the ones after it unread;
     * {@code fault.observation} names the one it happened in, which is therefore the only one that could be
     * half-read and is never published. Discarding the finished ones too — the rule until 2026-09-22 — broke the
     * guarantee {@code observe} exists for, because a consumer who ADDED an observation could lose one they
     * already had ({@link KernelProbeOverCapacityFaultTest}).
     */
    private static <E> KernelProbeArtifact.Engine observe(String name, Built<E> built,
            DeclarativeCustomConditions.Recorder calls, BlockObservation<E> observation) {
        var block = KernelProbeArtifact.Engine.of(name);
        switch (built) {
            case Built.Failed<E> failed -> block.fault(failed.fault());
            case Built.Engine<E> ready -> {
                try {
                    observation.accept(ready.engine(), calls, block);
                } catch (Raised raised) {
                    block.fault(raised.fault());
                }
            }
        }
        // Set on every path, a raise included: the calls it interrupted each completed.
        return block.customConditionCalls(calls.calls()).evaluationRaises(calls.raises()).build();
    }

    /** One (row, strategy) observation over a built engine, recording condition calls into {@code calls}. */
    @FunctionalInterface
    private interface BlockObservation<E> {

        void accept(E engine, DeclarativeCustomConditions.Recorder calls, KernelProbeArtifact.Engine.Builder block);
    }

    /**
     * Runs one observed channel, converting a kernel raise into the fault that replaces the whole block. The rule
     * raises A12 caught on the way are kept only when the channel completes, since a faulted channel publishes
     * nothing.
     */
    private static <T> T channel(Observation observation, DeclarativeCustomConditions.Recorder calls,
            Supplier<T> read) {
        calls.enter(observation);
        KernelRaiseCapture.open();
        try {
            T value = read.get();
            calls.caught(KernelRaiseCapture.close());
            return value;
        } catch (RuntimeException e) {
            throw new Raised(fault(observation.wireName(), e));
        } finally {
            KernelRaiseCapture.close();
        }
    }

    /** As {@link #channel}, for the document deserialization that precedes every channel. */
    private static <T> T instance(Supplier<T> read) {
        try {
            return read.get();
        } catch (RuntimeException e) {
            throw new Raised(fault(DOCUMENT_INSTANCE, e));
        }
    }

    /** The locus of a raise that happened before any requested channel could be read. */
    private static final String DOCUMENT_INSTANCE = "documentInstance";

    /** The locus of a raise while building one strategy's engine — it rides every row of that strategy. */
    private static final String ENGINE_BUILD = "engineBuild";

    /** Carries a captured {@link KernelProbeArtifact.Fault} out of a channel to the block that replaces. */
    private static final class Raised extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final transient KernelProbeArtifact.Fault fault;

        Raised(KernelProbeArtifact.Fault fault) {
            super(fault.observation(), null, false, false);
            this.fault = fault;
        }

        KernelProbeArtifact.Fault fault() {
            return fault;
        }
    }

    private static KernelProbeArtifact.Fault fault(String observation, Throwable raised) {
        return new KernelProbeArtifact.Fault(observation, chain(raised));
    }

    /** A raise and its causes, outermost first, as type and message. */
    static List<KernelProbeArtifact.Fault.Raise> chain(Throwable raised) {
        List<KernelProbeArtifact.Fault.Raise> chain = new ArrayList<>();
        // Bounded and cycle-safe: a self-referencing cause chain would otherwise hang a run that is already
        // failing, and the depth is generous enough that no real kernel chain reaches it.
        for (Throwable link = raised; link != null && chain.size() < MAX_CAUSE_DEPTH; link = link.getCause()) {
            chain.add(new KernelProbeArtifact.Fault.Raise(
                    link.getClass().getName(), Optional.ofNullable(link.getMessage())));
            if (link.getCause() == link) {
                break;
            }
        }
        return chain;
    }

    static final int MAX_CAUSE_DEPTH = 16;

    /**
     * Every fault the run captured, as {@code <row> / <strategy> at <locus>: <type>: <message>} lines, in row
     * then strategy order — the summary the failure carries once the artifact is safely written.
     */
    static List<String> faults(KernelProbeArtifact artifact) {
        List<String> lines = new ArrayList<>();
        for (KernelProbeArtifact.Row row : artifact.rows()) {
            for (KernelProbeArtifact.Engine engine : row.engines()) {
                engine.fault().ifPresent(fault -> lines.add(row.name() + " / " + engine.engine() + " at "
                        + fault.observation() + ": " + fault.chain().stream()
                                .map(raise -> raise.type() + raise.message().map(m -> ": " + m).orElse(""))
                                .collect(Collectors.joining(" ← "))));
            }
        }
        return List.copyOf(lines);
    }

    /**
     * The run observed at least one kernel raise. Thrown <b>after</b> the artifact is written, so the run fails
     * — a fault is not a success — while the observation it made is retained and pinnable.
     */
    public static final class FaultsObserved extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final transient List<String> faults;

        FaultsObserved(Path out, List<String> faults) {
            super("the kernel raised on " + faults.size() + " (row, strategy) observation(s); the artifact at "
                    + out.toAbsolutePath() + " records each one:\n  " + String.join("\n  ", faults));
            this.faults = List.copyOf(faults);
        }

        /** The captured faults, one line each, in the artifact's own row then strategy order. */
        public List<String> faults() {
            return faults;
        }
    }

    /**
     * The kernel's mandatory-information derivation over the request's model, as a sorted name list per set.
     * Delegates to {@code KernelAdapter.mandatoryInformation}, which is where the kernel types stay: this is a
     * MODEL-and-rule query with no document, so neither {@code validateFull} nor {@code compute} can expose it
     * and it is reported once for the request rather than per row or per strategy.
     */
    private static Map<KernelAdapter.MandatorySet, List<String>> mandatoryInformation(String modelJson) {
        var derived = KernelAdapter.ofJson(modelJson).mandatoryInformation();
        var out = new EnumMap<KernelAdapter.MandatorySet, List<String>>(KernelAdapter.MandatorySet.class);
        derived.forEach((set, names) -> out.put(set, List.copyOf(names)));
        return out;
    }

    /**
     * The kernel's dynamic-enumeration derivation over the request's model — the field&rarr;values map and every
     * problem it reported. Delegates to {@code KernelAdapter.dynamicEnumerations}, so the kernel types stay
     * there; model-level for the same reason as its sibling, and the only route to the derivation's classes at
     * all (see {@link Observation#DYNAMIC_ENUMERATIONS}).
     */
    private static KernelAdapter.DynamicEnumerations dynamicEnumerations(String modelJson) {
        return KernelAdapter.ofJson(modelJson).dynamicEnumerations();
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

    /**
     * One strategy's {@code compute()} run read through the RESULT. The applied document is re-parsed rather
     * than embedded as a string: it is A12's canonical Document JSON, and a consumer of this artifact already
     * has a JSON parser in hand, so a quoted blob would make them run a second one.
     */
    private static KernelProbeArtifact.ComputeResults project(RuntimeLaws.ComputeResultObservation observation) {
        return new KernelProbeArtifact.ComputeResults(
                observation.withoutErrors().stream().map(KernelRuntimeProbe::project).toList(),
                observation.errored().stream().map(KernelRuntimeProbe::project).toList(),
                readTree(observation.applied()));
    }

    private static KernelProbeArtifact.ResultInstance project(RuntimeLaws.ResultInstance instance) {
        return new KernelProbeArtifact.ResultInstance(
                address(instance.pointer()), instance.value(),
                instance.errorMessage().map(KernelRuntimeProbe::project));
    }

    /**
     * Re-parse A12's canonical Document JSON WITHOUT destroying a number's scale. A default mapper reads
     * {@code 5.00} into a {@code double} and re-renders it {@code 5.0}, so an applied document would arrive in
     * this artifact spelled differently from the one A12 wrote — the parse-stage half of the project's own
     * number-fidelity rule (CLAUDE.md, "adopt A12's JSON I/O BEHAVIOUR exactly";
     * KERNEL-FINDINGS KF1). {@code USE_BIG_DECIMAL_FOR_FLOATS} keeps the written scale, and
     * {@code DecimalNode} writes it back verbatim.
     */
    private static JsonNode readTree(String json) {
        try {
            return DOCUMENT_MAPPER.readTree(json);
        } catch (IOException cannotHappen) {
            throw new UncheckedIOException("A12's own serializer wrote unparseable JSON", cannotHappen);
        }
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
     * The independent projector is a {@code java-test-fixtures} surface: this test-source probe can reach it,
     * and {@code :cli} tests cross-check it without reversing the production dependency.
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

    static String address(String fullName, List<Integer> repetitionIndexes) {
        return KernelMessageAddressProjector.address(
                A12PartiallyKnownMultiPointer.Companion.of(fullName, repetitionIndexes));
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

        // Written first, failed second. A raise is an observation the artifact now carries, so it must survive
        // the run that fails on it — the order is the whole fix (a12-kernel-lean's instrument note, 2026-09-04).
        List<String> faults = faults(artifact);
        if (!faults.isEmpty()) {
            throw new FaultsObserved(out, faults);
        }
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
        return KernelProbeArtifact.Header.builder()
                .dmtoolVersion(property("a12.probe.dmtoolVersion"))
                .kernelBuiltAgainst(property("a12.probe.kernelBuiltAgainst"))
                .kernelRuntime(Optional.ofNullable(KernelVersion.runtimeVersion()).orElse("(unknown)"))
                .sourceRevision(property("a12.probe.sourceRevision"))
                // The state's own "we could not tell" spelling, not the generic one: it is a closed domain,
                // shared with dmtool's version surface, and `(unknown)` is not a member of it.
                .sourceState(System.getProperty(SOURCE_STATE_PROPERTY, UNDETERMINED_STATE))
                .model(request.modelRef())
                .locale(request.locale().getLanguage() + "_" + request.locale().getCountry())
                .clock(request.clock().map(clock -> clock.toInstant().toString()))
                .build();
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

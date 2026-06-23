package io.github.mbackschat.a12.dm.adapter.laws;

import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.mgmtp.a12.kernel.md.document.api.IDocument;
import com.mgmtp.a12.kernel.md.document.api.services.IDocumentFactory;
import com.mgmtp.a12.kernel.md.document.apiV2.PartiallyKnownDocumentMultiPointer;
import io.github.mbackschat.a12.dm.interpreter.eval.PointerKt;
import com.mgmtp.a12.kernel.md.facade.DocumentModelServiceFactory;
import com.mgmtp.a12.kernel.md.facade.DocumentRtServiceFactory;
import com.mgmtp.a12.kernel.md.facade.DocumentServiceFactory;
import com.mgmtp.a12.kernel.md.model.api.IDocumentModel;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelReferenceResolver;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelResolver;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelService;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelSerializer;
import com.mgmtp.a12.kernel.md.rt.api.IDocumentComputationResult;
import com.mgmtp.a12.kernel.md.rt.api.IDocumentDynamicServiceConfig;
import com.mgmtp.a12.kernel.md.rt.api.IDocumentModelIdProvider;
import com.mgmtp.a12.kernel.md.rt.api.IDocumentRtService;
import com.mgmtp.a12.kernel.md.rt.api.IDocumentValidationResult;
import com.mgmtp.a12.kernel.md.rt.api.ILabelProvider;
import com.mgmtp.a12.kernel.md.rt.api.IMessage;
import com.mgmtp.a12.kernel.md.rt.api.IModelCode;
import com.mgmtp.a12.kernel.md.rt.api.IModelCodeCache;

/**
 * The document-evaluating oracle for the <em>behavioral</em> kernel laws (F-LAW) — the runtime
 * counterpart of {@link io.github.mbackschat.a12.dm.rulekit.validate.laws} (those lock <em>accept /
 * reject</em>; this locks <em>fires / does-not-fire on data</em>). It runs the kernel's real
 * {@code IDocumentRtService.validateFull} over a constructed document instance and reports which rules
 * fired, so a kernel upgrade that changes a documented runtime behavior (polarity, empty-as-0, …)
 * fails loudly. Grounded in {@code ../a12-kernel-demo/KERNEL-SEMANTICS.md}.
 *
 * <p><b>Document construction (the V1 path — the one shared with the TypeScript runtime).</b> Each
 * entity instance needs <em>one 1-based repetition index per path part</em> (group <em>and</em> field):
 * {@code /Order} → {@code [1]}, {@code /Order/Quantity} → {@code [1,1]}. Field values are read back as
 * strings during the V1→update-action conversion, so values are passed as text — numbers/strings
 * verbatim, <b>boolean as {@code "true"}/{@code "false"}</b> (lowercase; other tokens are a formal
 * error), <b>confirm as {@code "true"} or empty</b> (a confirm has no {@code false} — that's a formal
 * error; unspecified counts as {@code False}). An <em>absent</em> field models an empty value.
 *
 * <p>For single-row root-group laws use the {@code Set}/{@code Map} convenience overloads (all indices
 * default to 1); for <b>multi-row</b> iteration (§9) use the explicit {@link Doc} builder (one index
 * per path part). {@link #messages(String, Doc)} also returns each message's row pointer, so a law can
 * assert <em>which</em> row a per-row rule fired on.
 *
 * <p><b>Shared oracle infrastructure.</b> This is the kernel oracle for BOTH the behavioral laws
 * ({@code …adapter.laws}) and the performance harness ({@code …adapter.perf}, INTERPRETER-PERFORMANCE.md),
 * so the surface those consume — {@link #model}, {@link #withRule}, {@link #doc}, {@link Doc},
 * {@link #kernelEngine}, {@link KernelEngine} — is {@code public} across the test source set; the rest stays
 * package-private to {@code laws}.
 */
public final class RuntimeLaws {

    private static final Pattern HEADER_ID = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");

    private RuntimeLaws() {
    }

    /** Load a model JSON fixture from the adapter test resources (e.g. {@code "models/order.dm.json"}). */
    public static String model(String resourcePath) {
        try (InputStream in = RuntimeLaws.class.getClassLoader().getResourceAsStream(resourcePath)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("failed to read " + resourcePath, e);
        }
    }

    /**
     * Inject one rule into {@code groupId}'s {@code elements} and return the new model JSON. The
     * condition is the rule's <em>error</em> condition (it fires when this holds — the polarity law).
     */
    public static String withRule(String modelJson, String groupId, String ruleName, String errorEntityRelPath,
            String errorCode, String condition) {
        return withRuleMessages(modelJson, groupId, ruleName, errorEntityRelPath, errorCode, condition, "x", "x");
    }

    /** As {@link #withRule}, but with explicit en_US / de_DE error-message texts (for localized-text laws). */
    static String withRuleMessages(String modelJson, String groupId, String ruleName, String errorEntityRelPath,
            String errorCode, String condition, String enText, String deText) {
        String escaped = condition.replace("\\", "\\\\").replace("\"", "\\\"");
        String en = enText.replace("\\", "\\\\").replace("\"", "\\\"");
        String de = deText.replace("\\", "\\\\").replace("\"", "\\\"");
        String rule = "{ \"type\": \"Rule\", \"id\": \"" + groupId + "/" + ruleName + "\", \"name\": \"" + ruleName + "\","
                + " \"Rule\": { \"errorEntityRelPath\": \"" + errorEntityRelPath + "\", \"errorCode\": \"" + errorCode + "\","
                + " \"errorCondition\": \"" + escaped + "\", \"severity\": \"ERROR\","
                + " \"errorMessage\": [ { \"locale\": \"en_US\", \"text\": \"" + en + "\" },"
                + " { \"locale\": \"de_DE\", \"text\": \"" + de + "\" } ] } },\n";
        // insert after the group's own elements bracket (the group id locates it; first elements after it)
        int g = modelJson.indexOf("\"id\": \"" + groupId + "\"");
        if (g < 0) {
            g = modelJson.indexOf("\"id\" : \"" + groupId + "\"");
        }
        int br = modelJson.indexOf("\"elements\"", g);
        br = modelJson.indexOf('[', br);
        return modelJson.substring(0, br + 1) + "\n" + rule + modelJson.substring(br + 1);
    }

    /**
     * Inject one computation into {@code groupId}'s {@code elements} and return the new model JSON. The
     * computation assigns {@code operation} (a value expression) to the field at {@code computedFieldRelPath}
     * (relative to the group, e.g. {@code "../EffectiveFee"}). Mirrors {@link #withRule} for the compute path.
     */
    static String withComputation(String modelJson, String groupId, String name, String computedFieldRelPath,
            String operation) {
        String op = operation.replace("\\", "\\\\").replace("\"", "\\\"");
        String comp = "{ \"type\": \"Computation\", \"id\": \"" + groupId + "/" + name + "\", \"name\": \"" + name + "\","
                + " \"Computation\": { \"computedFieldRelPath\": \"" + computedFieldRelPath + "\","
                + " \"computationAlternatives\": [ { \"operation\": \"" + op + "\" } ],"
                + " \"errorMessage\": [ { \"locale\": \"en_US\", \"text\": \"x\" }, { \"locale\": \"de_DE\", \"text\": \"x\" } ] } },\n";
        int g = modelJson.indexOf("\"id\": \"" + groupId + "\"");
        if (g < 0) {
            g = modelJson.indexOf("\"id\" : \"" + groupId + "\"");
        }
        int br = modelJson.indexOf("\"elements\"", g);
        br = modelJson.indexOf('[', br);
        return modelJson.substring(0, br + 1) + "\n" + comp + modelJson.substring(br + 1);
    }

    /**
     * Evaluate the model (rules included) against a document instance and return the error codes that
     * fired. {@code groupPaths} are the group rows to instantiate (e.g. {@code "/Order"}); each provided
     * field's ancestor groups are added automatically. A field path absent from {@code fieldValues} is
     * an <em>empty</em> field.
     */
    static Set<String> firedCodes(String modelJson, Set<String> groupPaths, Map<String, String> fieldValues) {
        return firedCodes(modelJson, groupPaths, Set.of(), fieldValues);
    }

    /**
     * As {@link #firedCodes(String, Set, Map)}, but {@code emptyFields} are added as <em>present but
     * valueless</em> instances — how a real form renders an unfilled field (distinct from a field that
     * is wholly absent from the document). Single-row convenience over {@link #firedCodes(String, Doc)}.
     */
    static Set<String> firedCodes(String modelJson, Set<String> groupPaths, Set<String> emptyFields,
            Map<String, String> fieldValues) {
        Doc doc = doc();
        Set<String> groups = new LinkedHashSet<>(groupPaths);
        fieldValues.keySet().forEach(f -> groups.addAll(ancestorGroups(f)));
        emptyFields.forEach(f -> groups.addAll(ancestorGroups(f)));
        groups.forEach(g -> doc.group(g, ones(depth(g))));
        emptyFields.forEach(f -> doc.emptyField(f, ones(depth(f))));
        fieldValues.forEach((f, v) -> doc.field(f, v, ones(depth(f))));
        return firedCodes(modelJson, doc);
    }

    /** Whether the rule with {@code errorCode} fires for the given instance. */
    static boolean fires(String modelJson, String errorCode, Set<String> groupPaths, Map<String, String> fieldValues) {
        return firedCodes(modelJson, groupPaths, fieldValues).contains(errorCode);
    }

    /** As {@link #fires(String, String, Set, Map)}, with {@code emptyFields} present-but-valueless. */
    static boolean fires(String modelJson, String errorCode, Set<String> groupPaths, Set<String> emptyFields,
            Map<String, String> fieldValues) {
        return firedCodes(modelJson, groupPaths, emptyFields, fieldValues).contains(errorCode);
    }

    // ---- explicit multi-row document instances (§9 iteration) -----------------------------------

    /**
     * A document instance built from explicit group/field placements, each with <em>one 1-based
     * repetition index per path part</em> — so multiple repeatable rows are addressable (e.g.
     * {@code /Order/Items} row 2 is {@code group("/Order/Items", 1, 2)} and its Count is
     * {@code field("/Order/Items/Count", "80", 1, 2, 1)}).
     */
    public static final class Doc {
        private final List<Inst> insts = new ArrayList<>();

        /** A group row at the given 1-based repetition indices (one per path part). */
        public Doc group(String path, int... reps) {
            insts.add(new Inst(path, reps, true, false, null));
            return this;
        }

        /** A field with a value at the given indices (one per path part, the field included). */
        public Doc field(String path, String value, int... reps) {
            insts.add(new Inst(path, reps, false, true, value));
            return this;
        }

        /** A present-but-valueless (unfilled) field. */
        public Doc emptyField(String path, int... reps) {
            insts.add(new Inst(path, reps, false, false, null));
            return this;
        }
    }

    public static Doc doc() {
        return new Doc();
    }

    private record Inst(String path, int[] reps, boolean group, boolean hasValue, String value) {
    }

    /**
     * One validation message: its error code, the row/field pointer it attached to (for per-row
     * checks), and its {@link IMessage.MessageType} — the kernel's value-vs-omission typing
     * (OMISSION_ERROR = the rule could be satisfied by <em>filling</em> more fields; VALUE_ERROR
     * otherwise). The typing channel is what the error-type laws lock.
     *
     * <p>{@code errorField} is the kernel's raw pointer {@code toString} (the {@code [1, 2, 1]} form the
     * runtime-law tests assert row attribution on). The rich-message channels — {@code field},
     * {@code referenced}, {@code omissionResponsible} — are the same pointers rendered the clean
     * {@link io.github.mbackschat.a12.dm.interpreter.PointerKt#formatPointer way} the interpreter uses, so
     * a differential can compare the interpreter's {@code Message} to the kernel's {@code IMessage}.
     */
    record Msg(String code, String errorField, IMessage.MessageType type,
            String field, List<String> referenced, List<String> omissionResponsible) {
    }

    /** A kernel field pointer rendered the interpreter's clean way (path + indices), for the differential. */
    private static String fmtPointer(PartiallyKnownDocumentMultiPointer p) {
        return PointerKt.formatPointer(p.fullName(), p.repetitionIndexes());
    }

    /** The {@link IMessage.MessageType} of the fired {@code errorCode} (empty when it did not fire). */
    static Optional<IMessage.MessageType> typeOf(String modelJson, String errorCode, Doc doc) {
        return messages(modelJson, doc).stream().filter(m -> m.code().equals(errorCode))
                .map(Msg::type).findFirst();
    }

    /** Single-row convenience for {@link #typeOf(String, String, Doc)} (same building as firedCodes). */
    static Optional<IMessage.MessageType> typeOf(String modelJson, String errorCode, Set<String> groupPaths,
            Set<String> emptyFields, Map<String, String> fieldValues) {
        Doc doc = doc();
        Set<String> groups = new LinkedHashSet<>(groupPaths);
        fieldValues.keySet().forEach(f -> groups.addAll(ancestorGroups(f)));
        emptyFields.forEach(f -> groups.addAll(ancestorGroups(f)));
        groups.forEach(g -> doc.group(g, ones(depth(g))));
        emptyFields.forEach(f -> doc.emptyField(f, ones(depth(f))));
        fieldValues.forEach((f, v) -> doc.field(f, v, ones(depth(f))));
        return typeOf(modelJson, errorCode, doc);
    }

    /** Build the document instance described by {@code doc} against {@code modelJson}'s model. */
    private static IDocument buildInstance(String modelJson, IDocumentFactory df, Doc doc) {
        IDocument d = df.createDocument(modelId(modelJson));
        for (Inst i : doc.insts) {
            if (i.group()) {
                d.addEntityInstance(df.createGroupInstance(i.path(), i.reps()));
            } else if (i.hasValue()) {
                d.addEntityInstance(df.createFieldInstance(i.path(), i.reps(), i.value()));
            } else {
                d.addEntityInstance(df.createFieldInstance(i.path(), i.reps()));   // present, no value = unfilled
            }
        }
        return d;
    }

    /** Run the kernel's {@code validateFull} over the instance, in the given locale. */
    private static IDocumentValidationResult validate(String modelJson, Doc doc, Locale locale) {
        IDocumentModel model = expandedModel(modelJson);
        IDocumentModelResolver resolver = id -> model;
        IDocumentRtService rt = new DocumentRtServiceFactory(resolver).createDocumentRtService(dynamicConfig());
        IDocumentFactory df = new DocumentServiceFactory(resolver).createDocumentFactory();
        return rt.validateFull(buildInstance(modelJson, df, doc), locale);
    }

    /** Evaluate an explicit document instance; return every message (code + pointer) the kernel raised. */
    static List<Msg> messages(String modelJson, Doc doc) {
        return validate(modelJson, doc, Locale.US).getMessages().stream()
                .map(m -> new Msg(m.getErrorCode(), String.valueOf(m.getErrorFieldPointer()), m.getMessageType(),
                        fmtPointer(m.getErrorFieldPointer()),
                        m.getReferencedFieldsPointers().stream().map(RuntimeLaws::fmtPointer).toList(),
                        m.getRefOmissionErrorResponsiblePointers().stream().map(RuntimeLaws::fmtPointer).toList()))
                .toList();
    }

    /** The kernel's fully-rendered (localized, params substituted) error text for {@code errorCode}. */
    static Optional<String> errorTextOf(String modelJson, String errorCode, Doc doc, Locale locale) {
        return validate(modelJson, doc, locale).getMessages().stream()
                .filter(m -> m.getErrorCode().equals(errorCode))
                .map(IMessage::getErrorText)
                .findFirst();
    }

    static Set<String> firedCodes(String modelJson, Doc doc) {
        return messages(modelJson, doc).stream().map(Msg::code).collect(Collectors.toCollection(HashSet::new));
    }

    // ---- computations (compute(): does a computed field get a value, get cleared, or error?) -----

    /**
     * One computed-field outcome of a {@code compute()} run: its path and which bucket the kernel put it
     * in — {@code value} present means it computed a value; {@code cleared} means the kernel set it empty
     * (computed nothing); {@code errored} means an operand/computation error.
     */
    record Computed(String path, Optional<String> value, boolean cleared, boolean errored) {
    }

    /**
     * Run the kernel's {@code compute()} over an explicit document instance and return every
     * computed-field outcome (value / cleared / errored). The discriminator for "does an empty operand
     * yield a value or nothing": a field in {@link #cleared(List, String)} computed nothing, a field with
     * a {@code value} computed a value.
     */
    static List<Computed> compute(String modelJson, Doc doc) {
        IDocumentModel model = expandedModel(modelJson);
        IDocumentModelResolver resolver = id -> model;
        IDocumentRtService rt = new DocumentRtServiceFactory(resolver).createDocumentRtService(dynamicConfig());
        IDocumentFactory df = new DocumentServiceFactory(resolver).createDocumentFactory();

        IDocument d = buildInstance(modelJson, df, doc);
        IDocumentComputationResult result = rt.compute(d, Locale.US);
        List<Computed> out = new ArrayList<>();
        result.getComputedFieldInstancesWithChanges().forEach(c ->
                out.add(new Computed(c.getPath(), c.getStringValue(), false, false)));
        result.getComputedFieldInstancesWithErrors().forEach(c ->
                out.add(new Computed(c.getPath(), c.getStringValue(), false, true)));
        result.getClearedFieldInstances().forEach(c ->
                out.add(new Computed(c.getPath(), Optional.empty(), true, false)));
        return out;
    }

    /** Whether {@code path} computed a (non-empty) value under {@code compute()}. */
    static Optional<String> computedValue(List<Computed> outcomes, String path) {
        return outcomes.stream().filter(c -> c.path().equals(path) && c.value().isPresent())
                .map(c -> c.value().get()).findFirst();
    }

    /** Whether {@code path} was cleared (computed nothing) under {@code compute()}. */
    static boolean cleared(List<Computed> outcomes, String path) {
        return outcomes.stream().anyMatch(c -> c.path().equals(path) && c.cleared());
    }

    /** Whether the rule with {@code errorCode} fires for the given explicit instance. */
    static boolean fires(String modelJson, String errorCode, Doc doc) {
        return firedCodes(modelJson, doc).contains(errorCode);
    }

    /**
     * A build-ONCE kernel evaluator for the performance harness (INTERPRETER-PERFORMANCE.md). The model is
     * deserialized + expanded and the rt service + document factory are created a SINGLE time, so a benchmark
     * can build an instance once and time only {@code validateFull} — a fair against-kernel comparison, not
     * polluted by per-call model rebuild (which the convenience {@link #firedCodes(String, Doc)} incurs).
     */
    public static final class KernelEngine {
        private final String modelJson;
        private final IDocumentRtService rt;
        private final IDocumentFactory df;

        KernelEngine(String modelJson) {
            this.modelJson = modelJson;
            IDocumentModel model = expandedModel(modelJson);
            IDocumentModelResolver resolver = id -> model;
            this.rt = new DocumentRtServiceFactory(resolver).createDocumentRtService(dynamicConfig());
            this.df = new DocumentServiceFactory(resolver).createDocumentFactory();
        }

        /** Build the kernel {@link IDocument} for {@code doc} once — do this OUTSIDE the timed region. */
        public IDocument instance(Doc doc) {
            return buildInstance(modelJson, df, doc);
        }

        /**
         * Every fired error code for an already-built instance, <b>sorted</b> — the timed call AND the
         * perf-harness correctness payload. A {@code List} (not a {@code Set}) so the comparison is a
         * <em>multiset</em>: a per-row rule firing on K rows yields K copies, and the interpreter must
         * produce the same K — catching a per-row firing mismatch a code-set would hide.
         */
        public List<String> firedCodesSorted(IDocument instance) {
            return rt.validateFull(instance, Locale.US).getMessages().stream()
                    .map(IMessage::getErrorCode).sorted().toList();
        }
    }

    public static KernelEngine kernelEngine(String modelJson) {
        return new KernelEngine(modelJson);
    }

    /** Deserialize + expand (self-contained) the model JSON. */
    private static IDocumentModel expandedModel(String modelJson) {
        var factory = new DocumentModelServiceFactory();
        IDocumentModelSerializer serializer = factory.createDocumentModelSerializer();
        IDocumentModelService modelService = factory.createDocumentModelService();
        IDocumentModel model;
        try {
            model = serializer.deserialize(new StringReader(modelJson));
        } catch (Exception e) {
            throw new RuntimeException("failed to deserialize model", e);
        }
        modelService.expand(model, (IDocumentModelReferenceResolver) ref -> {
            throw new IllegalStateException("self-contained, unexpected reference: " + ref);
        });
        return model;
    }

    private static IDocumentDynamicServiceConfig dynamicConfig() {
        IModelCodeCache cache = new IModelCodeCache() {
            private final Map<String, IModelCode> codes = new HashMap<>();
            public IModelCode getModelCode(String id) { return codes.get(id); }
            public void addModelCode(String id, IModelCode code) { codes.put(id, code); }
        };
        return new IDocumentDynamicServiceConfig() {
            public IModelCodeCache getCache() { return cache; }
            public Optional<String> getVariant() { return Optional.empty(); }
            // The $field$ display-name resolver — label-for-locale (non-blank) else the field name. Mirrors
            // KernelAdapter's provider, so a differential compares the interpreter's render to the kernel's
            // label-aware render (not the path+index fallback the kernel emits with no provider).
            public Optional<ILabelProvider> getLabelProvider() {
                return Optional.of((field, locale) -> {
                    String label = field.getLabel().get(locale);
                    return Optional.of(label != null && !label.isBlank() ? label : field.getName());
                });
            }
            public Optional<IDocumentModelIdProvider> getModelIdProvider() { return Optional.empty(); }
        };
    }

    private static String modelId(String modelJson) {
        Matcher m = HEADER_ID.matcher(modelJson);
        if (m.find()) {
            return m.group(1);   // the header id is the first "id" in the document
        }
        throw new IllegalArgumentException("no header id in model JSON");
    }

    /** Ancestor group paths of a field path, e.g. {@code /A/B/F} → {@code [/A, /A/B]}. */
    private static Set<String> ancestorGroups(String fieldPath) {
        Set<String> groups = new LinkedHashSet<>();
        int slash = fieldPath.indexOf('/', 1);
        while (slash > 0) {
            groups.add(fieldPath.substring(0, slash));
            slash = fieldPath.indexOf('/', slash + 1);
        }
        return groups;
    }

    private static int depth(String path) {
        return (int) path.chars().filter(c -> c == '/').count();
    }

    private static int[] ones(int n) {
        int[] a = new int[n];
        java.util.Arrays.fill(a, 1);
        return a;
    }
}

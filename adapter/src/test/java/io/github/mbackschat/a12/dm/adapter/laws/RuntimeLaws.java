package io.github.mbackschat.a12.dm.adapter.laws;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import com.fasterxml.jackson.core.util.MinimalPrettyPrinter;
import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldTypeFactory;
import com.mgmtp.a12.kernel.md.document.api.IDocument;
import com.mgmtp.a12.kernel.md.document.api.services.DocumentDeserializationConfig;
import com.mgmtp.a12.kernel.md.document.api.services.DocumentSerializationConfig;
import com.mgmtp.a12.kernel.md.document.api.services.IDocumentFactory;
import com.mgmtp.a12.kernel.md.document.apiV2.DocumentMultiPointer;
import com.mgmtp.a12.kernel.md.document.apiV2.DocumentPointer;
import com.mgmtp.a12.kernel.md.document.apiV2.PartiallyKnownDocumentMultiPointer;
import com.mgmtp.a12.kernel.md.document.apiV2.immutable.DocumentV2;
import com.mgmtp.a12.kernel.md.document.apiV2.immutable.FieldInstanceV2;
import com.mgmtp.a12.kernel.md.document.apiV2.immutable.GroupInstanceV2;
import com.mgmtp.a12.kernel.md.document.apiV2.immutable.RepetitionsV2;
import com.mgmtp.a12.kernel.md.document.apiV2.services.IDocumentV2Serializer;
import io.github.mbackschat.a12.dm.interpreter.eval.PointerKt;
import com.mgmtp.a12.kernel.md.facade.DocumentModelServiceFactory;
import com.mgmtp.a12.kernel.md.facade.DocumentRtServiceFactory;
import com.mgmtp.a12.kernel.md.facade.DocumentServiceFactory;
import com.mgmtp.a12.kernel.md.model.api.IDocumentModel;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelReferenceResolver;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelResolver;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelService;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelSerializer;
import com.mgmtp.a12.kernel.md.model.api.services.IValidationCodeGeneratorConfig;
import com.mgmtp.a12.kernel.md.rt.api.DocumentProcessingConfig;
import com.mgmtp.a12.kernel.md.rt.api.IComputedFieldInstance;
import com.mgmtp.a12.kernel.md.rt.api.ICustomCondition;
import com.mgmtp.a12.kernel.md.rt.api.ICustomConditionFactory;
import com.mgmtp.a12.kernel.md.rt.api.IDocumentComputationResult;
import com.mgmtp.a12.kernel.md.rt.api.IDocumentDynamicServiceConfig;
import com.mgmtp.a12.kernel.md.rt.api.IDocumentModelIdProvider;
import com.mgmtp.a12.kernel.md.rt.api.IDocumentRtService;
import com.mgmtp.a12.kernel.md.rt.api.IDocumentStaticServiceConfig;
import com.mgmtp.a12.kernel.md.rt.api.IDocumentValidationResult;
import com.mgmtp.a12.kernel.md.rt.api.ILabelProvider;
import com.mgmtp.a12.kernel.md.rt.api.IMessage;
import com.mgmtp.a12.kernel.md.rt.api.IModelCode;
import com.mgmtp.a12.kernel.md.rt.api.IModelCodeCache;
import com.mgmtp.a12.kernel.md.serializer.MDSerializerFactory;

/**
 * The document-evaluating oracle for the <em>behavioral</em> kernel laws (F-LAW) — the runtime
 * counterpart of {@link io.github.mbackschat.a12.dm.rulekit.validate.laws} (those lock <em>accept /
 * reject</em>; this locks <em>fires / does-not-fire on data</em>). It runs the kernel's real
 * {@code IDocumentRtService.validateFull} over a constructed document instance and reports which rules
 * fired, so a kernel upgrade that changes a documented runtime behavior (polarity, empty-as-0, …)
 * fails loudly. Grounded in {@code docs/KERNEL-SEMANTICS.md}.
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

    /** JSON-string-escape a spliced value — quotes, backslashes, AND control characters (a stored condition can
     *  legally carry embedded newlines/indentation, as the kernel-mm metamodels do). */
    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
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
        String escaped = jsonEscape(condition);
        String en = jsonEscape(enText);
        String de = jsonEscape(deText);
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
    public static String withComputation(String modelJson, String groupId, String name, String computedFieldRelPath,
            String operation) {
        return withComputation(modelJson, groupId, name, computedFieldRelPath, operation, List.of());
    }

    /**
     * As {@link #withComputation(String, String, String, String, String)} but declaring
     * {@code errorCodesToSuppress} ({@code IComputation.getErrorCodesToSuppress}) — the kernel's own escape
     * hatch for the static assignment-scale gate: {@code MVK_INVALID_COMPARE_DEC_PLACES} in the list lets a
     * computation whose operation's static scale differs from the target's load (the only way the runtime
     * no-fit store arm is reachable on a legal model — IF92).
     */
    public static String withComputation(String modelJson, String groupId, String name, String computedFieldRelPath,
            String operation, List<String> errorCodesToSuppress) {
        String op = operation.replace("\\", "\\\\").replace("\"", "\\\"");
        String suppress = errorCodesToSuppress.isEmpty() ? ""
                : " \"errorCodesToSuppress\": [ " + errorCodesToSuppress.stream()
                        .map(c -> "\"" + c + "\"").collect(Collectors.joining(", ")) + " ],";
        String comp = "{ \"type\": \"Computation\", \"id\": \"" + groupId + "/" + name + "\", \"name\": \"" + name + "\","
                + " \"Computation\": { \"computedFieldRelPath\": \"" + computedFieldRelPath + "\"," + suppress
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

    /** One tiered-computation alternative: an optional {@code precondition} gating an {@code operation} (the kernel
     *  selects the first whose precondition holds; a null precondition is unconditional). For {@link #withTieredComputation}. */
    public record CompAlt(String precondition, String operation, String toleranceRangeOp) {
        /** A tolerance-less alternative (strict {@code !=} implicit validation). */
        public CompAlt(String precondition, String operation) {
            this(precondition, operation, null);
        }
    }

    /**
     * Inject a TIERED computation (multiple {@code computationAlternatives}, each an optional precondition + an
     * operation, under an optional {@code commonPrecondition}) into {@code groupId}'s elements — the IF21
     * differential's kernel side, the tiered twin of {@link #withComputation}.
     */
    public static String withTieredComputation(String modelJson, String groupId, String name,
            String computedFieldRelPath, String commonPrecondition, List<CompAlt> alternatives) {
        StringBuilder alts = new StringBuilder();
        for (int i = 0; i < alternatives.size(); i++) {
            CompAlt a = alternatives.get(i);
            if (i > 0) {
                alts.append(", ");
            }
            alts.append("{ ");
            if (a.precondition() != null) {
                alts.append("\"precondition\": \"").append(esc(a.precondition())).append("\", ");
            }
            if (a.toleranceRangeOp() != null) {
                alts.append("\"toleranceRangeOp\": \"").append(esc(a.toleranceRangeOp())).append("\", ");
            }
            alts.append("\"operation\": \"").append(esc(a.operation())).append("\" }");
        }
        String common = commonPrecondition == null ? ""
                : " \"commonPrecondition\": \"" + esc(commonPrecondition) + "\",";
        String comp = "{ \"type\": \"Computation\", \"id\": \"" + groupId + "/" + name + "\", \"name\": \"" + name + "\","
                + " \"Computation\": { \"computedFieldRelPath\": \"" + computedFieldRelPath + "\"," + common
                + " \"computationAlternatives\": [ " + alts + " ],"
                + " \"errorMessage\": [ { \"locale\": \"en_US\", \"text\": \"x\" }, { \"locale\": \"de_DE\", \"text\": \"x\" } ] } },\n";
        int g = modelJson.indexOf("\"id\": \"" + groupId + "\"");
        if (g < 0) {
            g = modelJson.indexOf("\"id\" : \"" + groupId + "\"");
        }
        int br = modelJson.indexOf("\"elements\"", g);
        br = modelJson.indexOf('[', br);
        return modelJson.substring(0, br + 1) + "\n" + comp + modelJson.substring(br + 1);
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
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

    /** A computed-field {@link DocumentPointer} rendered the SAME clean {@code formatPointer} way — the per-row
     *  compute identity the corpus records (CONFORMANCE-CORPUS-SPEC §7), replacing the historical index-strip. */
    private static String fmtPointer(DocumentPointer p) {
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

    /** Run the kernel's {@code validateFull} over the instance, in the given locale (the modern {@link DocumentV2}
     *  + {@link DocumentProcessingConfig} path — the deprecated {@code validateFull(IDocument, Locale)} form is gone). */
    private static IDocumentValidationResult validate(String modelJson, Doc doc, Locale locale) {
        IDocumentModel model = expandedModel(modelJson);
        IDocumentModelResolver resolver = id -> model;
        DocumentServiceFactory dsf = new DocumentServiceFactory(resolver);
        IDocumentRtService rt = new DocumentRtServiceFactory(resolver).createDocumentRtService(dynamicConfig());
        DocumentV2 v2 = dsf.createDocumentV1V2Converter().ofDocumentV1(buildInstance(modelJson, dsf.createDocumentFactory(), doc));
        return rt.validateFull(v2, config(null, locale));
    }

    /** Evaluate an explicit document instance; return every message (code + pointer) the kernel raised. */
    static List<Msg> messages(String modelJson, Doc doc) {
        return validate(modelJson, doc, Locale.US).getMessages().stream().map(RuntimeLaws::toMsg).toList();
    }

    // ---- A12's OWN Document-JSON ingress (the serializer path, not the V1-string path) -----------

    /**
     * One pass of a Document JSON through A12's own ingress: {@code rewritten} is what
     * {@code IDocumentV2Serializer.serializeV2} writes back (compact), {@code codes} what the runtime then
     * reports. The two together expose A12's <b>value normalization</b> — it stores
     * {@code String | Boolean | BigDecimal | Instant | InstantRange} and re-renders each consumer's text from
     * that value, so an authored spelling survives neither channel. See KERNEL-FINDINGS "The canonical Document
     * JSON …".
     */
    record JsonIngress(String rewritten, List<String> codes) {
    }

    /**
     * Deserialize {@code documentJson} with the kernel's own {@link IDocumentV2Serializer}, write it back, and
     * validate it — the ingress the interpreter's public {@code readDocument} boundary reimplements. Distinct
     * from every other oracle here, which builds a V1 instance from raw STRINGS and therefore never exercises
     * A12's value conversion at all.
     */
    static JsonIngress jsonIngress(String modelJson, String documentJson) {
        IDocumentModel model = expandedModel(modelJson);
        IDocumentModelResolver resolver = id -> model;
        IDocumentV2Serializer serializer = new MDSerializerFactory().createDocumentSerializerV2(resolver);
        DocumentV2 v2 = deserializeV2(serializer, modelJson, documentJson);

        StringWriter rewritten = new StringWriter();
        serializer.serializeV2(v2, rewritten, DocumentSerializationConfig.builder()
                .prettyJsonPrinter(new MinimalPrettyPrinter()).build());

        IDocumentRtService rt = new DocumentRtServiceFactory(resolver).createDocumentRtService(dynamicConfig());
        List<String> codes = rt.validateFull(v2, config(null, Locale.US)).getMessages().stream()
                .map(IMessage::getErrorCode).sorted().toList();
        return new JsonIngress(rewritten.toString(), codes);
    }

    /**
     * Read A12's own nested Document JSON into its established {@link DocumentV2} domain type. This is the
     * narrow typed-source seam for laws whose observation depends on the stored V2 value class or decimal scale;
     * ordinary runtime laws keep using the {@link Doc} builder's deliberate V1/String profile.
     */
    static DocumentV2 documentV2(String modelJson, String documentJson) {
        IDocumentModel model = expandedModel(modelJson);
        IDocumentModelResolver resolver = id -> model;
        return deserializeV2(
                new MDSerializerFactory().createDocumentSerializerV2(resolver), modelJson, documentJson);
    }

    private static DocumentV2 deserializeV2(
            IDocumentV2Serializer serializer, String modelJson, String documentJson) {
        return serializer.deserializeV2(new StringReader(documentJson), modelId(modelJson),
                DocumentDeserializationConfig.builder().build(), notification -> { });
    }

    /** Map a kernel {@link IMessage} to our {@link Msg} (code + raw pointer + type + the clean rich channels). */
    private static Msg toMsg(IMessage m) {
        return new Msg(m.getErrorCode(), String.valueOf(m.getErrorFieldPointer()), m.getMessageType(),
                fmtPointer(m.getErrorFieldPointer()),
                m.getReferencedFieldsPointers().stream().map(RuntimeLaws::fmtPointer).toList(),
                m.getRefOmissionErrorResponsiblePointers().stream().map(RuntimeLaws::fmtPointer).toList());
    }

    // ---- modern custom-condition path (DocumentV2 + DocumentProcessingConfig) --------------------

    /**
     * Modern-path validation with a custom-condition factory — converts the instance to {@link DocumentV2}
     * and runs {@code validateFull(DocumentV2, DocumentProcessingConfig)} with {@code customConditions}
     * registered (name → md {@link ICustomCondition}). This is the path that reliably INVOKES data-reading
     * custom conditions; the deprecated {@code validateFull(IDocument, Locale)} + static-service path
     * invoked only constant ones in this harness (KERNEL-FINDINGS "CustomCondition").
     */
    private static IDocumentValidationResult validateWithConditions(String modelJson, Doc doc,
            Map<String, ICustomCondition> customConditions) {
        IDocumentModel model = expandedModel(modelJson);
        IDocumentModelResolver resolver = id -> model;
        DocumentServiceFactory dsf = new DocumentServiceFactory(resolver);
        IDocumentRtService rt = new DocumentRtServiceFactory(resolver).createDocumentRtService(dynamicConfig());
        IDocumentFactory df = dsf.createDocumentFactory();
        DocumentV2 v2 = dsf.createDocumentV1V2Converter().ofDocumentV1(buildInstance(modelJson, df, doc));
        DocumentProcessingConfig config = DocumentProcessingConfig.builder(Locale.US)
                .customConditionFactory(ICustomConditionFactory.fromMap(customConditions))
                .build();
        return rt.validateFull(v2, config);
    }

    /** Every message from the modern custom-condition path (see {@link #validateWithConditions}). */
    static List<Msg> messages(String modelJson, Doc doc, Map<String, ICustomCondition> customConditions) {
        return validateWithConditions(modelJson, doc, customConditions).getMessages().stream()
                .map(RuntimeLaws::toMsg).toList();
    }

    /**
     * Modern-path validation with a custom-FIELD-TYPE factory — threads {@code fieldTypeFactory} through
     * {@code DocumentProcessingConfig.customFieldTypeFactory}, the per-call successor to the removed static
     * {@code CustomFieldTypeService} registration (A12K-3589). The kernel's {@code FormatDefinitionVordefiniert}
     * calls {@code fieldTypeFactory.createCustomFieldValidator(name)} to auto-validate a custom-typed cell (the
     * implicit per-cell {@code Valid(field, type)}); a rejected cell reads as "unknown". Mirrors
     * {@link #validateWithConditions} for the field-type SPI.
     */
    private static IDocumentValidationResult validateWithFieldTypes(
            String modelJson, Doc doc, ICustomFieldTypeFactory fieldTypeFactory, Locale locale) {
        IDocumentModel model = expandedModel(modelJson);
        IDocumentModelResolver resolver = id -> model;
        DocumentServiceFactory dsf = new DocumentServiceFactory(resolver);
        IDocumentRtService rt = new DocumentRtServiceFactory(resolver).createDocumentRtService(dynamicConfig());
        DocumentV2 v2 = dsf.createDocumentV1V2Converter().ofDocumentV1(buildInstance(modelJson, dsf.createDocumentFactory(), doc));
        DocumentProcessingConfig config = DocumentProcessingConfig.builder(locale)
                .customFieldTypeFactory(fieldTypeFactory).build();
        return rt.validateFull(v2, config);
    }

    static List<Msg> messages(String modelJson, Doc doc, ICustomFieldTypeFactory fieldTypeFactory) {
        return messages(modelJson, doc, fieldTypeFactory, Locale.US);
    }

    static List<Msg> messages(
            String modelJson, Doc doc, ICustomFieldTypeFactory fieldTypeFactory, Locale locale) {
        return validateWithFieldTypes(modelJson, doc, fieldTypeFactory, locale)
                .getMessages().stream().map(RuntimeLaws::toMsg).toList();
    }

    static Optional<String> customFieldErrorTextOf(
            String modelJson, String errorCode, Doc doc, ICustomFieldTypeFactory fieldTypeFactory, Locale locale) {
        return validateWithFieldTypes(modelJson, doc, fieldTypeFactory, locale).getMessages().stream()
                .filter(message -> message.getErrorCode().equals(errorCode))
                .map(IMessage::getErrorText)
                .findFirst();
    }

    /** Whether {@code errorCode} fires under the modern custom-condition path. */
    static boolean fires(String modelJson, String errorCode, Doc doc, Map<String, ICustomCondition> customConditions) {
        return messages(modelJson, doc, customConditions).stream().anyMatch(m -> m.code().equals(errorCode));
    }

    /** The {@link IMessage.MessageType} of {@code errorCode} under the modern custom-condition path. */
    static Optional<IMessage.MessageType> typeOf(String modelJson, String errorCode, Doc doc,
            Map<String, ICustomCondition> customConditions) {
        return messages(modelJson, doc, customConditions).stream()
                .filter(m -> m.code().equals(errorCode)).map(Msg::type).findFirst();
    }

    // ---- partial validation (validatePart: only rules whose error field is relevant; non-relevant refs → 3VL) ----

    /**
     * One relevant entity for {@link #messagesPart}: a {@code path} and its 1-based repetition indices (one
     * per path part). Use {@code 0} ({@code IEntityInstance.ALL_SEMANTIC}, the wildcard) for "all repetitions"
     * of that path element — the kernel's encouraged form. A relevant group makes all its descendants relevant.
     */
    record Relevant(String path, List<Integer> reps) {
        static Relevant of(String path, int... reps) {
            return new Relevant(path, java.util.Arrays.stream(reps).boxed().toList());
        }
    }

    /** Partial validation: the kernel's {@code validatePart(DocumentV2, relevantEntities, config)} (codes only). */
    static Set<String> firedCodesPart(String modelJson, Doc doc, Set<Relevant> relevant) {
        return messagesPart(modelJson, doc, relevant).stream().map(Msg::code)
                .collect(Collectors.toCollection(HashSet::new));
    }

    /** Partial validation — every message the kernel raises for the given {@code relevant} entity set. */
    static List<Msg> messagesPart(String modelJson, Doc doc, Set<Relevant> relevant) {
        return messagesPart(modelJson, doc, relevant, Map.of());
    }

    /** Partial validation WITH custom conditions registered — the {@code validatePart} twin of
     *  {@link #validateWithConditions}. The relevant set passed to a {@link ICustomCondition} is what the
     *  IG10 differential probes (the kernel feeds it the partial relevant entities, null for full). */
    static List<Msg> messagesPart(String modelJson, Doc doc, Set<Relevant> relevant,
            Map<String, ICustomCondition> customConditions) {
        IDocumentModel model = expandedModel(modelJson);
        IDocumentModelResolver resolver = id -> model;
        DocumentServiceFactory dsf = new DocumentServiceFactory(resolver);
        IDocumentRtService rt = new DocumentRtServiceFactory(resolver).createDocumentRtService(dynamicConfig());
        IDocumentFactory df = dsf.createDocumentFactory();
        DocumentV2 v2 = dsf.createDocumentV1V2Converter().ofDocumentV1(buildInstance(modelJson, df, doc));
        Set<DocumentMultiPointer> relevantPointers = relevant.stream()
                .map(r -> DocumentMultiPointer.of(r.path(), r.reps())).collect(Collectors.toSet());
        DocumentProcessingConfig config = DocumentProcessingConfig.builder(Locale.US)
                .customConditionFactory(ICustomConditionFactory.fromMap(customConditions))
                .build();
        return rt.validatePart(v2, relevantPointers, config).getMessages().stream().map(RuntimeLaws::toMsg).toList();
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
    record Computed(String path, String pointer, Optional<String> value, boolean cleared, boolean errored) {
    }

    /** Public compute deltas plus the otherwise-hidden formal operand errors that make a no-value
     * computation target invalid for dependent reads. */
    record ComputationDetail(List<Computed> outcomes, List<Msg> formalErrorsInOperands) {
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
        DocumentServiceFactory dsf = new DocumentServiceFactory(resolver);
        IDocumentRtService rt = new DocumentRtServiceFactory(resolver).createDocumentRtService(dynamicConfig());
        DocumentV2 v2 = dsf.createDocumentV1V2Converter().ofDocumentV1(buildInstance(modelJson, dsf.createDocumentFactory(), doc));
        return computeDetail(rt, v2);
    }

    static ComputationDetail computeWithFormalErrors(String modelJson, Doc doc) {
        IDocumentModel model = expandedModel(modelJson);
        IDocumentModelResolver resolver = id -> model;
        DocumentServiceFactory dsf = new DocumentServiceFactory(resolver);
        IDocumentRtService rt = new DocumentRtServiceFactory(resolver).createDocumentRtService(dynamicConfig());
        DocumentV2 v2 = dsf.createDocumentV1V2Converter().ofDocumentV1(
                buildInstance(modelJson, dsf.createDocumentFactory(), doc));
        return computeDetailWithFormalErrors(rt, v2);
    }

    /**
     * Map a {@code compute()} run over {@code instance} to its value/cleared/errored detail — shared by the
     * Groovy {@link #compute(String, Doc)} and the {@link JavaKernelEngine#compute(DocumentV2)} tri-differential
     * path, so both codegen strategies report compute outcomes at the same granularity as the interpreter.
     */
    private static List<Computed> computeDetail(IDocumentRtService rt, DocumentV2 instance) {
        return computeDetail(rt, instance, Locale.US);
    }

    private static List<Computed> computeDetail(IDocumentRtService rt, DocumentV2 instance, Locale locale) {
        return computeDetail(rt, instance, config(null, locale));
    }

    private static List<Computed> computeDetail(
            IDocumentRtService rt, DocumentV2 instance, DocumentProcessingConfig config) {
        return computeDetailOf(rt.compute(instance, config));
    }

    private static ComputationDetail computeDetailWithFormalErrors(IDocumentRtService rt, DocumentV2 instance) {
        IDocumentComputationResult result = rt.compute(instance, config(null, Locale.US));
        return new ComputationDetail(
                computeDetailOf(result),
                result.getFormalErrorsInOperands().stream().map(RuntimeLaws::toMsg).toList());
    }

    /** Select the <b>three-channel change projection</b> from the fuller five-channel {@link IDocumentComputationResult}
     *  (WithChanges → value, WithErrors → errored, Cleared → cleared; the full result also carries
     *  {@code getComputedFieldInstancesWithoutErrors}/{@code getFormalErrorsInOperands} — KERNEL-FINDINGS §11
     *  five-channel supplement, locked by {@link ComputeResultChannelsLawsTest}). Shared by the legacy
     *  {@link #computeDetail} and the modern clock-pinnable {@link #computeModern} path. */
    private static List<Computed> computeDetailOf(IDocumentComputationResult result) {
        List<Computed> out = new ArrayList<>();
        result.getComputedFieldInstancesWithChanges().forEach(c ->
                out.add(new Computed(c.getPath(), fmtPointer(c.pointer()), c.getStringValue(), false, false)));
        result.getComputedFieldInstancesWithErrors().forEach(c ->
                out.add(new Computed(c.getPath(), fmtPointer(c.pointer()), c.getStringValue(), false, true)));
        result.getClearedFieldInstances().forEach(c ->
                out.add(new Computed(c.getPath(), fmtPointer(c.pointer()), Optional.empty(), true, false)));
        return out;
    }

    /** All five {@link IDocumentComputationResult} channels plus the {@code noErrorOccurred} predicate, over a
     *  dynamic-Groovy {@code compute()} — the five-channel result oracle for {@link ComputeResultChannelsLawsTest}
     *  (KERNEL-FINDINGS §11 five-channel supplement). The instance channels are rendered as {@code fmtPointer}
     *  strings; {@code withChanges} is a subset of {@code withoutErrors}. */
    record ResultChannels(List<String> withoutErrors, List<String> withChanges, List<String> withErrors,
            List<String> cleared, List<Msg> formalErrorsInOperands, boolean noErrorOccurred) {
    }

    static ResultChannels resultChannels(String modelJson, Doc doc) {
        IDocumentModel model = expandedModel(modelJson);
        IDocumentModelResolver resolver = id -> model;
        DocumentServiceFactory dsf = new DocumentServiceFactory(resolver);
        IDocumentRtService rt = new DocumentRtServiceFactory(resolver).createDocumentRtService(dynamicConfig());
        DocumentV2 v2 = dsf.createDocumentV1V2Converter().ofDocumentV1(
                buildInstance(modelJson, dsf.createDocumentFactory(), doc));
        return resultChannelsOf(rt.compute(v2, config(null, Locale.US)));
    }

    private static ResultChannels resultChannelsOf(IDocumentComputationResult result) {
        return new ResultChannels(
                pointers(result.getComputedFieldInstancesWithoutErrors()),
                pointers(result.getComputedFieldInstancesWithChanges()),
                pointers(result.getComputedFieldInstancesWithErrors()),
                pointers(result.getClearedFieldInstances()),
                result.getFormalErrorsInOperands().stream().map(RuntimeLaws::toMsg).toList(),
                result.noErrorOccurred());
    }

    private static List<String> pointers(Collection<IComputedFieldInstance> instances) {
        return instances.stream().map(c -> fmtPointer(c.pointer())).toList();
    }

    // ---- modern path with an optional pinned clock (the corpus capture/replay path, §9/§13) ------

    /** The modern {@link DocumentProcessingConfig} at {@link Locale#US}, pinning {@code clock} as
     *  {@code currentDateForTest} (both Today and Now) when non-null (else the real current date). */
    static DocumentProcessingConfig config(Date clock) {
        return config(clock, Locale.US);
    }

    /** As {@link #config(Date)} but at an explicit {@code locale} — the kernel rejects a locale the model does not
     *  declare (the real-corpus sweep passes a model-declared locale; signatures are locale-independent). */
    static DocumentProcessingConfig config(Date clock, Locale locale) {
        return config(clock, locale, Map.of());
    }

    /** The benchmark/corpus config with identical per-call custom conditions on both kernel strategies. */
    static DocumentProcessingConfig config(
            Date clock, Locale locale, Map<String, ICustomCondition> customConditions) {
        return config(clock, locale, customConditions, null);
    }

    /** The benchmark/corpus config with identical per-call runtime bindings on both kernel strategies. */
    static DocumentProcessingConfig config(Date clock, Locale locale,
            Map<String, ICustomCondition> customConditions, ICustomFieldTypeFactory fieldTypeFactory) {
        var b = DocumentProcessingConfig.builder(locale);
        if (clock != null) {
            b.currentDateForTest(new Date(clock.getTime()));
        }
        Map<String, ICustomCondition> conditions = Map.copyOf(customConditions);
        if (!conditions.isEmpty()) {
            b.customConditionFactory(ICustomConditionFactory.fromMap(conditions));
        }
        if (fieldTypeFactory != null) {
            b.customFieldTypeFactory(fieldTypeFactory);
        }
        return b.build();
    }

    /** Groovy-dynamic {@code validateFull(DocumentV2, config)} — the corpus capture anchor (S3/§13), clock-pinnable. */
    static List<Msg> messagesModern(String modelJson, Doc doc, Date clock) {
        return messagesModern(modelJson, doc, clock, Locale.US);
    }

    /** As {@link #messagesModern(String, Doc, Date)} but at an explicit locale (the sweep's model-declared locale). */
    static List<Msg> messagesModern(String modelJson, Doc doc, Date clock, Locale locale) {
        IDocumentModelResolver resolver = resolverOf(modelJson);
        DocumentServiceFactory dsf = new DocumentServiceFactory(resolver);
        IDocumentRtService rt = new DocumentRtServiceFactory(resolver).createDocumentRtService(dynamicConfig());
        DocumentV2 v2 = dsf.createDocumentV1V2Converter().ofDocumentV1(buildInstance(modelJson, dsf.createDocumentFactory(), doc));
        return rt.validateFull(v2, config(clock, locale)).getMessages().stream().map(RuntimeLaws::toMsg).toList();
    }

    /** Groovy-dynamic {@code validatePart(DocumentV2, relevant, config)} — clock-pinnable. */
    static List<Msg> messagesPartModern(String modelJson, Doc doc, Set<Relevant> relevant, Date clock) {
        IDocumentModelResolver resolver = resolverOf(modelJson);
        DocumentServiceFactory dsf = new DocumentServiceFactory(resolver);
        IDocumentRtService rt = new DocumentRtServiceFactory(resolver).createDocumentRtService(dynamicConfig());
        DocumentV2 v2 = dsf.createDocumentV1V2Converter().ofDocumentV1(buildInstance(modelJson, dsf.createDocumentFactory(), doc));
        Set<DocumentMultiPointer> rel = relevant.stream()
                .map(r -> DocumentMultiPointer.of(r.path(), r.reps())).collect(Collectors.toSet());
        return rt.validatePart(v2, rel, config(clock)).getMessages().stream().map(RuntimeLaws::toMsg).toList();
    }

    /** Groovy-dynamic {@code compute(DocumentV2, config)} — clock-pinnable (the corpus compute capture path). */
    static List<Computed> computeModern(String modelJson, Doc doc, Date clock) {
        return computeModern(modelJson, doc, clock, Locale.US);
    }

    /** As {@link #computeModern(String, Doc, Date)} but at an explicit locale (the sweep's model-declared locale). */
    static List<Computed> computeModern(String modelJson, Doc doc, Date clock, Locale locale) {
        IDocumentModelResolver resolver = resolverOf(modelJson);
        DocumentServiceFactory dsf = new DocumentServiceFactory(resolver);
        IDocumentRtService rt = new DocumentRtServiceFactory(resolver).createDocumentRtService(dynamicConfig());
        DocumentV2 v2 = dsf.createDocumentV1V2Converter().ofDocumentV1(buildInstance(modelJson, dsf.createDocumentFactory(), doc));
        return computeDetailOf(rt.compute(v2, config(clock, locale)));
    }

    /** A single-model {@link IDocumentModelResolver} for {@code modelJson} (deserialized + expanded once). */
    private static IDocumentModelResolver resolverOf(String modelJson) {
        IDocumentModel model = expandedModel(modelJson);
        return id -> model;
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

    /**
     * The kernel's **form-engine validation flow**: {@code compute()} → {@code applyTo(doc)} →
     * {@code validateFull(doc)}. The computation result's {@code applyTo(IDocument)} mutates the document in
     * place — applying the {@code WithChanges} values and CLEARING the {@code WithErrors}/{@code Cleared}
     * instances (KERNEL-FINDINGS "validateFull does NOT run computations" + {@code IDocumentComputationResult}) —
     * then full validation runs over the applied document. This is the oracle for {@link DmInterpreter#applyComputations}
     * + {@code validate} (the {@code model eval} default), the apply-computed-back step the interpreter mirrors.
     * Uses the modern {@link com.mgmtp.a12.kernel.md.rt.api.IDocumentComputationResult#applyTo(DocumentV2)
     * applyTo(DocumentV2)}: {@link DocumentV2} is immutable, so {@code applyTo} RETURNS a new document (it does not
     * mutate in place like the old {@code applyTo(IDocument)}), and the returned instance is what gets validated.
     */
    static List<Msg> messagesAfterCompute(String modelJson, Doc doc, Locale locale) {
        IDocumentModel model = expandedModel(modelJson);
        IDocumentModelResolver resolver = id -> model;
        DocumentServiceFactory dsf = new DocumentServiceFactory(resolver);
        IDocumentRtService rt = new DocumentRtServiceFactory(resolver).createDocumentRtService(dynamicConfig());
        DocumentV2 v2 = dsf.createDocumentV1V2Converter().ofDocumentV1(buildInstance(modelJson, dsf.createDocumentFactory(), doc));
        DocumentProcessingConfig config = config(null, locale);
        DocumentV2 applied = rt.compute(v2, config).applyTo(v2);   // changes set, errored/cleared emptied — new immutable doc
        return rt.validateFull(applied, config).getMessages().stream().map(RuntimeLaws::toMsg).toList();
    }

    /** Whether the rule with {@code errorCode} fires for the given explicit instance. */
    static boolean fires(String modelJson, String errorCode, Doc doc) {
        return firedCodes(modelJson, doc).contains(errorCode);
    }

    // ---- the post-application cell states (the applied-DocumentV2 read-back, IF126) ---------------

    /** One probed cell: its name path + 1-based indices, one per path segment, the field's own included. */
    record CellProbe(String path, List<Integer> reps) {
    }

    /**
     * The kernel's post-APPLICATION cell states — {@code compute()} → {@code applyTo(DocumentV2)} → read each
     * probe on the applied document — as {@code path|absent} / {@code path|presentEmpty} /
     * {@code path|presentValue|<text>} (the typed value in canonical string form, the declared
     * string-comparison rule). The groovy-dynamic leg; {@link JavaKernelEngine#appliedStates} is the
     * static-JAVA twin. The oracle for {@code Document.cellStateAt} over
     * {@code DmInterpreter.applyComputations} (KERNEL-FINDINGS §11 "post-application cell state").
     */
    static List<String> appliedStates(String modelJson, Doc doc, List<CellProbe> probes) {
        IDocumentModel model = expandedModel(modelJson);
        IDocumentModelResolver resolver = id -> model;
        DocumentServiceFactory dsf = new DocumentServiceFactory(resolver);
        IDocumentRtService rt = new DocumentRtServiceFactory(resolver).createDocumentRtService(dynamicConfig());
        DocumentV2 v2 = dsf.createDocumentV1V2Converter().ofDocumentV1(buildInstance(modelJson, dsf.createDocumentFactory(), doc));
        DocumentV2 applied = rt.compute(v2, config(null, Locale.US)).applyTo(v2);
        return probes.stream().map(p -> p.path() + "|" + appliedStateAt(applied, p)).toList();
    }

    /** Compute against {@code source}, apply that already-classified result to a separate model-compatible
     *  {@code destination}, and inspect the destination. This distinguishes source-relative result classification
     *  from recomputing change equality at application time (SPEC-2026-07-22-08). */
    static List<String> appliedStatesToDestination(
            String modelJson, Doc source, Doc destination, List<CellProbe> probes) {
        IDocumentModel model = expandedModel(modelJson);
        IDocumentModelResolver resolver = id -> model;
        DocumentServiceFactory dsf = new DocumentServiceFactory(resolver);
        IDocumentRtService rt = new DocumentRtServiceFactory(resolver).createDocumentRtService(dynamicConfig());
        DocumentV2 sourceV2 = dsf.createDocumentV1V2Converter().ofDocumentV1(
                buildInstance(modelJson, dsf.createDocumentFactory(), source));
        DocumentV2 destinationV2 = dsf.createDocumentV1V2Converter().ofDocumentV1(
                buildInstance(modelJson, dsf.createDocumentFactory(), destination));
        DocumentV2 applied = rt.compute(sourceV2, config(null, Locale.US)).applyTo(destinationV2);
        return probes.stream().map(p -> p.path() + "|" + appliedStateAt(applied, p)).toList();
    }

    /** Walk the applied document to {@code probe} — absent / presentEmpty / presentValue|text kept distinct. */
    private static String appliedStateAt(DocumentV2 applied, CellProbe probe) {
        String[] segments = probe.path().substring(1).split("/");
        GroupInstanceV2 current = applied;
        for (int i = 0; i < segments.length - 1; i++) {
            RepetitionsV2 rows = current.directSubgroup(segments[i]);
            int index = probe.reps().get(i);
            GroupInstanceV2 row = rows.size() >= index ? rows.get(index) : null;
            if (row == null) {
                return "absent";
            }
            current = row;
        }
        FieldInstanceV2 field = current.directField(segments[segments.length - 1]);
        if (field == null) {
            return "absent";
        }
        Object value = field.value();
        if (value == null) {
            return "presentEmpty";
        }
        String text = value instanceof java.math.BigDecimal d ? d.toPlainString() : String.valueOf(value);
        return "presentValue|" + text;
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
        private final DocumentServiceFactory dsf;
        private final IDocumentFactory df;

        KernelEngine(String modelJson) {
            this.modelJson = modelJson;
            IDocumentModel model = expandedModel(modelJson);
            IDocumentModelResolver resolver = id -> model;
            this.dsf = new DocumentServiceFactory(resolver);
            this.rt = new DocumentRtServiceFactory(resolver).createDocumentRtService(dynamicConfig());
            this.df = dsf.createDocumentFactory();
        }

        /** Build the kernel {@link DocumentV2} for {@code doc} once — including the V1-&gt;V2 conversion, so it is
         *  OUTSIDE the timed region (V2 is immutable, so reusing one instance across timed runs has no aliasing hazard). */
        public DocumentV2 instance(Doc doc) {
            return dsf.createDocumentV1V2Converter().ofDocumentV1(buildInstance(modelJson, df, doc));
        }

        /**
         * Every fired error code for an already-built instance, <b>sorted</b> — the timed call AND the
         * perf-harness correctness payload. A {@code List} (not a {@code Set}) so the comparison is a
         * <em>multiset</em>: a per-row rule firing on K rows yields K copies, and the interpreter must
         * produce the same K — catching a per-row firing mismatch a code-set would hide.
         */
        public List<String> firedCodesSorted(DocumentV2 instance) {
            return rt.validateFull(instance, config(null, Locale.US)).getMessages().stream()
                    .map(IMessage::getErrorCode).sorted().toList();
        }

        /** Fired codes under an exact injected clock, retaining this engine's prepared model/runtime service. */
        public List<String> firedCodesSorted(DocumentV2 instance, Date clock) {
            return rt.validateFull(instance, config(clock, Locale.US)).getMessages().stream()
                    .map(IMessage::getErrorCode).sorted().toList();
        }

        /** Full validation signatures ({@code code|type|indexed-pointer}), sorted for exact perf eligibility. */
        public List<String> validationSignaturesSorted(DocumentV2 instance) {
            return validationSignaturesSorted(instance, Locale.US);
        }

        public List<String> validationSignaturesSorted(DocumentV2 instance, Locale locale) {
            return rt.validateFull(instance, config(null, locale)).getMessages().stream()
                    .map(RuntimeLaws::validationSignature).sorted().toList();
        }

        /** Full signatures under explicit runtime bindings; model/document construction stays build-once. */
        public List<String> validationSignaturesSorted(DocumentV2 instance, Locale locale, Date clock,
                Map<String, ICustomCondition> customConditions) {
            return validationSignaturesSorted(instance, locale, clock, customConditions, null);
        }

        /** Full signatures under explicit runtime bindings; model/document construction stays build-once. */
        public List<String> validationSignaturesSorted(DocumentV2 instance, Locale locale, Date clock,
                Map<String, ICustomCondition> customConditions, ICustomFieldTypeFactory fieldTypeFactory) {
            return rt.validateFull(instance, config(clock, locale, customConditions, fieldTypeFactory)).getMessages().stream()
                    .map(RuntimeLaws::validationSignature).sorted().toList();
        }

        /** Every computed-field outcome for an already-built instance, normalized + <b>sorted</b> (the B7 compute payload). */
        public List<String> computeOutcomesSorted(DocumentV2 instance) {
            return computeSorted(rt, instance);
        }

        /** Full computation signatures ({@code indexed-pointer|outcome|value}), sorted for exact perf eligibility. */
        public List<String> computationSignaturesSorted(DocumentV2 instance) {
            return computationSignaturesSorted(instance, Locale.US);
        }

        public List<String> computationSignaturesSorted(DocumentV2 instance, Locale locale) {
            return computeSignaturesSorted(rt, instance, locale);
        }

        /** Full computation signatures under the same explicit runtime bindings as validation. */
        public List<String> computationSignaturesSorted(DocumentV2 instance, Locale locale, Date clock,
                Map<String, ICustomCondition> customConditions) {
            return computationSignaturesSorted(instance, locale, clock, customConditions, null);
        }

        /** Full computation signatures under the same explicit runtime bindings as validation. */
        public List<String> computationSignaturesSorted(DocumentV2 instance, Locale locale, Date clock,
                Map<String, ICustomCondition> customConditions, ICustomFieldTypeFactory fieldTypeFactory) {
            return computeSignaturesSorted(rt, instance, config(clock, locale, customConditions, fieldTypeFactory));
        }

        /** The full five-channel computation result over an already-built typed source document. */
        ResultChannels resultChannels(DocumentV2 instance) {
            return resultChannelsOf(rt.compute(instance, config(null, Locale.US)));
        }
    }

    public static KernelEngine kernelEngine(String modelJson) {
        return new KernelEngine(modelJson);
    }

    /**
     * Run the kernel's {@code compute()} over an already-built instance and return each computed-field outcome
     * as {@code normalizedPath|value} / {@code …|CLEARED} / {@code …|ERR}, <b>sorted</b> — the build-once compute
     * twin of {@link KernelEngine#firedCodesSorted}. Repetition indices are stripped from the path so a per-row
     * computed field (the kernel emits one indexed instance per row) compares to the interpreter's bare-path
     * {@code Computed.targetPath}. The compared instance leaves the computed field empty in the seed, so the
     * kernel reports every row as a change (its result set is "with changes", not all rows).
     */
    private static List<String> computeSorted(IDocumentRtService rt, DocumentV2 instance) {
        IDocumentComputationResult r = rt.compute(instance, config(null, Locale.US));
        List<String> out = new ArrayList<>();
        r.getComputedFieldInstancesWithChanges().forEach(c -> out.add(normComputedPath(c.getPath()) + "|" + c.getStringValue().orElse("")));
        r.getComputedFieldInstancesWithErrors().forEach(c -> out.add(normComputedPath(c.getPath()) + "|ERR"));
        r.getClearedFieldInstances().forEach(c -> out.add(normComputedPath(c.getPath()) + "|CLEARED"));
        out.sort(null);
        return out;
    }

    private static String validationSignature(IMessage message) {
        return message.getErrorCode() + "|" + message.getMessageType().name() + "|" + fmtPointer(message.getErrorFieldPointer());
    }

    private static List<String> computeSignaturesSorted(IDocumentRtService rt, DocumentV2 instance, Locale locale) {
        return computeSignaturesSorted(rt, instance, config(null, locale));
    }

    private static List<String> computeSignaturesSorted(
            IDocumentRtService rt, DocumentV2 instance, DocumentProcessingConfig config) {
        return computeDetail(rt, instance, config).stream().map(c -> {
            if (c.cleared()) {
                return c.pointer() + "|CLEARED";
            }
            if (c.errored()) {
                return c.pointer() + "|ERRORED|" + c.value().orElse("");
            }
            return c.pointer() + "|VALUE|" + c.value().orElse("");
        }).sorted().toList();
    }

    private static String normComputedPath(String path) {
        return path.replaceAll("\\[\\d+\\]", "");
    }

    /**
     * A build-ONCE kernel evaluator that runs the kernel's OWN generated validator compiled as <b>Java</b>
     * (not Groovy) — the <em>Groovy-tax-free</em> baseline (INTERPRETER-PERFORMANCE.md "the kernel baseline:
     * Groovy vs Java"). It generates Java source via {@code generateValidationCode(…, JAVA)}, compiles it
     * in-memory with {@link javax.tools.JavaCompiler}, and runs it through the kernel's <b>static</b>
     * rt-service path ({@code DocumentStaticRtService}) — the SAME {@code validateFull} scaffolding the Groovy
     * path uses, so the fired-code output is identical and only the executed language differs. So the
     * Groovy-vs-Java comparison isolates the dynamic-dispatch/metaclass execution tax from the algorithm.
     *
     * <p>Code generation + in-memory compilation happen ONCE in the constructor (outside any timed region),
     * mirroring {@link KernelEngine}. The codegen step is language-parameterized ({@link #generateSources}),
     * so a future <em>kernel-JS vs interpreter-JS</em> comparison reuses the same source-generation seam.
     */
    public static final class JavaKernelEngine {
        private final String modelJson;
        private final IDocumentRtService rt;
        private final IDocumentFactory df;
        private final DocumentServiceFactory dsf;   // kept for the V1→V2 conversion the modern validatePart needs

        JavaKernelEngine(String modelJson) {
            this.modelJson = modelJson;
            IDocumentModel expanded = expandedModel(modelJson);
            Properties idToPackage = new Properties();   // OUT: modelId -> generated package (the static path needs it)
            Map<String, String> sources = generateSources(
                    expanded, IValidationCodeGeneratorConfig.ProgrammingLanguage.JAVA, idToPackage);
            ClassLoader codeLoader = compileInMemory(sources);
            IDocumentModelResolver resolver = id -> expanded;
            this.dsf = new DocumentServiceFactory(resolver);
            this.rt = new DocumentRtServiceFactory(resolver).createDocumentRtService(staticConfig(idToPackage, codeLoader));
            this.df = dsf.createDocumentFactory();
        }

        /** Build the kernel {@link DocumentV2} for {@code doc} once — including the V1-&gt;V2 conversion, OUTSIDE the
         *  timed region (the static rt-service consumes {@link DocumentV2} just like the dynamic one). */
        public DocumentV2 instance(Doc doc) {
            return dsf.createDocumentV1V2Converter().ofDocumentV1(buildInstance(modelJson, df, doc));
        }

        /** Every fired error code for an already-built instance, <b>sorted</b> — the multiset (see {@link KernelEngine}). */
        public List<String> firedCodesSorted(DocumentV2 instance) {
            return rt.validateFull(instance, config(null, Locale.US)).getMessages().stream()
                    .map(IMessage::getErrorCode).sorted().toList();
        }

        /** Fired codes under an exact injected clock, retaining this generated-Java runtime service. */
        public List<String> firedCodesSorted(DocumentV2 instance, Date clock) {
            return rt.validateFull(instance, config(clock, Locale.US)).getMessages().stream()
                    .map(IMessage::getErrorCode).sorted().toList();
        }

        /** Full validation signatures ({@code code|type|indexed-pointer}), sorted for exact perf eligibility. */
        public List<String> validationSignaturesSorted(DocumentV2 instance) {
            return validationSignaturesSorted(instance, Locale.US);
        }

        public List<String> validationSignaturesSorted(DocumentV2 instance, Locale locale) {
            return rt.validateFull(instance, config(null, locale)).getMessages().stream()
                    .map(RuntimeLaws::validationSignature).sorted().toList();
        }

        /** Full signatures under explicit runtime bindings; code generation remains outside every timed call. */
        public List<String> validationSignaturesSorted(DocumentV2 instance, Locale locale, Date clock,
                Map<String, ICustomCondition> customConditions) {
            return validationSignaturesSorted(instance, locale, clock, customConditions, null);
        }

        /** Full signatures under explicit runtime bindings; code generation remains outside every timed call. */
        public List<String> validationSignaturesSorted(DocumentV2 instance, Locale locale, Date clock,
                Map<String, ICustomCondition> customConditions, ICustomFieldTypeFactory fieldTypeFactory) {
            return rt.validateFull(instance, config(clock, locale, customConditions, fieldTypeFactory)).getMessages().stream()
                    .map(RuntimeLaws::validationSignature).sorted().toList();
        }

        /** Every computed-field outcome for an already-built instance, normalized + <b>sorted</b> (the B7 compute payload). */
        public List<String> computeOutcomesSorted(DocumentV2 instance) {
            return computeSorted(rt, instance);
        }

        /** Full computation signatures ({@code indexed-pointer|outcome|value}), sorted for exact perf eligibility. */
        public List<String> computationSignaturesSorted(DocumentV2 instance) {
            return computationSignaturesSorted(instance, Locale.US);
        }

        public List<String> computationSignaturesSorted(DocumentV2 instance, Locale locale) {
            return computeSignaturesSorted(rt, instance, locale);
        }

        /** Full computation signatures under the same explicit runtime bindings as validation. */
        public List<String> computationSignaturesSorted(DocumentV2 instance, Locale locale, Date clock,
                Map<String, ICustomCondition> customConditions) {
            return computationSignaturesSorted(instance, locale, clock, customConditions, null);
        }

        /** Full computation signatures under the same explicit runtime bindings as validation. */
        public List<String> computationSignaturesSorted(DocumentV2 instance, Locale locale, Date clock,
                Map<String, ICustomCondition> customConditions, ICustomFieldTypeFactory fieldTypeFactory) {
            return computeSignaturesSorted(rt, instance, config(clock, locale, customConditions, fieldTypeFactory));
        }

        /** The full five-channel computation result over an already-built typed source document. */
        ResultChannels resultChannels(DocumentV2 instance) {
            return resultChannelsOf(rt.compute(instance, config(null, Locale.US)));
        }

        /**
         * Every validation message (code + type + clean pointer) for an already-built instance — the full
         * {@code code|type|pointer} signature the codegen tri-differential (WI-1) compares, mirroring the Groovy
         * {@link RuntimeLaws#messages(String, Doc)}. So the static-JAVA codegen strategy is diffed against the
         * interpreter and the Groovy-dynamic strategy at the SAME granularity (polarity + row, not codes-only).
         */
        List<Msg> messages(DocumentV2 instance) {
            return rt.validateFull(instance, config(null, Locale.US)).getMessages().stream().map(RuntimeLaws::toMsg).toList();
        }

        /** Fully rendered validation text from the generated-Java strategy, at an explicit locale. */
        Optional<String> errorTextOf(DocumentV2 instance, String errorCode, Locale locale) {
            return rt.validateFull(instance, config(null, locale)).getMessages().stream()
                    .filter(message -> message.getErrorCode().equals(errorCode))
                    .map(IMessage::getErrorText)
                    .findFirst();
        }

        /** Generated-Java validation with the same per-call custom-condition callbacks as the dynamic path. */
        List<Msg> messages(DocumentV2 instance, Map<String, ICustomCondition> customConditions) {
            DocumentProcessingConfig config = DocumentProcessingConfig.builder(Locale.US)
                    .customConditionFactory(ICustomConditionFactory.fromMap(customConditions)).build();
            return rt.validateFull(instance, config).getMessages().stream().map(RuntimeLaws::toMsg).toList();
        }

        /** Generated-Java validation with the same per-call custom-field-type factory as the dynamic path. */
        List<Msg> messages(DocumentV2 instance, ICustomFieldTypeFactory fieldTypeFactory) {
            return messages(instance, fieldTypeFactory, Locale.US);
        }

        List<Msg> messages(
                DocumentV2 instance, ICustomFieldTypeFactory fieldTypeFactory, Locale locale) {
            DocumentProcessingConfig config = DocumentProcessingConfig.builder(locale)
                    .customFieldTypeFactory(fieldTypeFactory).build();
            return rt.validateFull(instance, config).getMessages().stream().map(RuntimeLaws::toMsg).toList();
        }

        Optional<String> customFieldErrorTextOf(
                DocumentV2 instance, String errorCode, ICustomFieldTypeFactory fieldTypeFactory, Locale locale) {
            DocumentProcessingConfig config = DocumentProcessingConfig.builder(locale)
                    .customFieldTypeFactory(fieldTypeFactory).build();
            return rt.validateFull(instance, config).getMessages().stream()
                    .filter(message -> message.getErrorCode().equals(errorCode))
                    .map(IMessage::getErrorText)
                    .findFirst();
        }

        /** Every computed-field outcome (value / cleared / errored) for an already-built instance — the compute
         *  tri-differential payload, mirroring the Groovy {@link RuntimeLaws#compute(String, Doc)}. */
        List<Computed> compute(DocumentV2 instance) {
            return computeDetail(rt, instance);
        }

        ComputationDetail computeWithFormalErrors(DocumentV2 instance) {
            return computeDetailWithFormalErrors(rt, instance);
        }

        /** The post-application cell states over the static-JAVA strategy — the twin of
         *  {@link RuntimeLaws#appliedStates(String, Doc, List)}. */
        List<String> appliedStates(Doc doc, List<CellProbe> probes) {
            DocumentV2 v2 = instance(doc);
            DocumentV2 applied = rt.compute(v2, config(null, Locale.US)).applyTo(v2);
            return probes.stream().map(p -> p.path() + "|" + appliedStateAt(applied, p)).toList();
        }

        /** The form-engine flow ({@code compute → applyTo → validateFull}) over the static-JAVA strategy —
         *  the twin of {@link RuntimeLaws#messagesAfterCompute(String, Doc, Locale)}. */
        List<Msg> messagesAfterCompute(Doc doc) {
            DocumentV2 v2 = instance(doc);
            DocumentProcessingConfig config = config(null, Locale.US);
            DocumentV2 applied = rt.compute(v2, config).applyTo(v2);
            return rt.validateFull(applied, config).getMessages().stream().map(RuntimeLaws::toMsg).toList();
        }

        /**
         * Partial validation over the static-JAVA codegen path — the {@code validatePart} twin of {@link #messages}
         * for the WI-1 tri-differential. The already-{@link DocumentV2} instance (the modern-path requirement the
         * static rt-service shares with the dynamic one) is run through {@code validatePart} for the given relevant
         * entities, returning the same {@code code|type|pointer} signatures. Whether the static path supports this
         * at all is what {@code JavaStaticPartialBaselineDiffTest} pins.
         */
        List<Msg> messagesPart(Doc doc, Set<Relevant> relevant) {
            return messagesPart(doc, relevant, Map.of());
        }

        /** Static-Java partial validation with the same per-call custom-condition callbacks as the dynamic path. */
        List<Msg> messagesPart(
                Doc doc, Set<Relevant> relevant, Map<String, ICustomCondition> customConditions) {
            DocumentV2 v2 = instance(doc);
            Set<DocumentMultiPointer> relevantPointers = relevant.stream()
                    .map(r -> DocumentMultiPointer.of(r.path(), r.reps())).collect(Collectors.toSet());
            DocumentProcessingConfig config = DocumentProcessingConfig.builder(Locale.US)
                    .customConditionFactory(ICustomConditionFactory.fromMap(customConditions)).build();
            return rt.validatePart(v2, relevantPointers, config).getMessages().stream().map(RuntimeLaws::toMsg).toList();
        }

        // ---- modern clock-pinnable path (the corpus tri-verification's java-static leg, §9) ----------

        /** Static-JAVA {@code validateFull(DocumentV2, config)} — clock-pinnable. */
        List<Msg> messagesModern(Doc doc, Date clock) {
            return messagesModern(doc, clock, Locale.US);
        }

        /** As {@link #messagesModern(Doc, Date)} but at an explicit locale (the sweep's model-declared locale). */
        List<Msg> messagesModern(Doc doc, Date clock, Locale locale) {
            return rt.validateFull(instance(doc), config(clock, locale)).getMessages().stream().map(RuntimeLaws::toMsg).toList();
        }

        /** Static-JAVA {@code validatePart(DocumentV2, relevant, config)} — clock-pinnable. */
        List<Msg> messagesPartModern(Doc doc, Set<Relevant> relevant, Date clock) {
            Set<DocumentMultiPointer> rel = relevant.stream()
                    .map(r -> DocumentMultiPointer.of(r.path(), r.reps())).collect(Collectors.toSet());
            return rt.validatePart(instance(doc), rel, config(clock)).getMessages().stream().map(RuntimeLaws::toMsg).toList();
        }

        /** Static-JAVA {@code compute(DocumentV2, config)} — clock-pinnable. */
        List<Computed> computeModern(Doc doc, Date clock) {
            return computeModern(doc, clock, Locale.US);
        }

        /** As {@link #computeModern(Doc, Date)} but at an explicit locale (the sweep's model-declared locale). */
        List<Computed> computeModern(Doc doc, Date clock, Locale locale) {
            return computeDetailOf(rt.compute(instance(doc), config(clock, locale)));
        }
    }

    /** Build a Java-baseline kernel engine (generates + in-memory-compiles the kernel's Java validator for {@code modelJson}). */
    public static JavaKernelEngine kernelJavaEngine(String modelJson) {
        return new JavaKernelEngine(modelJson);
    }

    /**
     * Generate and join the kernel's Java source for a model without compiling it. Package-private for
     * structural law locks whose runtime result cannot expose generated evaluation order.
     */
    static String generatedJavaSource(String modelJson) {
        return generatedSource(modelJson, IValidationCodeGeneratorConfig.ProgrammingLanguage.JAVA);
    }

    /**
     * The GROOVY twin of {@link #generatedJavaSource}. Needed because some lowering decisions exist in ONE
     * strategy only — Groovy splits a rule's condition lines into closures once the rule reaches
     * {@code NUM_CONDITIONLINES_PER_METHOD_GROOVY}, which no runtime result can reveal — so a law lock over
     * that mode must read the emitted Groovy to prove it actually engaged.
     */
    static String generatedGroovySource(String modelJson) {
        return generatedSource(modelJson, IValidationCodeGeneratorConfig.ProgrammingLanguage.GROOVY);
    }

    private static String generatedSource(String modelJson,
            IValidationCodeGeneratorConfig.ProgrammingLanguage lang) {
        Properties idToPackage = new Properties();
        return generateSources(expandedModel(modelJson), lang, idToPackage).entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> "// " + entry.getKey() + "\n" + entry.getValue())
                .collect(Collectors.joining("\n"));
    }

    /**
     * Generate the kernel's validation source for {@code expanded} in {@code lang}; returns {@code zipPath ->
     * source} for that language's source entries and fills {@code idToPackageOut} with the {@code modelId ->
     * generated-package} mapping the static-model path needs. Language-parameterized: Java and Groovy today,
     * JS when its runtime becomes buildable here.
     */
    private static Map<String, String> generateSources(IDocumentModel expanded,
            IValidationCodeGeneratorConfig.ProgrammingLanguage lang, Properties idToPackageOut) {
        IDocumentModelService modelService = new DocumentModelServiceFactory().createDocumentModelService();
        IValidationCodeGeneratorConfig config = () -> lang;
        List<String> notes = new ArrayList<>();
        byte[] zip = modelService.generateValidationCode(expanded, config, idToPackageOut,
                n -> notes.add(n.getSeverity() + ": " + n.getMessage()));
        if (zip == null) {
            throw new IllegalStateException("generateValidationCode(" + lang + ") returned null; notifications=" + notes);
        }
        String suffix = switch (lang) {
            case JAVA -> ".java";
            case GROOVY -> ".groovy";
            case JAVASCRIPT -> ".js";
        };
        Map<String, String> sources = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.getName().endsWith(suffix)) {
                    sources.put(e.getName(), new String(zis.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("failed to read generated code zip", e);
        }
        return sources;
    }

    private static IDocumentStaticServiceConfig staticConfig(Properties idToPackage, ClassLoader codeLoader) {
        return new IDocumentStaticServiceConfig() {
            public Optional<String> getModelPackage(String id) { return Optional.ofNullable(idToPackage.getProperty(id)); }
            public Optional<ClassLoader> getModelCodeClassLoader(String id) { return Optional.of(codeLoader); }
            public Optional<ILabelProvider> getLabelProvider() { return Optional.of(LABEL_OR_NAME); }
            public Optional<String> getVariant() { return Optional.empty(); }
        };
    }

    /** Compile {@code sources} ({@code zipPath -> code}) in-memory against this JVM's classpath; return a loader for them. */
    private static ClassLoader compileInMemory(Map<String, String> sources) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("a JDK (not a JRE) is required to compile the Java kernel baseline");
        }
        Map<String, ByteArrayOutputStream> compiled = new HashMap<>();
        StandardJavaFileManager std = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8);
        ForwardingJavaFileManager<StandardJavaFileManager> fm = new ForwardingJavaFileManager<>(std) {
            @Override
            public JavaFileObject getJavaFileForOutput(JavaFileManager.Location loc, String className,
                    JavaFileObject.Kind kind, FileObject sibling) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                compiled.put(className, bytes);
                return new SimpleJavaFileObject(
                        URI.create("mem:///" + className.replace('.', '/') + ".class"), JavaFileObject.Kind.CLASS) {
                    @Override public OutputStream openOutputStream() { return bytes; }
                };
            }
        };
        List<JavaFileObject> units = new ArrayList<>();
        sources.forEach((path, code) -> units.add(
                new SimpleJavaFileObject(URI.create("string:///" + path), JavaFileObject.Kind.SOURCE) {
                    @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return code; }
                }));
        // Inherit this test JVM's classpath so the generated code's com.mgmtp.* imports resolve.
        List<String> options = List.of("-classpath", System.getProperty("java.class.path"));
        DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
        boolean ok = compiler.getTask(null, fm, diags, options, null, units).call();
        if (!ok) {
            StringBuilder sb = new StringBuilder("generated Java failed to compile:\n");
            for (Diagnostic<? extends JavaFileObject> d : diags.getDiagnostics()) sb.append("  ").append(d).append('\n');
            throw new IllegalStateException(sb.toString());
        }
        return new ClassLoader(RuntimeLaws.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                ByteArrayOutputStream b = compiled.get(name);
                if (b == null) throw new ClassNotFoundException(name);
                byte[] arr = b.toByteArray();
                return defineClass(name, arr, 0, arr.length);
            }
        };
    }

    /**
     * The kernel's model-CONSISTENCY notifications (the MVK gate: `checkConsistency` after deserialize +
     * expand), each as {@code "SEVERITY: message"} in the kernel's order. The model-check stance oracle for a
     * foreign / hand-authored model the typed builders would refuse (e.g. an out-of-domain enum {@code sValue}).
     */
    static List<String> consistencyMessages(String modelJson) {
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
        List<String> out = new ArrayList<>();
        modelService.checkConsistency(model, n -> out.add(n.getSeverity() + ": " + n.getMessage()));
        return out;
    }

    /**
     * The model JSON as A12's OWN model serializer rewrites it (deserialize → serialize). Its use is to prove a
     * hand-authored fixture key genuinely BINDS in the kernel's model tree: a key the deserializer ignored would
     * be absent from the rewrite, so a test that reads a verdict "on a model declaring X" can show that the model
     * really does declare X rather than that the key was silently dropped.
     */
    static String modelRoundTrip(String modelJson) {
        IDocumentModelSerializer serializer = new DocumentModelServiceFactory().createDocumentModelSerializer();
        try {
            IDocumentModel model = serializer.deserialize(new StringReader(modelJson));
            StringWriter out = new StringWriter();
            serializer.serialize(model, out, n -> { });
            return out.toString();
        } catch (Exception e) {
            throw new RuntimeException("failed to round-trip model", e);
        }
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
            public Optional<ILabelProvider> getLabelProvider() { return Optional.of(LABEL_OR_NAME); }
            public Optional<IDocumentModelIdProvider> getModelIdProvider() { return Optional.empty(); }
        };
    }

    /** One explicit display-name profile on both kernel strategies, so a differential isolates generated code. */
    private static final ILabelProvider LABEL_OR_NAME = (field, locale) -> {
        String label = field.getLabel().get(locale);
        return Optional.of(label != null && !label.isBlank() ? label : field.getName());
    };

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

package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.core.DmKeys;
import io.github.mbackschat.a12.dm.cli.criterion.CriterionSpec;
import io.github.mbackschat.a12.dm.cli.rule.RuleMessages;
import io.github.mbackschat.a12.dm.modelkit.ElementDraft;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.read.ReadComputation;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;

/**
 * Shared {@code data}-payload builders for the read verbs (T3). The standalone {@code field/group/config/
 * typedef/include read} verbs and their {@code apply}-embedded {@code *_READ} op
 * peers used to build DIFFERENT (subset) shapes for the same read — group emitted 2 keys vs the
 * standalone's 8, config 4 vs ~10, typedef dropped {@code importedFrom}. These builders are the single
 * source of each shape, so both surfaces stay at parity by construction.
 */
public final class ReadPayloads {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DslRenderer EN = new DslRenderer(ConditionLanguage.EN);

    private ReadPayloads() {
    }

    /** The full targeted {@code field read} payload, shared by the standalone and embedded apply routes. */
    public static ObjectNode field(ModelDocument doc, String field) {
        ObjectNode data = MAPPER.createObjectNode();
        data.put("field", field);
        data.put("kind", doc.fieldKind(field).orElseThrow(
                () -> new IllegalArgumentException("no field '" + field + "' in the model")));
        doc.requiredness(field).ifPresent(requiredness -> {
            switch (requiredness) {
                case OPTIONAL -> data.put("required", false);
                case REQUIRED -> data.put("required", true);
                case REQUIRED_IF_PARENT_PRESENT -> data.put("required", "ifParentPresent");
            }
        });
        doc.labels(field).ifPresent(labels -> putIfNotEmpty(data, "label", labels));
        putIfNotEmpty(data, "helperText", doc.helperText(field));
        putIfNotEmpty(data, "internalDescription", doc.internalDescription(field));
        putIfNotEmpty(data, "externalDescription", doc.externalDescription(field));
        putIfNotEmpty(data, "annotations", doc.annotations(field));
        doc.fieldConfigJson(field).ifPresent(config -> data.setAll(configNode(config)));
        if (data.hasNonNull("typedef")) {
            doc.typeDefinitionConfigJson(data.get("typedef").asText())
                    .ifPresent(config -> data.set("resolvedType", configNode(config)));
        }
        return data;
    }

    /** The full {@code group read} data for one group ({@code doc} must contain {@code group}). */
    public static ObjectNode group(ModelDocument doc, String group) {
        ObjectNode data = MAPPER.createObjectNode();
        data.put("group", group);
        boolean repeatable = doc.isRepeating(group);
        data.put("repeatable", repeatable);
        if (repeatable) {
            data.put("maxRepetitions", doc.maxRepetitions(group));   // the numeric max rows, not just the boolean
        }
        // the index field (repetition row-key) so a `group modify --index-field` edit round-trips
        doc.indexField(group).ifPresent(idx -> data.put("indexField", idx));
        // the declared row sort fields (display/USB ordering) so a `group modify --sort-field` round-trips
        List<ElementDraft.SortField> sorts = doc.sortFields(group);
        if (!sorts.isEmpty()) {
            ArrayNode sortFields = data.putArray("sortFields");
            sorts.forEach(sf -> {
                ObjectNode n = sortFields.addObject();
                n.put("name", sf.fieldName());
                n.put("order", sf.order().name());
            });
        }
        // the group's DIRECT child fields, so a just-built composite group is confirmable through the read
        // alone (probe 10). Direct children only (a deeper field belongs to a sub-group).
        String prefix = group + "/";
        ArrayNode fields = data.putArray("fields");
        doc.fieldPaths().stream()
                .filter(p -> p.startsWith(prefix) && p.indexOf('/', prefix.length()) < 0)
                .forEach(fields::add);
        // target-specific reads retain their metadata projection (G45); `meta` is the write owner
        doc.labels(group).ifPresent(m -> putIfNotEmpty(data, "label", m));
        putIfNotEmpty(data, "annotations", doc.annotations(group));
        putIfNotEmpty(data, "internalDescription", doc.internalDescription(group));
        putIfNotEmpty(data, "externalDescription", doc.externalDescription(group));
        return data;
    }

    /** The full {@code config read} data (the whole {@code modelInfo}/{@code modelConfig}/header surface). */
    public static ObjectNode config(ModelDocument doc) {
        ModelDocument.DocConfig c = doc.config();
        ObjectNode data = MAPPER.createObjectNode();
        data.put("decimalSeparator", c.decimalSeparator());
        data.put("timeZone", c.timeZone());
        data.put("conditionLanguage", c.conditionLanguage());
        data.put("fieldRefByShortNameAllowed", c.fieldRefByShortNameAllowed());
        // singleton/range/atomic-grapheme entries; empty selects the BMP default (never supplementary chars)
        ArrayNode supported = data.putArray("supportedCharacters");
        c.supportedCharacters().forEach(supported::add);
        // the model-level per-locale label (header.labels) — the read peer of `config modify --label`
        Map<String, String> modelLabels = doc.modelLabels();
        if (!modelLabels.isEmpty()) {
            ObjectNode label = data.putObject("label");
            modelLabels.forEach(label::put);
        }
        doc.modelComment().ifPresent(comment -> data.put("comment", comment));
        doc.modelPacket().ifPresent(packet -> data.put(DmKeys.PACKET, packet));
        doc.baseYear().ifPresent(year -> data.put("baseYear", year));
        // the model-level header annotations (incl. the access-control `roles`, G26) so a roles edit round-trips
        Map<String, String> modelAnnotations = doc.modelAnnotations();
        if (!modelAnnotations.isEmpty()) {
            ObjectNode annotations = data.putObject("annotations");
            modelAnnotations.forEach(annotations::put);
        }
        // the declared message locales (header.locales) drive the per-locale-message law — surface the full set
        ArrayNode locales = data.putArray("locales");
        doc.locales().forEach(locales::add);
        return data;
    }

    /** The full {@code typedef read} (no-arg) data — the LOCAL ids plus the import sources ({@code importedFrom}). */
    public static ObjectNode typedefList(ModelDocument doc) {
        ObjectNode data = MAPPER.createObjectNode();
        ArrayNode arr = data.putArray("typedefs");
        doc.typeDefinitionIds().forEach(arr::add);
        List<String> imports = doc.typeDefImports();
        if (!imports.isEmpty()) {
            ArrayNode importsArr = data.putArray("importedFrom");
            imports.forEach(importsArr::add);
        }
        return data;
    }

    /** The {@code data} key of the {@code criterion read} list: A12's own member name. */
    public static final String CRITERIA = DmKeys.DOCUMENT_UNIQUENESS_CRITERIA;

    /** The no-arg {@code criterion read}: every criterion as a stored entry, in model order; empty when none. */
    public static ObjectNode criterionList(ModelDocument doc) {
        ObjectNode data = MAPPER.createObjectNode();
        ArrayNode criteria = data.putArray(CRITERIA);
        doc.uniquenessCriteria().forEach(criterion -> criteria.add(CriterionSpec.toJson(criterion)));
        return data;
    }

    /** One criterion by name as a stored entry, the first of that name; the caller has checked it exists. */
    public static ObjectNode criterion(ModelDocument doc, String name) {
        return doc.uniquenessCriteria().stream().filter(criterion -> criterion.name().equals(name)).findFirst()
                .map(CriterionSpec::toJson)
                .orElseThrow(() -> new IllegalArgumentException("the model has no uniqueness criterion named '" + name + "'"));
    }

    /** Every included model, by the reference that is its identity (KF651), on standalone and embedded reads. */
    public static ObjectNode includeList(ModelDocument doc) {
        ObjectNode data = MAPPER.createObjectNode();
        ArrayNode includes = data.putArray("includes");
        doc.includes().forEach(include -> includes.addObject().put("reference", include.reference()));
        return data;
    }

    /**
     * The full targeted {@code rule read} payload. The condition rides {@code converted}+{@code condition}
     * when the converter models it, else {@code canonicalText}+{@code reason} — tolerant, never an error, so a
     * read never fails on an Opaque construct. {@code doc} supplies the metadata projection, which sits on the
     * element node rather than in the adapter's condition/message view.
     */
    public static ObjectNode rule(KernelAdapter adapter, ModelDocument doc, String rule) {
        ObjectNode data = MAPPER.createObjectNode();
        data.put("rule", rule);
        data.put("severity", adapter.ruleSeverity(rule));
        data.put("errorCode", adapter.ruleErrorCode(rule));
        adapter.ruleErrorField(rule).ifPresent(f -> data.put("errorField", f));
        putRuleRead(data, adapter.read(rule));
        RuleMessages.putInto(data, adapter.ruleMessages(rule));
        putIfNotEmpty(data, "annotations", doc.annotations(rule));
        putIfNotEmpty(data, "internalDescription", doc.internalDescription(rule));
        putIfNotEmpty(data, "externalDescription", doc.externalDescription(rule));
        return data;
    }

    /**
     * The read outcome as output keys — one place for {@code rule read} and {@code model read}, written as an
     * exhaustive switch.
     *
     * <p>It was an {@code if}/{@code else if} chain over two states, which is the shape that silently emits
     * NOTHING when a third arrives: {@code converted} would have gone missing altogether rather than
     * reading false. The switch makes the compiler the guard for the next one.
     *
     * <p><b>The refused arm is compiler-mandated rather than reachable from here, and is NOT a documented
     * output key.</b> {@code ReadResult} is shared with the kernel-free bridge, which is the only producer
     * of {@code Refused} (DEV-API-GAPS DG1001); every CLI read goes through the kernel-backed adapter, whose
     * own layer answers first. The branch emits the opaque shape plus {@code refusedCode} so it is correct
     * if the CLI ever surfaces a bridge read — but until it does, that key stays out of the published
     * schema, because advertising a field no run can produce is the defect this estate calls out by name.
     */
    public static void putRuleRead(ObjectNode data, ReadResult read) {
        switch (read) {
            case ReadResult.Converted converted -> {
                data.put("converted", true);
                data.put("condition", EN.render(converted.condition()));
                // The waiver lives on the condition's root, not in the rendered text, so it rides as the flag
                // `rule add` takes; without it the returned text is the cross-scale equality A12 refuses (KF75).
                if (converted.allowsDifferingDecimals()) {
                    data.put("allowDifferingDecimals", true);
                }
            }
            case ReadResult.Opaque opaque -> {
                data.put("converted", false);
                data.put("canonicalText", opaque.canonicalText());
                data.put("reason", opaque.reason());
            }
            case ReadResult.Refused refused -> {
                data.put("converted", false);
                data.put("canonicalText", refused.canonicalText());
                data.put("reason", refused.reason());
                data.put("refusedCode", refused.code().name());
            }
        }
    }

    /**
     * The full targeted {@code computation read} payload. Beyond the target field it echoes the FORMULA — the
     * common precondition and each alternative's (precondition?, operation) as DSL — so the computed value is
     * confirmable from a read alone rather than only through {@code computation format}.
     *
     * <p>{@code group} (where the computation LIVES) is reported beside {@code calculatedField} (where it
     * WRITES) because A12 lets the two differ, and the pair is what a persisted-and-read-back check needs: with
     * only the calculated field, placement was legible solely as a prefix of the computation's own name-path,
     * so nothing confirmed that a declared group survived authoring. It is emitted unconditionally rather than
     * only when it differs from the default — a key whose absence means "derive it yourself" puts the rule back
     * in the reader, which is what made the round trip unconfirmable in the first place.
     */
    public static ObjectNode computation(KernelAdapter adapter, String computation) {
        ObjectNode data = MAPPER.createObjectNode();
        data.put("computation", computation);
        // Structural, so it is present for an Opaque read too: a computation's full name IS its group + name.
        data.put("group", computation.substring(0, computation.lastIndexOf('/')));
        ArrayNode suppressions = data.putArray("errorCodesToSuppress");
        adapter.computationErrorCodesToSuppress(computation).forEach(suppressions::add);
        // Owner-level like the suppressions, so an Opaque formula still shows it; absent means Exact, as in the
        // stored model, since A12's serializer never writes Exact.
        adapter.computationRoundingMode(computation).stored().ifPresent(mode -> data.put("roundingMode", mode));
        switch (adapter.readComputation(computation)) {
            case ReadComputation.Converted converted -> {
                data.put("converted", true);
                Computation comp = converted.computation();
                data.put("calculatedField", comp.computedField().path().value());
                DraftComputation draft = comp.draft();
                if (draft.commonPrecondition() != null) {
                    data.put("commonPrecondition", draft.commonPrecondition());
                }
                ArrayNode alternatives = data.putArray("alternatives");
                for (DraftComputation.Alternative alt : draft.alternatives()) {
                    ObjectNode node = alternatives.addObject();
                    if (alt.precondition() != null) {
                        node.put("precondition", alt.precondition());
                    }
                    node.put("operation", alt.operation());
                    if (alt.toleranceRangeOp() != null) {
                        node.put("toleranceRangeOp", alt.toleranceRangeOp().wire());
                    }
                }
                RuleMessages.putInto(data, comp.messages());
            }
            // Exhaustive for the reason putRuleRead states. The cast this replaced read "not Converted
            // means Opaque", so a third state would have been a ClassCastException on a computation the
            // kernel accepts — a crash, not a degraded read.
            case ReadComputation.Opaque opaque -> {
                data.put("converted", false);
                data.put("reason", opaque.reason());
            }
        }
        return data;
    }

    /**
     * A kit config-block read-back ({@code ModelDocument.fieldConfigJson}/{@code typeDefinitionConfigJson})
     * parsed into a merge-ready object node — the kits speak JSON text across the published boundary (Set W),
     * and the read verbs re-join it to their envelope here. The blocks carry only text/int/boolean leaves,
     * so the parse is lossless and the rendered envelope is byte-identical to the pre-Set-W node hand-off.
     */
    public static ObjectNode configNode(String configJson) {
        try {
            return (ObjectNode) MAPPER.readTree(configJson);
        } catch (IOException e) {
            throw new UncheckedIOException("unparseable config block from the kit read-back: " + configJson, e);
        }
    }

    /** Echo a per-locale/per-name metadata map under {@code key}, only when it carries something. */
    private static void putIfNotEmpty(ObjectNode data, String key, Map<String, String> m) {
        if (m != null && !m.isEmpty()) {
            data.set(key, MAPPER.valueToTree(m));
        }
    }
}

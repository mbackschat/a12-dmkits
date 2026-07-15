package io.github.mbackschat.a12.dm.adapter.capture.runner.kernel;

import java.io.StringReader;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.mgmtp.a12.kernel.md.document.api.IDocument;
import com.mgmtp.a12.kernel.md.document.api.services.IDocumentFactory;
import com.mgmtp.a12.kernel.md.document.apiV2.immutable.DocumentV2;
import com.mgmtp.a12.kernel.md.document.apiV2.immutable.FieldInstanceV2;
import com.mgmtp.a12.kernel.md.document.apiV2.immutable.GroupInstanceV2;
import com.mgmtp.a12.kernel.md.document.apiV2.immutable.RepetitionsV2;
import com.mgmtp.a12.kernel.md.facade.DocumentModelServiceFactory;
import com.mgmtp.a12.kernel.md.facade.DocumentServiceFactory;
import com.mgmtp.a12.kernel.md.model.api.IDocumentModel;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelReferenceResolver;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelSerializer;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelService;
import com.mgmtp.a12.kernel.md.rt.api.DocumentProcessingConfig;
import com.mgmtp.a12.kernel.md.rt.api.IComputedFieldInstance;
import com.mgmtp.a12.kernel.md.rt.api.IDocumentComputationResult;
import com.mgmtp.a12.kernel.md.rt.api.IDocumentDynamicServiceConfig;
import com.mgmtp.a12.kernel.md.rt.api.IDocumentModelIdProvider;
import com.mgmtp.a12.kernel.md.rt.api.IDocumentRtService;
import com.mgmtp.a12.kernel.md.rt.api.ILabelProvider;
import com.mgmtp.a12.kernel.md.rt.api.IMessage;
import com.mgmtp.a12.kernel.md.rt.api.IModelCode;
import com.mgmtp.a12.kernel.md.rt.api.IModelCodeCache;
import com.mgmtp.a12.utils.conversion.InstantRange;

import io.github.mbackschat.a12.dm.adapter.capture.dto.AppliedState;
import io.github.mbackschat.a12.dm.adapter.capture.dto.Availability;
import io.github.mbackschat.a12.dm.adapter.capture.dto.ComputeObservation;
import io.github.mbackschat.a12.dm.adapter.capture.dto.EvidenceValue;
import io.github.mbackschat.a12.dm.adapter.capture.dto.ObservedValue;
import io.github.mbackschat.a12.dm.adapter.capture.dto.Pointers;
import io.github.mbackschat.a12.dm.adapter.capture.dto.RunnerFidelity;
import io.github.mbackschat.a12.dm.adapter.capture.dto.RunnerId;
import io.github.mbackschat.a12.dm.adapter.capture.runner.CaseExecution;
import io.github.mbackschat.a12.dm.adapter.capture.schema.ScenarioSet;

/**
 * The shared kernel-route observation mapper — where kernel types TERMINATE (proposal §3; the
 * {@code verifyCapturePurity} boundary). Both kernel routes observe through exactly this mapper; only the
 * {@link IDocumentRtService} construction differs (dynamic Groovy vs static generated-Java — the two
 * registered routes), so a cross-route difference is a ROUTE difference, never a mapping one.
 *
 * <p>Everything is built FRESH per call: model expansion, factories, the rt service, and the document —
 * no cache or global state crosses cases or routes. The computation result is applied to the pristine
 * immutable input {@link DocumentV2} via the supported {@code applyTo(DocumentV2)} route (which RETURNS a
 * new document), and the applied state is read back at every probe pointer with absent / present-empty /
 * present-value kept distinct.
 */
final class KernelRouteSupport {

    private static final Pattern HEADER_ID = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");

    private KernelRouteSupport() {
    }

    // ---- fresh kernel construction --------------------------------------------------------------------

    /** Deserialize + expand (self-contained) the model JSON — fresh each call. */
    static IDocumentModel expandedModel(String modelJson) {
        DocumentModelServiceFactory factory = new DocumentModelServiceFactory();
        IDocumentModelSerializer serializer = factory.createDocumentModelSerializer();
        IDocumentModelService modelService = factory.createDocumentModelService();
        IDocumentModel model;
        try {
            model = serializer.deserialize(new StringReader(modelJson));
        } catch (Exception e) {
            throw new IllegalArgumentException("model DM-JSON failed to deserialize", e);
        }
        modelService.expand(model, (IDocumentModelReferenceResolver) ref -> {
            throw new IllegalStateException("scenario models are self-contained; unexpected reference: " + ref);
        });
        return model;
    }

    /** A fresh dynamic-service config (fresh code cache — nothing shared across cases/routes). */
    static IDocumentDynamicServiceConfig freshDynamicConfig() {
        IModelCodeCache cache = new IModelCodeCache() {
            private final Map<String, IModelCode> codes = new HashMap<>();

            @Override
            public IModelCode getModelCode(String id) {
                return codes.get(id);
            }

            @Override
            public void addModelCode(String id, IModelCode code) {
                codes.put(id, code);
            }
        };
        return new IDocumentDynamicServiceConfig() {
            @Override
            public IModelCodeCache getCache() {
                return cache;
            }

            @Override
            public Optional<String> getVariant() {
                return Optional.empty();
            }

            @Override
            public Optional<ILabelProvider> getLabelProvider() {
                return Optional.empty();
            }

            @Override
            public Optional<IDocumentModelIdProvider> getModelIdProvider() {
                return Optional.empty();
            }
        };
    }

    // ---- the shared observation -----------------------------------------------------------------------

    /** Observe {@code execution} through {@code rt} (whichever route built it), as {@code runner}. */
    static ComputeObservation observe(RunnerId runner, IDocumentRtService rt, DocumentServiceFactory dsf,
            CaseExecution execution) {
        DocumentV2 input = buildInstance(dsf, execution);
        DocumentProcessingConfig config = processingConfig(execution);

        IDocumentComputationResult result = rt.compute(input, config);

        List<ComputeObservation.ValueEntry> withoutErrors = new ArrayList<>();
        for (IComputedFieldInstance c : result.getComputedFieldInstancesWithoutErrors()) {
            withoutErrors.add(valueEntry(c, execution));
        }
        List<ComputeObservation.ValueEntry> changedSubset = new ArrayList<>();
        for (IComputedFieldInstance c : result.getComputedFieldInstancesWithChanges()) {
            changedSubset.add(valueEntry(c, execution));
        }
        List<ComputeObservation.ErrorEntry> withErrors = new ArrayList<>();
        for (IComputedFieldInstance c : result.getComputedFieldInstancesWithErrors()) {
            withErrors.add(new ComputeObservation.ErrorEntry(pointerOf(c),
                    execution.declaredComputationOf(pointerOf(c)), observedValue(c), causeOf(c)));
        }
        List<ComputeObservation.ClearedEntry> cleared = new ArrayList<>();
        for (IComputedFieldInstance c : result.getClearedFieldInstances()) {
            cleared.add(new ComputeObservation.ClearedEntry(pointerOf(c),
                    execution.declaredComputationOf(pointerOf(c))));
        }
        List<ComputeObservation.DiagnosticEntry> formal = new ArrayList<>();
        for (IMessage m : result.getFormalErrorsInOperands()) {
            formal.add(new ComputeObservation.DiagnosticEntry(m.getErrorCode(), m.getMessageType().name(),
                    Pointers.format(m.getErrorFieldPointer().fullName(),
                            m.getErrorFieldPointer().repetitionIndexes())));
        }

        // Channel 6: apply the PUBLIC result to the pristine immutable input (applyTo RETURNS a new doc).
        DocumentV2 applied = result.applyTo(input);
        List<ComputeObservation.AppliedEntry> appliedEntries = new ArrayList<>();
        for (String probe : execution.probePointers()) {
            appliedEntries.add(new ComputeObservation.AppliedEntry(probe, appliedStateAt(applied, probe)));
        }

        return ComputeObservation.success(execution.caseId(), runner)
                .withoutErrors(ComputeObservation.Channel.available(RunnerFidelity.ALL_COMPUTED_CLEAN, withoutErrors))
                .changedSubset(ComputeObservation.Channel.available(RunnerFidelity.DELTA_VS_INPUT, changedSubset))
                .withErrors(ComputeObservation.Channel.available(RunnerFidelity.ERRORED_INSTANCES, withErrors))
                .cleared(ComputeObservation.Channel.available(RunnerFidelity.INPUT_FILLED_ONLY, cleared))
                .formalErrorsInOperands(
                        ComputeObservation.Channel.available(RunnerFidelity.OPERAND_FORMAL_ERRORS, formal))
                .appliedState(ComputeObservation.Channel.available(RunnerFidelity.REQUESTED_PROBES, appliedEntries))
                .build();
    }

    // ---- instance building + config ---------------------------------------------------------------------

    private static DocumentV2 buildInstance(DocumentServiceFactory dsf, CaseExecution execution) {
        IDocumentFactory df = dsf.createDocumentFactory();
        IDocument document = df.createDocument(modelId(execution.modelJson()));
        for (ScenarioSet.Placement p : execution.placements()) {
            int[] reps = p.reps().stream().mapToInt(Integer::intValue).toArray();
            switch (p.kind()) {
                case GROUP -> document.addEntityInstance(df.createGroupInstance(p.path(), reps));
                case FIELD -> document.addEntityInstance(df.createFieldInstance(p.path(), reps,
                        p.value().orElseThrow()));
                case EMPTY -> document.addEntityInstance(df.createFieldInstance(p.path(), reps));
            }
        }
        return dsf.createDocumentV1V2Converter().ofDocumentV1(document);
    }

    private static DocumentProcessingConfig processingConfig(CaseExecution execution) {
        Locale locale = Locale.forLanguageTag(execution.locale().replace('_', '-'));
        var builder = DocumentProcessingConfig.builder(locale);
        execution.clock().ifPresent(clock -> builder.currentDateForTest(
                Date.from(LocalDateTime.parse(clock).atZone(ZoneId.systemDefault()).toInstant())));
        return builder.build();
    }

    private static String modelId(String modelJson) {
        Matcher m = HEADER_ID.matcher(modelJson);
        if (m.find()) {
            return m.group(1);   // the header id is the first "id" in the document
        }
        throw new IllegalArgumentException("no header id in model JSON");
    }

    // ---- entry mapping ----------------------------------------------------------------------------------

    private static ComputeObservation.ValueEntry valueEntry(IComputedFieldInstance c, CaseExecution execution) {
        String pointer = pointerOf(c);
        return new ComputeObservation.ValueEntry(pointer, execution.declaredComputationOf(pointer),
                observedValue(c));
    }

    private static ObservedValue observedValue(IComputedFieldInstance c) {
        EvidenceValue typed = evidenceValue(c.getValueV2());
        String rendered = c.getStringValue().orElse(null);
        return new ObservedValue(
                typed == null ? Availability.NOT_APPLICABLE : Availability.AVAILABLE, typed,
                rendered == null ? Availability.NOT_APPLICABLE : Availability.AVAILABLE, rendered);
    }

    private static ComputeObservation.CauseObservation causeOf(IComputedFieldInstance c) {
        return c.getErrorMessage()
                .map(m -> ComputeObservation.CauseObservation.of(m.getErrorCode(), m.getMessageType().name(),
                        Pointers.format(m.getErrorFieldPointer().fullName(),
                                m.getErrorFieldPointer().repetitionIndexes())))
                .orElseGet(() -> new ComputeObservation.CauseObservation(Availability.NOT_APPLICABLE,
                        null, null, null));
    }

    private static String pointerOf(IComputedFieldInstance c) {
        return Pointers.format(c.pointer().fullName(), c.pointer().repetitionIndexes());
    }

    /** Map a kernel {@code valueV2} to the closed evidence vocabulary; an unknown type fails the run
     *  loudly ({@code technicalFailure}) rather than widening the vocabulary silently. */
    private static EvidenceValue evidenceValue(Object valueV2) {
        return switch (valueV2) {
            case null -> null;
            case String s -> new EvidenceValue.StringValue(s);
            case BigDecimal d -> new EvidenceValue.DecimalValue(d.toPlainString());
            case Boolean b -> new EvidenceValue.BooleanValue(b);
            case Instant i -> new EvidenceValue.InstantValue(i.toString());
            case InstantRange r -> new EvidenceValue.InstantRangeValue(r.start().toString(), r.end().toString());
            case Number n -> new EvidenceValue.DecimalValue(new BigDecimal(n.toString()).toPlainString());
            default -> throw new IllegalStateException(
                    "unmappable typed value kind — the closed evidence vocabulary is STRING/DECIMAL/BOOLEAN/"
                            + "INSTANT/INSTANT_RANGE");
        };
    }

    // ---- the applied-state read-back (channel 6) ---------------------------------------------------------

    /** The post-application state at {@code probe} — absent / present-empty / present-value kept distinct. */
    private static AppliedState appliedStateAt(DocumentV2 applied, String probe) {
        Pointers.Parsed parsed = Pointers.parse(probe);
        String[] segments = parsed.namePath().substring(1).split("/");
        GroupInstanceV2 current = applied;
        for (int i = 0; i < segments.length - 1; i++) {
            RepetitionsV2 reps = current.directSubgroup(segments[i]);
            int index = parsed.reps().get(i);
            GroupInstanceV2 row = reps.size() >= index ? reps.get(index) : null;
            if (row == null) {
                return new AppliedState.Absent();
            }
            current = row;
        }
        FieldInstanceV2 field = current.directField(segments[segments.length - 1]);
        if (field == null) {
            return new AppliedState.Absent();
        }
        Object value = field.value();
        if (value == null) {
            return new AppliedState.PresentEmpty();
        }
        // The DocumentV2 read surface exposes the TYPED value; a rendered form is not part of that read.
        return new AppliedState.PresentValue(new ObservedValue(
                Availability.AVAILABLE, evidenceValue(value), Availability.NOT_EXPOSED_BY_RUNNER, null));
    }
}

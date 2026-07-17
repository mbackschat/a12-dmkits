package io.github.mbackschat.a12.dm.adapter.capture.runner.interpreter;

import java.util.ArrayList;
import java.util.List;

import io.github.mbackschat.a12.dm.adapter.capture.dto.AppliedState;
import io.github.mbackschat.a12.dm.adapter.capture.dto.ComputeObservation;
import io.github.mbackschat.a12.dm.adapter.capture.dto.ObservedValue;
import io.github.mbackschat.a12.dm.adapter.capture.dto.Pointers;
import io.github.mbackschat.a12.dm.adapter.capture.dto.RunStatus;
import io.github.mbackschat.a12.dm.adapter.capture.dto.RunnerFidelity;
import io.github.mbackschat.a12.dm.adapter.capture.dto.RunnerId;
import io.github.mbackschat.a12.dm.adapter.capture.runner.CaseExecution;
import io.github.mbackschat.a12.dm.adapter.capture.runner.ComputeRunner;
import io.github.mbackschat.a12.dm.adapter.capture.schema.CaseDocumentIdentity;
import io.github.mbackschat.a12.dm.adapter.capture.schema.ScenarioSet;
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter;
import io.github.mbackschat.a12.dm.interpreter.UnsupportedConstructException;
import io.github.mbackschat.a12.dm.interpreter.eval.Document;
import io.github.mbackschat.a12.dm.interpreter.eval.DocumentKt;
import io.github.mbackschat.a12.dm.interpreter.eval.StoredCellState;
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader;
import io.github.mbackschat.a12.dm.interpreter.model.Computed;
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome;
import io.github.mbackschat.a12.dm.interpreter.model.EvalClock;
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef;

/**
 * The {@code a12-dmkits-interpreter} route — clean-room TRIANGULATION, never the oracle. READ-ONLY reuse
 * of the interpreter's published surface ({@code ModelLoader} · {@code DmInterpreter.compute} ·
 * {@code applyComputations} · {@code Document.cellStateAt}); the fidelity gaps are declared honestly
 * (proposal §4), never bridged: no typed values, no structured causes, no changed subset (report-all
 * granularity), and no operand formal errors on the compute surface. The applied read-back keeps
 * absent / present-empty / present-value distinct via {@code cellStateAt} (INTERPRETER-FINDINGS IF126,
 * graduated from IG72). Missing transport is never rendered as an empty observation.
 */
public final class InterpreterRunner implements ComputeRunner {

    @Override
    public RunnerId id() {
        return RunnerId.A12_DMKITS_INTERPRETER;
    }

    @Override
    public ComputeObservation observe(CaseExecution execution) {
        ModelDef def = ModelLoader.INSTANCE.load(execution.modelJson());
        CaseDocumentIdentity.Recorder consumed = CaseDocumentIdentity.recorder();
        Document doc = replay(def, execution.placements(), consumed);
        EvalClock clock = execution.clock()
                .map(c -> new EvalClock(c.substring(0, c.indexOf('T')), c))
                .orElse(null);
        DmInterpreter interpreter = new DmInterpreter(def, clock);

        List<Computed> outcomes;
        Document applied;
        try {
            outcomes = interpreter.compute(doc, execution.locale());
            applied = interpreter.applyComputations(doc, execution.locale());
        } catch (UnsupportedConstructException e) {
            // The interpreter's own declared inability (our clean-room text) — never a silent skip.
            return ComputeObservation.failed(execution.caseId(), id(), RunStatus.UNSUPPORTED, e.getMessage());
        }

        List<ComputeObservation.ValueEntry> values = new ArrayList<>();
        List<ComputeObservation.ErrorEntry> errored = new ArrayList<>();
        List<ComputeObservation.ClearedEntry> cleared = new ArrayList<>();
        for (Computed outcome : outcomes) {
            String pointer = Pointers.format(outcome.getPath(), outcome.getIndices());
            String declared = execution.declaredComputationOf(pointer);
            switch (outcome.getOutcome()) {
                case VALUE -> values.add(new ComputeObservation.ValueEntry(pointer, declared,
                        ObservedValue.renderedOnly(outcome.getValue())));
                case ERRORED -> errored.add(new ComputeObservation.ErrorEntry(pointer, declared,
                        ObservedValue.renderedOnly(outcome.getValue()),
                        ComputeObservation.CauseObservation.notExposed()));
                case CLEARED -> cleared.add(new ComputeObservation.ClearedEntry(pointer, declared));
            }
        }

        List<ComputeObservation.AppliedEntry> appliedEntries = new ArrayList<>();
        for (String probe : execution.probePointers()) {
            appliedEntries.add(new ComputeObservation.AppliedEntry(probe, appliedStateAt(applied, probe)));
        }

        return ComputeObservation.success(execution.caseId(), id())
                .consumedDocument(consumed.identity())
                .withoutErrors(ComputeObservation.Channel.available(RunnerFidelity.ALL_COMPUTED_CLEAN, values))
                .changedSubset(ComputeObservation.Channel.notExposed())
                .withErrors(ComputeObservation.Channel.available(RunnerFidelity.ERRORED_INSTANCES, errored))
                .cleared(ComputeObservation.Channel.available(RunnerFidelity.ALL_CLEARED, cleared))
                .formalErrorsInOperands(ComputeObservation.Channel.notExposed())
                .appliedState(ComputeObservation.Channel.available(RunnerFidelity.REQUESTED_PROBES, appliedEntries))
                .build();
    }

    /** Replay the placements, recording each AS it is applied to the interpreter document (the recorder
     *  call shares the exact locals the engine call consumes — the consumed-document binding). */
    private static Document replay(ModelDef def, List<ScenarioSet.Placement> placements,
            CaseDocumentIdentity.Recorder consumed) {
        Document doc = DocumentKt.newDocument(def);
        for (ScenarioSet.Placement p : placements) {
            int[] reps = p.reps().stream().mapToInt(Integer::intValue).toArray();
            switch (p.kind()) {
                case GROUP -> {
                    doc.group(p.path(), reps);
                    consumed.group(p.path(), p.reps());
                }
                case FIELD -> {
                    String value = p.value().orElseThrow();
                    doc.field(p.path(), value, reps);
                    consumed.field(p.path(), p.reps(), value);
                }
                case EMPTY -> {
                    doc.emptyField(p.path(), reps);
                    consumed.empty(p.path(), p.reps());
                }
            }
        }
        return doc;
    }

    /** The applied read-back over the interpreter's public {@code Document.cellStateAt} (IF126): absent /
     *  present-empty / present-value(raw, rendered form only) kept distinct — the same three states the
     *  kernel routes read off the applied {@code DocumentV2}. */
    private static AppliedState appliedStateAt(Document applied, String probe) {
        Pointers.Parsed parsed = Pointers.parse(probe);
        StoredCellState state = applied.cellStateAt(parsed.namePath(), parsed.reps());
        return switch (state) {
            case StoredCellState.Absent ignored -> new AppliedState.Absent();
            case StoredCellState.PresentEmpty ignored -> new AppliedState.PresentEmpty();
            case StoredCellState.PresentValue value ->
                    new AppliedState.PresentValue(ObservedValue.renderedOnly(value.getRaw()));
            default -> throw new IllegalStateException("unmapped cell state: " + state);
        };
    }
}

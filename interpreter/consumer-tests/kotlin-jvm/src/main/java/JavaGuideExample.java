// tag::interpreter-java-start[]
import io.github.mbackschat.a12.dm.interpreter.ComputationReport;
import io.github.mbackschat.a12.dm.interpreter.JavaModelSourceProvider;
import io.github.mbackschat.a12.dm.interpreter.ModelWorkspace;
import io.github.mbackschat.a12.dm.interpreter.ModelWorkspaceJava;
import io.github.mbackschat.a12.dm.interpreter.ValidationReport;
import io.github.mbackschat.a12.dm.interpreter.WorkspaceModelSource;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class JavaGuideExample {
    public record Result(String starterJson, ValidationReport validation, ComputationReport computation) {}

    public static Result run(String modelJson) {
        var source = WorkspaceModelSource.builder().json(modelJson).build();
        var prepared = ModelWorkspace.fromSources(List.of(source)).prepare("permit-basic");
        var starterJson = prepared.getDocuments().encodeDocument(
                prepared.getDocuments().seedDocument());
        var document = prepared.getDocuments().readDocument("""
                {"Permit":{"ApplicationNo":"P-42","RequestedArea":120}}""");
        var interpreter = prepared.createInterpreter();
        return new Result(
                starterJson,
                interpreter.validateFull(document),
                interpreter.compute(document));
    }

    public static CompletionStage<String> collectEntryModelId(String modelJson) {
        var source = WorkspaceModelSource.builder().json(modelJson).build();
        JavaModelSourceProvider provider =
                (modelId, context) -> CompletableFuture.completedFuture(source);
        return ModelWorkspaceJava.collect("permit-basic", provider)
                .thenApply(workspace -> workspace.prepare("permit-basic").getEntryModelId());
    }
}
// end::interpreter-java-start[]

final class JavaRetainedResultConsumer {
    static void verify(String modelJson) {
        var source = WorkspaceModelSource.builder().json(modelJson).build();
        var prepared = ModelWorkspace.fromSources(List.of(source)).prepare("permit-basic");
        var snapshot = prepared.getDocuments().readDocument("""
                {"Permit":{"ApplicationNo":"P-42","RequestedArea":120}}""");
        var interpreter = prepared.createInterpreter();
        var retained = interpreter.compute(snapshot);
        if (retained.getActions().stream()
                .noneMatch(action -> action.getPointer().getPath().equals("/Permit/ReviewedArea"))) {
            throw new AssertionError("Java retained computation action is missing");
        }
        if (!retained.getNoErrorOccurred()
                || !retained.getFormalErrorsInOperands().isEmpty()
                || retained.getResults().stream().anyMatch(result -> result.getDiagnostic() != null)) {
            throw new AssertionError("Java clean computation diagnostics are inconsistent");
        }
        var malformed = prepared.getDocuments().readDocument("""
                {"Permit":{"ApplicationNo":"P-42","RequestedArea":"not-a-number"}}""");
        var malformedReport = interpreter.compute(malformed);
        if (malformedReport.getNoErrorOccurred()
                || malformedReport.getFormalErrorsInOperands().stream()
                    .noneMatch(finding -> finding.getCode().equals("zahlHatUngueltigeZeichen"))) {
            throw new AssertionError("Java formal computation diagnostic is missing");
        }
        var applied = retained.applyTo(snapshot);
        if (!prepared.getDocuments().encodeDocument(applied).contains("\"ReviewedArea\":120")) {
            throw new AssertionError("Java retained computation application failed");
        }
        if (!prepared.getDocuments().encodeDocument(interpreter.applyComputations(snapshot))
                .equals(prepared.getDocuments().encodeDocument(applied))) {
            throw new AssertionError("Java same-document convenience diverged");
        }
    }
}

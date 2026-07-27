// tag::interpreter-java-start[]
import io.github.mbackschat.a12.dm.interpreter.ComputationReport;
import io.github.mbackschat.a12.dm.interpreter.DocumentInputV1;
import io.github.mbackschat.a12.dm.interpreter.FieldPlacementInput;
import io.github.mbackschat.a12.dm.interpreter.GroupPlacementInput;
import io.github.mbackschat.a12.dm.interpreter.JavaModelSourceProvider;
import io.github.mbackschat.a12.dm.interpreter.ModelWorkspace;
import io.github.mbackschat.a12.dm.interpreter.ModelWorkspaceJava;
import io.github.mbackschat.a12.dm.interpreter.ValidationReport;
import io.github.mbackschat.a12.dm.interpreter.WorkspaceModelSource;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class JavaGuideExample {
    public record Result(ValidationReport validation, ComputationReport computation) {}

    public static Result run(String modelJson) {
        var source = WorkspaceModelSource.builder().json(modelJson).build();
        var prepared = ModelWorkspace.fromSources(List.of(source)).prepare("permit-basic");
        var input = new DocumentInputV1(
                List.of(new GroupPlacementInput("/Permit", List.of(1))),
                List.of(
                        new FieldPlacementInput("/Permit/ApplicationNo", List.of(1, 1), "P-42"),
                        new FieldPlacementInput("/Permit/RequestedArea", List.of(1, 1), "120")),
                1);
        var document = prepared.getDocuments().readDocument(input);
        var interpreter = prepared.createInterpreter();
        return new Result(
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

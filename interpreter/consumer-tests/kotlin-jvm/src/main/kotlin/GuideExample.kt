// tag::interpreter-kotlin-start[]
import io.github.mbackschat.a12.dm.interpreter.ComputationReport
import io.github.mbackschat.a12.dm.interpreter.DocumentInputV1
import io.github.mbackschat.a12.dm.interpreter.FieldPlacementInput
import io.github.mbackschat.a12.dm.interpreter.GroupPlacementInput
import io.github.mbackschat.a12.dm.interpreter.ModelWorkspace
import io.github.mbackschat.a12.dm.interpreter.ValidationReport
import io.github.mbackschat.a12.dm.interpreter.WorkspaceModelSource

data class InterpreterResult(
    val validation: ValidationReport,
    val computation: ComputationReport,
)

fun runInterpreter(modelJson: String): InterpreterResult {
    val source = WorkspaceModelSource.builder().json(modelJson).build()
    val prepared = ModelWorkspace.fromSources(listOf(source)).prepare("permit-basic")
    val document = prepared.documents.readDocument(
        DocumentInputV1(
            groups = listOf(GroupPlacementInput("/Permit", listOf(1))),
            fields = listOf(
                FieldPlacementInput("/Permit/ApplicationNo", listOf(1, 1), "P-42"),
                FieldPlacementInput("/Permit/RequestedArea", listOf(1, 1), "120"),
            ),
        ),
    )
    val interpreter = prepared.createInterpreter()
    return InterpreterResult(
        validation = interpreter.validateFull(document),
        computation = interpreter.compute(document),
    )
}
// end::interpreter-kotlin-start[]

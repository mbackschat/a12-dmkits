// tag::interpreter-kotlin-start[]
import io.github.mbackschat.a12.dm.interpreter.ComputationReport
import io.github.mbackschat.a12.dm.interpreter.ModelWorkspace
import io.github.mbackschat.a12.dm.interpreter.ValidationReport
import io.github.mbackschat.a12.dm.interpreter.WorkspaceModelSource

data class InterpreterResult(
    val starterJson: String,
    val validation: ValidationReport,
    val computation: ComputationReport,
)

fun runInterpreter(modelJson: String): InterpreterResult {
    val source = WorkspaceModelSource.builder().json(modelJson).build()
    val prepared = ModelWorkspace.fromSources(listOf(source)).prepare("permit-basic")
    val starterJson = prepared.documents.encodeDocument(prepared.documents.seedDocument())
    val document = prepared.documents.readDocument(
        """{"Permit":{"ApplicationNo":"P-42","RequestedArea":120}}""",
    )
    val interpreter = prepared.createInterpreter()
    return InterpreterResult(
        starterJson = starterJson,
        validation = interpreter.validateFull(document),
        computation = interpreter.compute(document),
    )
}
// end::interpreter-kotlin-start[]

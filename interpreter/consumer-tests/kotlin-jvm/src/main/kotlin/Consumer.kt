import io.github.mbackschat.a12.dm.interpreter.FieldPlacementInput
import io.github.mbackschat.a12.dm.interpreter.GroupPlacementInput
import io.github.mbackschat.a12.dm.interpreter.ModelWorkspace
import io.github.mbackschat.a12.dm.interpreter.DocumentInputV1
import io.github.mbackschat.a12.dm.interpreter.WorkspaceModelSource

fun main() {
    val modelJson = checkNotNull(
        Thread.currentThread().contextClassLoader.getResource("permit-basic.dm.json"),
    ).readText()
    val source = WorkspaceModelSource.builder().json(modelJson).build()
    val prepared = ModelWorkspace.fromSources(listOf(source)).prepare("permit-basic")
    val snapshot = prepared.documents.readDocument(
        DocumentInputV1(
            groups = listOf(GroupPlacementInput("/Permit", listOf(1))),
            fields = listOf(
                FieldPlacementInput("/Permit/ApplicationNo", listOf(1, 1), "P-42"),
                FieldPlacementInput("/Permit/RequestedArea", listOf(1, 1), "120"),
            ),
        ),
    )
    val interpreter = prepared.createInterpreter()
    val validation = interpreter.validateFull(snapshot)
    val computation = interpreter.compute(snapshot)

    check(validation.findings.map { it.code } == listOf("AREA_NEEDS_NOTE"))
    check(computation.results.single().pointer.path == "/Permit/ReviewedArea")
    check(computation.results.single().value == "120")
    println("staged Maven interpreter consumer passed")
}

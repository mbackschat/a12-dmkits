import io.github.mbackschat.a12.dm.interpreter.FieldPlacementInput
import io.github.mbackschat.a12.dm.interpreter.GroupPlacementInput
import io.github.mbackschat.a12.dm.interpreter.ModelWorkspace
import io.github.mbackschat.a12.dm.interpreter.DocumentInputV1
import io.github.mbackschat.a12.dm.interpreter.WorkspaceModelSource

@JsModule("node:fs")
@JsNonModule
private external object NodeFileSystem {
    fun readFileSync(path: String, encoding: String): String
}

fun main() {
    val modelJson = NodeFileSystem.readFileSync(
        "kotlin/permit-basic.dm.json",
        "utf8",
    )
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

    check(interpreter.validateFull(snapshot).findings.map { it.code } == listOf("AREA_NEEDS_NOTE"))
    check(interpreter.compute(snapshot).results.single().value == "120")
    println("staged Maven Kotlin/JS interpreter consumer passed")
}

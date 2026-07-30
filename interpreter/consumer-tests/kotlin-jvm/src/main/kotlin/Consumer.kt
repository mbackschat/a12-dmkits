fun main() {
    val modelJson = checkNotNull(
        Thread.currentThread().contextClassLoader.getResource("permit-basic.dm.json"),
    ).readText()
    val kotlinResult = runInterpreter(modelJson)
    val javaResult = JavaGuideExample.run(modelJson)

    check(kotlinResult.validation.findings.map { it.code } == listOf("AREA_NEEDS_NOTE"))
    check(kotlinResult.computation.results.single().pointer.path == "/Permit/ReviewedArea")
    check(kotlinResult.computation.results.single().value == "120")
    check(javaResult.validation().findings.map { it.code } == listOf("AREA_NEEDS_NOTE"))
    check(javaResult.computation().results.single().value == "120")
    JavaRetainedResultConsumer.verify(modelJson)
    check(JavaGuideExample.collectEntryModelId(modelJson).toCompletableFuture().get() == "permit-basic")
    println("staged Maven interpreter consumer passed")
}

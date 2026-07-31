fun main() {
    val modelJson = checkNotNull(
        Thread.currentThread().contextClassLoader.getResource("permit-basic.dm.json"),
    ).readText()
    val kotlinResult = runInterpreter(modelJson)
    val javaResult = JavaGuideExample.run(modelJson)

    check(kotlinResult.starterJson.contains("\"EstimatedFee\""))
    check(kotlinResult.validation.findings.map { it.code } == listOf("AREA_NEEDS_NOTE"))
    check(kotlinResult.computation.results.single { it.pointer.path == "/Permit/ReviewedArea" }.value == "120")
    check(kotlinResult.computation.noErrorOccurred)
    check(kotlinResult.computation.formalErrorsInOperands.isEmpty())
    check(kotlinResult.computation.results.all { it.diagnostic == null })
    check(javaResult.starterJson().contains("\"EstimatedFee\""))
    check(javaResult.validation().findings.map { it.code } == listOf("AREA_NEEDS_NOTE"))
    check(javaResult.computation().results.single { it.pointer.path == "/Permit/ReviewedArea" }.value == "120")
    JavaRetainedResultConsumer.verify(modelJson)
    check(JavaGuideExample.collectEntryModelId(modelJson).toCompletableFuture().get() == "permit-basic")
    println("staged Maven interpreter consumer passed")
}

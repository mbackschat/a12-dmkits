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

    // The backend-service shape: a bounded cache of prepared models under concurrent requests. Asserted as
    // well as printed, so the demo is a regression detector and not just illustrative output.
    val serviceReport = runServiceCacheExample(modelJson)
    check(serviceReport.contains("requests served    : 12")) { serviceReport }
    // 12 concurrent requests over 3 models cost 3 preparations when the cache fits the working set.
    check(serviceReport.contains("models prepared    : 3\n  evictions          : 0")) { serviceReport }
    // One entry too small thrashes: every request evicts the entry the next one wants.
    check(serviceReport.contains("models prepared    : 12")) { serviceReport }
    // Identical content shares a fingerprint, yet the two entries' objects are not interchangeable.
    // Assert WHAT was answered, not only that 12 answers arrived: a contamination regression that returned
    // zero findings for every request would otherwise still satisfy the count.
    check(serviceReport.contains("findings/request   : [1]")) { serviceReport }
    check(serviceReport.contains("same fingerprint   : true")) { serviceReport }
    check(serviceReport.contains("cross-entry document : rejected")) { serviceReport }
    println(serviceReport)

    println("staged Maven interpreter consumer passed")
}

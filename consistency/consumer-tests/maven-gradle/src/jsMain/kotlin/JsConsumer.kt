@JsModule("node:fs")
@JsNonModule
private external object NodeFiles {
    fun readFileSync(path: String, encoding: String): String
}

@JsModule("node:process")
@JsNonModule
private external object NodeProcess {
    val env: dynamic
}

fun main() {
    val directory = NodeProcess.env.CHECKER_FIXTURES as String
    verifyChecker(
        NodeProcess.env.CHECKER_VERSION as String,
        NodeFiles.readFileSync("$directory/storefront.dm.json", "utf8"),
        NodeFiles.readFileSync("$directory/storefront-broken.dm.json", "utf8"),
    )
}

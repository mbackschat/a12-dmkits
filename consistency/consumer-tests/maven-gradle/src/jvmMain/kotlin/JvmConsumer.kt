import java.nio.file.Files
import java.nio.file.Path

fun main() {
    val directory = Path.of(System.getenv("CHECKER_FIXTURES"))
    verifyChecker(
        System.getenv("CHECKER_VERSION"),
        Files.readString(directory.resolve("storefront.dm.json")),
        Files.readString(directory.resolve("storefront-broken.dm.json")),
    )
}
